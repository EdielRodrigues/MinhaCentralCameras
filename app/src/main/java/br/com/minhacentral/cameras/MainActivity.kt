package br.com.minhacentral.cameras

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

class MainActivity : AppCompatActivity() {
 private lateinit var root: LinearLayout
 private var player: ExoPlayer? = null
 private val prefs by lazy { getSharedPreferences("cameras", MODE_PRIVATE) }
 private val cameras get() = JSONArray(prefs.getString("list", "[]"))
 private fun label(t: String, size: Float = 17f): TextView = TextView(this).apply { text=t; textSize=size; setPadding(8,12,8,12) }
 private fun button(t:String, action:()->Unit) = Button(this).apply { text=t; setOnClickListener { action() } }
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
 private fun page(title:String) {
  root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,22,20,20) }
  val scroll=ScrollView(this).apply { addView(root) }; setContentView(scroll)
  root.addView(label(title,24f))
 }
 private fun home() {
  player?.release(); player=null
  page("Minha Central de Câmeras")
  root.addView(label("Central Android • câmeras IP e RTSP",14f))
  root.addView(button("+ Adicionar câmera") { addDialog() })
  root.addView(button("Ler QR Code") { IntentIntegrator(this).setPrompt("Aponte para o QR Code da câmera").setBeepEnabled(false).initiateScan() })
  root.addView(button("Buscar ONVIF na rede Wi-Fi") { discover() })
  root.addView(button("Configurar Wi-Fi do celular") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
  root.addView(label("Minhas câmeras",20f))
  val list=cameras
  for (i in 0 until list.length()) {
   val c=list.getJSONObject(i)
   root.addView(button("▶ ${c.optString("name")}  •  ${c.optString("host")}") { cameraScreen(i) })
  }
  root.addView(label("Compatibilidade: o QR Code identifica dados, mas não garante pareamento. Câmeras com RTSP podem ser reproduzidas se a autenticação e o protocolo forem compatíveis.",13f))
 }
 private fun field(hint:String, value:String="", secret:Boolean=false):EditText = EditText(this).apply { this.hint=hint; setText(value); inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT }
 private fun addDialog(existing:JSONObject?=null, index:Int=-1, scanned:String="") {
  val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,4,28,4) }
  val name=field("Nome da câmera",existing?.optString("name")?:"Minha câmera")
  val host=field("IP local (ex: 192.168.1.10)",existing?.optString("host")?:"")
  val path=field("Caminho RTSP (ex: /onvif1)",existing?.optString("path")?:"/onvif1")
  val port=field("Porta RTSP",existing?.optString("port")?:"554")
  val user=field("Usuário RTSP (se exigido)",existing?.optString("user")?:"")
  val pass=field("Senha RTSP", "",true)
  val qr=field("Código/ID da câmera",existing?.optString("qr")?:scanned)
  listOf(name,host,path,port,user,pass,qr).forEach { form.addView(it) }
  val dialog=AlertDialog.Builder(this).setTitle(if(index<0)"Adicionar câmera" else "Editar câmera").setView(ScrollView(this).apply { addView(form) })
   .setNegativeButton("Cancelar",null).setPositiveButton("Salvar",null).create()
  dialog.setOnShowListener {
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
    val h=host.text.toString().trim(); val p=port.text.toString().toIntOrNull()
    if(h.isEmpty() || !Regex("^[a-zA-Z0-9.-]+$").matches(h) || p==null || p !in 1..65535) { Toast.makeText(this,"Informe IP/host e porta válidos",Toast.LENGTH_LONG).show(); return@setOnClickListener }
    val data=JSONObject().put("name",name.text.toString()).put("host",h).put("path",path.text.toString()).put("port",p).put("user",user.text.toString()).put("qr",qr.text.toString())
    // Senhas não são persistidas: solicitar ao reproduzir.
    val arr=cameras; if(index>=0) arr.put(index,data) else arr.put(data)
    prefs.edit().putString("list",arr.toString()).apply(); dialog.dismiss(); home()
   }
  }; dialog.show()
 }
 private fun cameraScreen(i:Int) {
  val c=cameras.getJSONObject(i); page(c.optString("name"))
  root.addView(label("${c.optString("host")}:${c.optInt("port")}${c.optString("path")}",14f))
  root.addView(button("▶ Abrir imagem ao vivo") { requestPlayback(c) })
  root.addView(button("Editar configuração") { addDialog(c,i) })
  root.addView(button("Excluir câmera") { AlertDialog.Builder(this).setMessage("Excluir esta câmera?").setNegativeButton("Não",null).setPositiveButton("Excluir") { _,_ -> val old=cameras; val updated=JSONArray(); for(j in 0 until old.length()) if(j!=i) updated.put(old.getJSONObject(j)); prefs.edit().putString("list",updated.toString()).apply(); home() }.show() })
  root.addView(button("Voltar") { home() })
 }
 private fun requestPlayback(c:JSONObject) {
  val password=field("Senha RTSP (não será salva)","",true)
  AlertDialog.Builder(this).setTitle("Autenticação RTSP").setView(password).setNegativeButton("Cancelar",null).setPositiveButton("Conectar") { _,_ -> play(c,password.text.toString()) }.show()
 }
 private fun play(c: JSONObject, password: String) {
  player?.release()
  player = null
  page("Ao vivo: ${c.optString("name")}")
  val video = PlayerView(this).apply {
   layoutParams = LinearLayout.LayoutParams(-1, 650)
  }
  root.addView(video)
  val status = label("Preparando conexão RTSP...", 14f)
  root.addView(status)
  root.addView(button("Fechar transmissão") { home() })

  val host = c.optString("host").trim()
  val port = c.optInt("port", 554)
  val path = c.optString("path", "/onvif1").let {
   if (it.startsWith("/")) it else "/$it"
  }
  val username = c.optString("user").trim()
  if (host.isBlank() || port !in 1..65535) {
   status.text = "Endereço ou porta RTSP inválidos."
   return
  }
  // Uri.Builder codifica caracteres especiais da senha sem expô-la na tela.
  val uriBuilder = Uri.Builder().scheme("rtsp")
   .encodedAuthority("$host:$port")
   .encodedPath(path)
  val streamUri = if (username.isNotEmpty()) {
   uriBuilder.encodedAuthority(
    "${Uri.encode(username)}:${Uri.encode(password)}@$host:$port"
   ).build()
  } else {
   uriBuilder.build()
  }
  try {
   // TCP evita falhas comuns de transporte RTP/UDP em redes domésticas.
   val mediaSource = RtspMediaSource.Factory()
    .setForceUseRtpTcp(true)
    .createMediaSource(MediaItem.fromUri(streamUri))
   val exoPlayer = ExoPlayer.Builder(this).build()
   player = exoPlayer
   video.player = exoPlayer
   exoPlayer.addListener(object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
     status.text = "Não foi possível abrir a imagem (${error.errorCodeName}). " +
      "Verifique usuário e senha NVR, porta RTSP e compatibilidade da câmera."
     android.util.Log.e("MinhaCentralRTSP", "Falha de reprodução", error)
    }
    override fun onPlaybackStateChanged(state: Int) {
     if (state == Player.STATE_READY) status.text = "Transmissão conectada."
    }
   })
   exoPlayer.setMediaSource(mediaSource)
   exoPlayer.prepare()
   exoPlayer.playWhenReady = true
  } catch (error: Exception) {
   android.util.Log.e("MinhaCentralRTSP", "Falha ao iniciar reprodução", error)
   status.text = "Erro ao iniciar o vídeo: ${error.javaClass.simpleName}"
   player?.release()
   player = null
  }
 }
 private fun discover() {
  page("Busca ONVIF")
  val output=label("Procurando câmeras na rede local...",14f); root.addView(output)
  root.addView(button("Voltar") { home() })
  Thread {
   val found=mutableSetOf<String>()
   try {
    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    val multicast = wifi.createMulticastLock("onvif-discovery"); multicast.setReferenceCounted(false); multicast.acquire()
    try { DatagramSocket().use { sock ->
     sock.soTimeout=1600
     val probe="""<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe/></e:Body></e:Envelope>"""
     val bytes=probe.toByteArray(); sock.send(DatagramPacket(bytes,bytes.size,InetAddress.getByName("239.255.255.250"),3702))
     repeat(5) {
      try { val buffer=ByteArray(8192); val packet=DatagramPacket(buffer,buffer.size); sock.receive(packet); found.add(packet.address.hostAddress?:"") } catch(_:Exception) { }
     }
    }
    } finally { multicast.release() }
   } catch(_:Exception) { }
   runOnUiThread {
    output.text=if(found.isEmpty()) "Nenhuma resposta ONVIF encontrada. Confira se o celular está no Wi-Fi da câmera e se ONVIF está ativado." else "Dispositivos encontrados: ${found.size}"
    found.forEach { ip -> root.addView(button("Adicionar $ip") { val preset=JSONObject().put("name","Câmera $ip").put("host",ip).put("port",554).put("path","/onvif1"); addDialog(preset) }) }
   }
  }.start()
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  val result=IntentIntegrator.parseActivityResult(requestCode,resultCode,data)
  if(result!=null && result.contents!=null) {
   val raw=result.contents
   val parsed=try { Uri.parse(raw) } catch(_:Exception) { null }
   val candidate=if(parsed?.scheme=="rtsp") parsed.host else null
   val preset=JSONObject().put("name","Câmera QR").put("host",candidate?:"").put("port",if((parsed?.port ?: -1) > 0) parsed!!.port else 554).put("path",if(parsed?.scheme=="rtsp") parsed.path?:"/" else "/onvif1").put("qr",raw)
   addDialog(preset)
  }
 }
 override fun onDestroy() { player?.release(); super.onDestroy() }
}
