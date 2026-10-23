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
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("cameras", MODE_PRIVATE) }
    private val cameras: JSONArray get() = try { JSONArray(prefs.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
    private var engine: LibVLC? = null
    private var playback: MediaPlayer? = null
    private var videoSurface: SurfaceView? = null
    private var session = 0
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(180)}\n" +
                    error.stackTrace.take(12).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                prefs.edit().putString("last_crash", report).commit()
            } catch (_: Exception) { }
            if (old != null) old.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
        home()
    }

    private fun label(value: String, size: Float = 17f) = TextView(this).apply {
        text = value; textSize = size; setPadding(8, 12, 8, 12)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { action() }
    }
    private fun field(hintText: String, value: String = "", secret: Boolean = false) = EditText(this).apply {
        hint = hintText; setText(value)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
    }
    private fun page(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 22, 20, 20) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(label(title, 24f))
    }
    private fun stopPlayback() {
        session++
        playing = false
        val p = playback; playback = null
        try { p?.stop() } catch (_: Exception) { }
        try { p?.vlcVout?.detachViews() } catch (_: Exception) { }
        try { p?.release() } catch (_: Exception) { }
        val vlc = engine; engine = null
        try { vlc?.release() } catch (_: Exception) { }
        videoSurface = null
    }
    private fun home() {
        stopPlayback()
        page("Minha Central de Câmeras")
        root.addView(label("Versão 1.4.0 • Motor LibVLC", 14f))
        root.addView(button("+ Adicionar câmera") { editCamera() })
        root.addView(button("Ler QR Code") {
            IntentIntegrator(this).setPrompt("Leia o QR Code da câmera").setBeepEnabled(false).initiateScan()
        })
        root.addView(button("Buscar ONVIF no Wi-Fi") { discover() })
        root.addView(button("Configurar Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        val list = cameras
        root.addView(label("Minhas câmeras: ${list.length()}", 20f))
        val indexes = (0 until list.length()).sortedWith(compareByDescending<Int> {
            list.optJSONObject(it)?.optBoolean("favorite", false) == true
        }.thenBy { list.optJSONObject(it)?.optString("name", "")?.lowercase() ?: "" })
        for (i in indexes) {
            val c = list.optJSONObject(i) ?: continue
            root.addView(button("${if (c.optBoolean("favorite")) "★" else "☆"} ${c.optString("name")} • ${c.optString("host")}") { cameraScreen(i) })
        }
        root.addView(button("Diagnóstico") { diagnostics() })
        root.addView(label("A senha RTSP/NVR é solicitada ao conectar e não é salva.", 13f))
    }
    private fun editCamera(existing: JSONObject? = null, index: Int = -1, scanned: String = "") {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 4, 24, 4) }
        val name = field("Nome", existing?.optString("name") ?: "Minha câmera")
        val host = field("IP local (ex.: 192.168.1.10)", existing?.optString("host") ?: "")
        val port = field("Porta RTSP", existing?.optString("port") ?: "554")
        val path = field("Caminho RTSP", existing?.optString("path") ?: "/onvif1")
        val user = field("Usuário RTSP", existing?.optString("user") ?: "")
        val qr = field("Código/ID", existing?.optString("qr") ?: scanned)
        listOf(name, host, port, path, user, qr).forEach { form.addView(it) }
        val dialog = AlertDialog.Builder(this).setTitle(if (index < 0) "Adicionar câmera" else "Editar câmera")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim()
                val p = port.text.toString().trim().toIntOrNull()
                if (!Regex("^[a-zA-Z0-9.-]+$").matches(h) || p == null || p !in 1..65535) {
                    Toast.makeText(this, "IP ou porta inválidos", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val data = JSONObject().put("name", name.text.toString().trim()).put("host", h)
                    .put("port", p).put("path", path.text.toString().trim())
                    .put("user", user.text.toString().trim()).put("qr", qr.text.toString().trim())
                    .put("favorite", existing?.optBoolean("favorite", false) ?: false)
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
        root.addView(button(if (c.optBoolean("favorite")) "★ Remover favorito" else "☆ Adicionar favorito") {
            val list = cameras
            val item = list.optJSONObject(index) ?: return@button
            item.put("favorite", !item.optBoolean("favorite"))
            prefs.edit().putString("list", list.toString()).apply()
            cameraScreen(index)
        })
        root.addView(button("▶ Abrir imagem ao vivo") { requestPlayback(c) })
        root.addView(button("Editar configuração") { editCamera(c, index) })
        root.addView(button("Excluir câmera") {
            AlertDialog.Builder(this).setMessage("Excluir esta câmera?").setNegativeButton("Não", null)
                .setPositiveButton("Excluir") { _, _ ->
                    val all = cameras; val updated = JSONArray()
                    for (j in 0 until all.length()) if (j != index) all.optJSONObject(j)?.let { updated.put(it) }
                    prefs.edit().putString("list", updated.toString()).apply(); home()
                }.show()
        })
        root.addView(button("Voltar") { home() })
    }
    private fun requestPlayback(c: JSONObject) {
        val password = field("Senha RTSP/NVR", secret = true)
        val form = LinearLayout(this).apply { setPadding(24, 8, 24, 8); addView(password) }
        AlertDialog.Builder(this).setTitle("Conectar à câmera").setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar") { _, _ -> play(c, password.text.toString()) }.show()
    }
    private fun play(c: JSONObject, password: String) {
        stopPlayback()
        page("Ao vivo: ${c.optString("name")}")
        val surface = SurfaceView(this)
        surface.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650)
        videoSurface = surface
        root.addView(surface)
        val status = label("Conectando via LibVLC...", 14f)
        root.addView(status)
        root.addView(button("Fechar transmissão") { home() })
        val host = c.optString("host").trim()
        val port = c.optInt("port", 554)
        val path = c.optString("path", "/onvif1").let { if (it.startsWith("/")) it else "/$it" }
        val username = c.optString("user").trim()
        if (host.isEmpty() || port !in 1..65535) { status.text = "IP ou porta inválidos"; return }
        val authority = if (username.isNotEmpty()) "${Uri.encode(username)}:${Uri.encode(password)}@$host:$port" else "$host:$port"
        val address = Uri.Builder().scheme("rtsp").encodedAuthority(authority).encodedPath(Uri.encode(path, "/")).build()
        val token = session
        try {
            val vlc = LibVLC(this, arrayListOf("--rtsp-tcp", "--network-caching=800", "--no-audio-time-stretch"))
            engine = vlc
            val p = MediaPlayer(vlc)
            playback = p
            p.vlcVout.setVideoView(surface)
            p.vlcVout.attachViews()
            p.setEventListener { event ->
                if (token != session) return@setEventListener
                when (event.type) {
                    MediaPlayer.Event.Playing -> runOnUiThread { if (token == session) { playing = true; status.text = "Transmissão conectada" } }
                    MediaPlayer.Event.Buffering -> runOnUiThread { if (token == session && !playing) status.text = "Carregando imagem..." }
                    MediaPlayer.Event.EncounteredError -> runOnUiThread { if (token == session) status.text = "Falha de reprodução RTSP. Verifique credenciais NVR e compatibilidade da câmera." }
                    MediaPlayer.Event.EndReached -> runOnUiThread { if (token == session) status.text = "Transmissão encerrada." }
                }
            }
            val media = Media(vlc, address)
            media.addOption(":rtsp-tcp")
            media.addOption(":network-caching=800")
            p.media = media
            media.release()
            if (!p.play()) status.text = "Não foi possível iniciar o reprodutor."
        } catch (e: Exception) {
            status.text = "Erro ao iniciar LibVLC: ${e.javaClass.simpleName}"
            stopPlayback()
        }
    }
    private fun diagnostics() {
        stopPlayback(); page("Diagnóstico")
        root.addView(label("Versão 1.4.0 • LibVLC • Android ${android.os.Build.VERSION.RELEASE}"))
        root.addView(label("Câmeras cadastradas: ${cameras.length()}"))
        val crash = prefs.getString("last_crash", null)
        root.addView(label(if (crash.isNullOrBlank()) "Nenhum travamento registrado." else "Último erro: $crash", 14f))
        root.addView(button("Copiar diagnóstico") {
            val message = "Central V5 | Android ${android.os.Build.VERSION.RELEASE} | ${crash ?: "sem erro"}"
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico", message))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        })
        root.addView(button("Voltar") { home() })
    }
    private fun discover() {
        stopPlayback(); page("Busca ONVIF")
        val currentRoot = root
        val output = label("Procurando câmeras na rede Wi-Fi...")
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
            } catch (_: Exception) { }
            runOnUiThread {
                if (isFinishing || isDestroyed || root !== currentRoot) return@runOnUiThread
                output.text = if (found.isEmpty()) "Nenhuma câmera ONVIF encontrada." else "Encontradas: ${found.size}"
                found.forEach { ip -> currentRoot.addView(button("Adicionar $ip") {
                    editCamera(JSONObject().put("name", "Câmera $ip").put("host", ip).put("port", 554).put("path", "/onvif1"))
                }) }
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
            editCamera(JSONObject().put("name", "Câmera QR")
                .put("host", if (isRtsp) parsed?.host.orEmpty() else "")
                .put("port", if (isRtsp && (parsed?.port ?: -1) > 0) parsed!!.port else 554)
                .put("path", if (isRtsp) parsed?.path ?: "/onvif1" else "/onvif1")
                .put("qr", raw))
        }
    }
    override fun onStop() { stopPlayback(); super.onStop() }
    override fun onDestroy() { stopPlayback(); super.onDestroy() }
}
