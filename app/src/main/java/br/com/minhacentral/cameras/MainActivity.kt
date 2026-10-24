package br.com.minhacentral.cameras

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("cameras", MODE_PRIVATE) }
    private val cameras get() = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
    private var engine: LibVLC? = null
    private var playback: MediaPlayer? = null
    private var session = 0
    private var scanGeneration = 0
    private data class Found(val ip: String, val ports: MutableSet<Int> = mutableSetOf(), var onvif: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { prefs.edit().putString("last_crash", "${error.javaClass.simpleName}: ${error.message}\n" + error.stackTrace.take(12).joinToString("\n")).commit() } catch (_: Exception) { }
            if (previous != null) previous.uncaughtException(thread, error) else android.os.Process.killProcess(android.os.Process.myPid())
        }
        home()
    }
    private fun label(s: String, size: Float = 17f) = TextView(this).apply { text = s; textSize = size; setPadding(8, 12, 8, 12) }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }
    private fun field(hintText: String, value: String = "", secret: Boolean = false) = EditText(this).apply {
        hint = hintText; setText(value)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
    }
    private fun page(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 20) }
        setContentView(ScrollView(this).apply { addView(root) }); root.addView(label(title, 24f))
    }
    private fun stopPlayback() {
        session++
        val p = playback; playback = null
        try { p?.stop() } catch (_: Exception) { }
        try { p?.vlcVout?.detachViews() } catch (_: Exception) { }
        try { p?.release() } catch (_: Exception) { }
        val vlc = engine; engine = null
        try { vlc?.release() } catch (_: Exception) { }
    }
    private fun home() {
        scanGeneration++; stopPlayback(); page("Minha Central de Câmeras")
        root.addView(label("V6 • Busca real ONVIF e RTSP • LibVLC", 14f))
        root.addView(button("🔎 Buscar câmeras no Wi-Fi") { discover() })
        root.addView(button("+ Adicionar manualmente") { editCamera() })
        root.addView(button("Ler QR Code") { IntentIntegrator(this).setPrompt("Leia o QR Code").setBeepEnabled(false).initiateScan() })
        root.addView(button("Configurar Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        val list = cameras
        root.addView(label("Minhas câmeras: ${list.length()}", 20f))
        val indexes = (0 until list.length()).sortedWith(compareByDescending<Int> { list.optJSONObject(it)?.optBoolean("favorite") == true }.thenBy { list.optJSONObject(it)?.optString("name") ?: "" })
        for (i in indexes) {
            val c = list.optJSONObject(i) ?: continue
            root.addView(button("${if (c.optBoolean("favorite")) "★" else "☆"} ${c.optString("name")} • ${c.optString("host")}") { cameraScreen(i) })
        }
        root.addView(button("Diagnóstico do aplicativo") { diagnostics() })
    }
    private fun editCamera(existing: JSONObject? = null, index: Int = -1) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 4, 24, 4) }
        val name = field("Nome", existing?.optString("name") ?: "Minha câmera")
        val host = field("IP local", existing?.optString("host") ?: "")
        val port = field("Porta RTSP", existing?.optString("port") ?: "554")
        val path = field("Caminho RTSP", existing?.optString("path") ?: "/onvif1")
        val user = field("Usuário RTSP (se houver)", existing?.optString("user") ?: "")
        val qr = field("ID/QR", existing?.optString("qr") ?: "")
        listOf(name, host, port, path, user, qr).forEach { form.addView(it) }
        val dialog = AlertDialog.Builder(this).setTitle("Configurar câmera").setView(ScrollView(this).apply { addView(form) }).setNegativeButton("Cancelar", null).setPositiveButton("Salvar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim(); val p = port.text.toString().toIntOrNull()
                if (!Regex("^[a-zA-Z0-9.-]+$").matches(h) || p == null || p !in 1..65535) { Toast.makeText(this, "IP ou porta inválidos", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val obj = JSONObject().put("name", name.text.toString()).put("host", h).put("port", p).put("path", path.text.toString()).put("user", user.text.toString()).put("qr", qr.text.toString()).put("favorite", existing?.optBoolean("favorite") ?: false)
                val all = cameras; if (index in 0 until all.length()) all.put(index, obj) else all.put(obj)
                prefs.edit().putString("list", all.toString()).apply(); dialog.dismiss(); home()
            }
        }
        dialog.show()
    }
    private fun cameraScreen(i: Int) {
        stopPlayback(); val c = cameras.optJSONObject(i) ?: return home()
        page(c.optString("name")); root.addView(label("${c.optString("host")}:${c.optInt("port",554)}${c.optString("path")}", 14f))
        root.addView(button("▶ Abrir imagem ao vivo") { requestPlayback(c) })
        root.addView(button("Testar conexão RTSP (sem senha)") { testRtsp(c.optString("host"), c.optInt("port",554)) })
        root.addView(button(if (c.optBoolean("favorite")) "★ Remover favorito" else "☆ Favoritar") {
            val all = cameras; all.optJSONObject(i)?.put("favorite", !c.optBoolean("favorite")); prefs.edit().putString("list", all.toString()).apply(); cameraScreen(i)
        })
        root.addView(button("Editar") { editCamera(c, i) })
        root.addView(button("Excluir") { AlertDialog.Builder(this).setMessage("Excluir câmera?").setNegativeButton("Não", null).setPositiveButton("Excluir") { _, _ ->
            val all = cameras; val out = JSONArray(); for (j in 0 until all.length()) if (j != i) out.put(all.optJSONObject(j))
            prefs.edit().putString("list", out.toString()).apply(); home()
        }.show() })
        root.addView(button("Voltar") { home() })
    }
    private fun requestPlayback(c: JSONObject) {
        val pass = field("Senha NVR/RTSP", secret = true)
        AlertDialog.Builder(this).setTitle("Senha da câmera").setView(LinearLayout(this).apply { setPadding(24, 8, 24, 8); addView(pass) })
            .setNegativeButton("Cancelar", null).setPositiveButton("Conectar") { _, _ -> play(c, pass.text.toString()) }.show()
    }
    private fun play(c: JSONObject, password: String) {
        stopPlayback(); page("Ao vivo: ${c.optString("name")}")
        val surface = SurfaceView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650) }
        root.addView(surface); val status = label("Conectando via LibVLC...", 14f); root.addView(status)
        root.addView(button("Fechar transmissão") { home() })
        val host = c.optString("host"); val port = c.optInt("port",554)
        val path = c.optString("path", "/onvif1").let { if (it.startsWith("/")) it else "/$it" }
        val user = c.optString("user")
        val auth = if (user.isNotBlank()) "${Uri.encode(user)}:${Uri.encode(password)}@" else ""
        val address = Uri.parse("rtsp://$auth$host:$port${Uri.encode(path, "/")}")
        val token = session
        try {
            val vlc = LibVLC(this, arrayListOf("--rtsp-tcp", "--network-caching=800")); engine = vlc
            val p = MediaPlayer(vlc); playback = p
            p.vlcVout.setVideoView(surface); p.vlcVout.attachViews()
            p.setEventListener { e ->
                if (token == session) when (e.type) {
                    MediaPlayer.Event.Playing -> runOnUiThread { if (token == session) status.text = "Conectado • aguardando imagem" }
                    MediaPlayer.Event.EncounteredError -> runOnUiThread { if (token == session) status.text = "Falha RTSP: consulte Testar conexão para diagnóstico. A senha NVR pode ser necessária." }
                    MediaPlayer.Event.EndReached -> runOnUiThread { if (token == session) status.text = "Transmissão encerrada" }
                }
            }
            val media = Media(vlc, address); media.addOption(":rtsp-tcp"); media.addOption(":network-caching=800")
            p.media = media; media.release(); p.play()
        } catch (e: Exception) { status.text = "Falha ao iniciar: ${e.javaClass.simpleName}"; stopPlayback() }
    }
    private fun diagnostics() {
        stopPlayback(); page("Diagnóstico V6")
        root.addView(label("Android ${android.os.Build.VERSION.RELEASE} • LibVLC • ${cameras.length()} câmeras"))
        root.addView(label(prefs.getString("last_crash", "Nenhum travamento registrado.") ?: "Nenhum travamento", 14f))
        root.addView(button("Voltar") { home() })
    }
    private fun testRtsp(ip: String, port: Int) {
        stopPlayback(); page("Teste RTSP: $ip:$port")
        val status = label("Conectando...", 15f); root.addView(status); root.addView(button("Voltar") { home() })
        val currentRoot = root
        Thread {
            val result = rtspOptions(ip, port)
            runOnUiThread { if (!isFinishing && !isDestroyed && root === currentRoot) status.text = result }
        }.start()
    }
    private fun rtspOptions(ip: String, port: Int): String = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), 1800); socket.soTimeout = 2000
            val request = "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: MinhaCentralV6\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
            val bytes = ByteArray(2048); val n = socket.getInputStream().read(bytes)
            val first = if (n > 0) String(bytes, 0, n, Charsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty() else "Sem resposta"
            when {
                "200" in first -> "$first\nServiço RTSP respondeu. Isso não confirma senha nem imagem."
                "401" in first -> "$first\nCâmera exige autenticação RTSP."
                "404" in first -> "$first\nServiço respondeu; caminho pode ser inválido."
                else -> "$first\nResposta do servidor; autenticação/vídeo não verificados."
            }
        }
    } catch (e: Exception) { "Falha de conexão TCP: ${e.javaClass.simpleName}. Verifique Wi-Fi, IP e porta." }

    private fun localSubnet(): String? = try {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ip = wifi.connectionInfo.ipAddress
        if (ip == 0) null else "${ip and 255}.${ip shr 8 and 255}.${ip shr 16 and 255}."
    } catch (_: Exception) { null }
    private fun discover() {
        stopPlayback(); scanGeneration++; val scanId = scanGeneration
        page("Buscar câmeras na rede")
        val currentRoot = root
        val status = label("Procurando ONVIF e portas RTSP na rede Wi-Fi...", 15f); root.addView(status)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(results)
        root.addView(button("Cancelar busca / Voltar") { home() })
        Thread {
            val found = linkedMapOf<String, Found>()
            val lock = Any()
            val subnet = localSubnet()
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            var multicast: WifiManager.MulticastLock? = null
            try {
                multicast = wifi.createMulticastLock("central-onvif-v6").apply { setReferenceCounted(false); acquire() }
                DatagramSocket().use { sock ->
                    sock.soTimeout = 800
                    val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe/></e:Body></e:Envelope>"""
                    val bytes = probe.toByteArray()
                    repeat(2) { sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("239.255.255.250"), 3702)) }
                    repeat(5) {
                        if (scanId != scanGeneration) return@repeat
                        try {
                            val buffer = ByteArray(8192); val packet = DatagramPacket(buffer, buffer.size)
                            sock.receive(packet); val ip = packet.address.hostAddress ?: return@repeat
                            synchronized(lock) { found.getOrPut(ip) { Found(ip) }.onvif = true }
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { } finally { try { multicast?.release() } catch (_: Exception) { } }
            if (subnet != null && scanId == scanGeneration) {
                val pool = Executors.newFixedThreadPool(24)
                for (last in 1..254) {
                    if (scanId != scanGeneration) break
                    pool.execute {
                        val ip = "$subnet$last"
                        for (port in intArrayOf(554, 8554, 10554, 5000, 80)) {
                            if (scanId != scanGeneration) break
                            try {
                                Socket().use { s -> s.connect(InetSocketAddress(ip, port), 280) }
                                synchronized(lock) { found.getOrPut(ip) { Found(ip) }.ports.add(port) }
                            } catch (_: Exception) { }
                        }
                    }
                }
                pool.shutdown(); pool.awaitTermination(35, TimeUnit.SECONDS); pool.shutdownNow()
            }
            val list = synchronized(lock) { found.values.map { Found(it.ip, it.ports.toMutableSet(), it.onvif) }.sortedBy { it.ip.split('.').lastOrNull()?.toIntOrNull() ?: 0 } }
            runOnUiThread {
                if (isFinishing || isDestroyed || root !== currentRoot || scanId != scanGeneration) return@runOnUiThread
                status.text = if (subnet == null) "Wi-Fi IPv4 indisponível. Busca ONVIF realizada; conecte-se à rede local para varredura de portas." else "Busca concluída: ${list.size} dispositivos com respostas. Portas abertas não garantem que sejam câmeras."
                if (list.isEmpty()) results.addView(label("Nenhum dispositivo identificado. Confirme que o celular e as câmeras estão no mesmo Wi-Fi e sem isolamento de clientes."))
                for (f in list) {
                    val rtsp = listOf(554, 8554, 10554).firstOrNull { it in f.ports }
                    val details = "${f.ip} • ${if (f.onvif) "ONVIF detectado" else "ONVIF não confirmado"} • portas: ${f.ports.sorted().joinToString().ifBlank { "nenhuma testada aberta" }}"
                    results.addView(label(details, 15f))
                    results.addView(button("Adicionar ${f.ip}") {
                        editCamera(JSONObject().put("name", "Câmera ${f.ip}").put("host", f.ip).put("port", rtsp ?: 554).put("path", "/onvif1"))
                    })
                    if (rtsp != null) results.addView(button("Testar RTSP ${f.ip}:$rtsp") { testRtsp(f.ip, rtsp) })
                }
            }
        }.start()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            val raw = result.contents; val parsed = Uri.parse(raw); val rtsp = parsed.scheme.equals("rtsp", true)
            editCamera(JSONObject().put("name", "Câmera QR").put("host", if (rtsp) parsed.host.orEmpty() else "")
                .put("port", if (rtsp && parsed.port > 0) parsed.port else 554).put("path", if (rtsp) parsed.path ?: "/onvif1" else "/onvif1").put("qr", raw))
        }
    }
    override fun onStop() { stopPlayback(); super.onStop() }
    override fun onDestroy() { scanGeneration++; stopPlayback(); super.onDestroy() }
}
