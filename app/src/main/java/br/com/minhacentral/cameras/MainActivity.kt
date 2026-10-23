package br.com.minhacentral.cameras

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
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
 private var videoView: PlayerView? = null
 private val prefs by lazy { getSharedPreferences("cameras", MODE_PRIVATE) }
 private val cameras: JSONArray get() = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
 private var playbackToken = 0

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  // Captura o erro real se o Android encerrar o app, inclusive falhas fora da tela de vídeo.
  val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
  Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
   try {
    val detail = "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty().take(250)}\n" +
      throwable.stackTrace.take(10).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    prefs.edit().putString("last_crash", detail.take(3500)).commit()
   } catch (_: Exception) { }
   if (previousHandler != null) previousHandler.uncaughtException(thread, throwable)
   else android.os.Process.killProcess(android.os.Process.myPid())
  }
  home()
 }

 private fun label(value: String, size: Float = 17f): TextView = TextView(this).apply {
  text = value; textSize = size; setPadding(8, 12, 8, 12)
 }
 private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
  text = value; setOnClickListener { action() }
 }
 private fun field(hintText: String, value: String = "", secret: Boolean = false): EditText = EditText(this).apply {
  hint = hintText; setText(value)
  inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
 }
 private fun page(title: String) {
  root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 22, 20, 20) }
  val scroll = ScrollView(this).apply { addView(root) }
  setContentView(scroll)
  root.addView(label(title, 24f))
 }
 private fun stopPlayback() {
  playbackToken++
  videoView?.player = null
  videoView = null
  val old = player
  player = null
  try { old?.stop(); old?.release() } catch (e: Exception) { Log.e("CentralRTSP", "Release failed", e) }
 }
 private fun home() {
  stopPlayback()
  page("Minha Central de Câmeras")
  root.addView(label("Câmeras IP • ONVIF • RTSP | versão 1.3.0", 14f))
  val crash = prefs.getString("last_crash", null)
  if (!crash.isNullOrBlank()) {
   root.addView(button("⚠ Ver relatório da última falha") {
    AlertDialog.Builder(this).setTitle("Relatório de falha")
     .setMessage(crash)
     .setPositiveButton("Copiar") { _, _ ->
      val clip = android.content.ClipData.newPlainText("Erro Minha Central", crash)
      (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
      Toast.makeText(this, "Relatório copiado", Toast.LENGTH_SHORT).show()
     }
     .setNeutralButton("Limpar") { _, _ -> prefs.edit().remove("last_crash").apply(); home() }
     .setNegativeButton("Fechar", null).show()
   })
  }
  root.addView(button("+ Adicionar câmera") { editCamera() })
  root.addView(button("Ler QR Code") {
   IntentIntegrator(this).setPrompt("Aponte para o QR Code da câmera").setBeepEnabled(false).initiateScan()
  })
  root.addView(button("Buscar ONVIF no Wi-Fi") { discover() })
  root.addView(button("Configurar Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
  root.addView(label("Painel de câmeras", 20f))
  val list = cameras
  root.addView(label("${list.length()} câmera(s) cadastrada(s) • Favoritos primeiro", 14f))
  val indexes = (0 until list.length()).sortedWith(compareByDescending<Int> {
   list.optJSONObject(it)?.optBoolean("favorite", false) == true
  }.thenBy { list.optJSONObject(it)?.optString("name", "")?.lowercase() ?: "" })
  for (i in indexes) {
   val camera = list.optJSONObject(i) ?: continue
   val star = if (camera.optBoolean("favorite", false)) "★" else "☆"
   root.addView(button("$star  ${camera.optString("name")} • ${camera.optString("host")}") { cameraScreen(i) })
  }
  root.addView(button("Diagnóstico e informações") { diagnostics() })
  root.addView(label("Use as credenciais RTSP/NVR da câmera. A senha não é armazenada neste aplicativo.", 13f))
 }
 private fun editCamera(existing: JSONObject? = null, index: Int = -1, scanned: String = "") {
  val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 4, 28, 4) }
  val name = field("Nome", existing?.optString("name") ?: "Minha câmera")
  val host = field("IP local (ex.: 192.168.1.10)", existing?.optString("host") ?: "")
  val path = field("Caminho RTSP", existing?.optString("path") ?: "/onvif1")
  val port = field("Porta RTSP", existing?.optString("port") ?: "554")
  val user = field("Usuário RTSP (se exigido)", existing?.optString("user") ?: "")
  val qr = field("Código/ID", existing?.optString("qr") ?: scanned)
  listOf(name, host, path, port, user, qr).forEach { form.addView(it) }
  val dialog = AlertDialog.Builder(this).setTitle(if (index < 0) "Adicionar câmera" else "Editar câmera")
   .setView(ScrollView(this).apply { addView(form) })
   .setNegativeButton("Cancelar", null).setPositiveButton("Salvar", null).create()
  dialog.setOnShowListener {
   dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
    val h = host.text.toString().trim()
    val p = port.text.toString().trim().toIntOrNull()
    if (!Regex("^[a-zA-Z0-9.-]+$").matches(h) || p == null || p !in 1..65535) {
     Toast.makeText(this, "Confira IP e porta", Toast.LENGTH_LONG).show(); return@setOnClickListener
    }
    val data = JSONObject().put("favorite", existing?.optBoolean("favorite", false) ?: false).put("name", name.text.toString().trim()).put("host", h)
     .put("port", p).put("path", path.text.toString().trim())
     .put("user", user.text.toString().trim()).put("qr", qr.text.toString().trim())
    val list = cameras
    if (index >= 0 && index < list.length()) list.put(index, data) else list.put(data)
    prefs.edit().putString("list", list.toString()).apply()
    dialog.dismiss(); home()
   }
  }
  dialog.show()
 }
 private fun cameraScreen(index: Int) {
  stopPlayback()
  val c = cameras.optJSONObject(index) ?: return home()
  page(c.optString("name", "Câmera"))
  root.addView(label("${c.optString("host")}:${c.optInt("port", 554)}${c.optString("path")}", 14f))
  root.addView(button(if (c.optBoolean("favorite", false)) "★ Remover dos favoritos" else "☆ Adicionar aos favoritos") {
   val arr = cameras
   val selected = arr.optJSONObject(index) ?: return@button
   selected.put("favorite", !selected.optBoolean("favorite", false))
   prefs.edit().putString("list", arr.toString()).apply()
   cameraScreen(index)
  })
  root.addView(button("▶ Abrir imagem ao vivo") { requestPlayback(c) })
  root.addView(button("Editar configuração") { editCamera(c, index) })
  root.addView(button("Excluir câmera") {
   AlertDialog.Builder(this).setMessage("Excluir esta câmera?")
    .setNegativeButton("Não", null).setPositiveButton("Excluir") { _, _ ->
     val result = JSONArray(); val all = cameras
     for (j in 0 until all.length()) if (j != index) all.optJSONObject(j)?.let { result.put(it) }
     prefs.edit().putString("list", result.toString()).apply(); home()
    }.show()
  })
  root.addView(button("Voltar") { home() })
 }
 private fun diagnostics() {
  stopPlayback()
  page("Diagnóstico da central")
  root.addView(label("Versão: 1.3.0 | Android: ${android.os.Build.VERSION.RELEASE}", 15f))
  root.addView(label("Câmeras cadastradas: ${cameras.length()}", 15f))
  root.addView(label("Wi-Fi local necessário para RTSP direto. Acesso remoto exige VPN ou serviço compatível.", 14f))
  val crash = prefs.getString("last_crash", null)
  root.addView(label(if (crash.isNullOrBlank()) "Nenhum relatório de falha salvo." else "Existe um relatório de falha salvo.", 14f))
  root.addView(button("Copiar diagnóstico (sem senhas)") {
   val message = "Minha Central 1.3.0 | Android ${android.os.Build.VERSION.RELEASE} | Câmeras ${cameras.length()} | Erro: ${crash ?: "nenhum"}"
   val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
   clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico", message))
   Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show()
  })
  root.addView(button("Voltar") { home() })
 }
 private fun requestPlayback(c: JSONObject) {
  val password = field("Senha RTSP / NVR", secret = true)
  val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 4, 24, 4); addView(password) }
  AlertDialog.Builder(this).setTitle("Autenticação RTSP").setView(container)
   .setNegativeButton("Cancelar", null)
   .setPositiveButton("Conectar") { _, _ -> play(c, password.text.toString()) }.show()
 }
 private fun play(c: JSONObject, password: String) {
  stopPlayback()
  page("Ao vivo: ${c.optString("name")}")
  val status = label("Preparando vídeo RTSP...", 14f)
  // Renderização por SurfaceView padrão, com altura fixa e botão de retorno.
  try {
   val view = PlayerView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 650); useController = true }
   videoView = view
   root.addView(view)
  } catch (e: Exception) {
   status.text = "Falha ao criar tela de vídeo: ${e.javaClass.simpleName}"
  }
  root.addView(status)
  root.addView(button("Fechar transmissão") { home() })
  val view = videoView ?: return
  val host = c.optString("host").trim()
  val port = c.optInt("port", 554)
  val path = c.optString("path", "/onvif1").let { if (it.startsWith("/")) it else "/$it" }
  val username = c.optString("user").trim()
  if (host.isBlank() || port !in 1..65535) { status.text = "IP ou porta inválidos"; return }
  // Credenciais são codificadas, sem expor a senha em logs ou na interface.
  val authority = if (username.isNotEmpty()) "${Uri.encode(username)}:${Uri.encode(password)}@$host:$port" else "$host:$port"
  val address = Uri.Builder().scheme("rtsp").encodedAuthority(authority).path(path).build()
  val token = playbackToken
  try {
   val source = RtspMediaSource.Factory().setForceUseRtpTcp(true)
    .createMediaSource(MediaItem.fromUri(address))
   val exo = ExoPlayer.Builder(applicationContext).build()
   player = exo
   view.player = exo
   exo.addListener(object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
     if (token != playbackToken) return
     Log.e("CentralRTSP", "RTSP failed code=${error.errorCodeName}; type=${error.cause?.javaClass?.simpleName}")
     status.text = "Falha RTSP: ${error.errorCodeName}. Confira autenticação NVR e suporte RTSP."
    }
    override fun onPlaybackStateChanged(state: Int) {
     if (token != playbackToken) return
     when (state) {
      Player.STATE_READY -> status.text = "Transmissão conectada"
      Player.STATE_BUFFERING -> status.text = "Carregando imagem..."
     }
    }
   })
   exo.setMediaSource(source)
   exo.prepare()
   exo.playWhenReady = true
  } catch (e: Exception) {
   Log.e("CentralRTSP", "Failed to start RTSP: ${e.javaClass.simpleName}")
   status.text = "Erro ao iniciar RTSP: ${e.javaClass.simpleName}"
   stopPlayback()
  }
 }
 private fun discover() {
  stopPlayback()
  page("Busca ONVIF")
  val screenRoot = root
  val output = label("Buscando dispositivos na rede Wi-Fi...", 14f)
  root.addView(output)
  root.addView(button("Voltar") { home() })
  Thread {
   val found = mutableSetOf<String>()
   try {
    val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("central-onvif").apply { setReferenceCounted(false) }
    lock.acquire()
    try {
     DatagramSocket().use { socket ->
      socket.soTimeout = 1200
      val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe/></e:Body></e:Envelope>"""
      val bytes = probe.toByteArray()
      socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 3702))
      repeat(4) {
       try {
        val buffer = ByteArray(8192)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        packet.address.hostAddress?.let { found.add(it) }
       } catch (_: Exception) { }
      }
     }
    } finally { lock.release() }
   } catch (e: Exception) { Log.w("CentralONVIF", "Discovery failed: ${e.javaClass.simpleName}") }
   runOnUiThread {
    if (isFinishing || isDestroyed || root !== screenRoot) return@runOnUiThread
    output.text = if (found.isEmpty()) "Nenhuma câmera ONVIF respondeu. Verifique o Wi-Fi e ONVIF." else "Encontradas: ${found.size}"
    found.forEach { ip ->
     screenRoot.addView(button("Adicionar $ip") {
      editCamera(JSONObject().put("name", "Câmera $ip").put("host", ip).put("port", 554).put("path", "/onvif1"))
     })
    }
   }
  }.start()
 }
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
  if (result?.contents != null) {
   val raw = result.contents
   val parsed = try { Uri.parse(raw) } catch (_: Exception) { null }
   val isRtsp = parsed?.scheme.equals("rtsp", ignoreCase = true)
   val preset = JSONObject().put("name", "Câmera QR")
    .put("host", if (isRtsp) parsed?.host.orEmpty() else "")
    .put("port", if (isRtsp && (parsed?.port ?: -1) > 0) parsed!!.port else 554)
    .put("path", if (isRtsp) parsed?.path ?: "/onvif1" else "/onvif1")
    .put("qr", raw)
   editCamera(preset)
  }
 }
 override fun onStop() { stopPlayback(); super.onStop() }
 override fun onDestroy() { stopPlayback(); super.onDestroy() }
}
