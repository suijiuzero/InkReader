package com.inkreader.tts

import android.content.Context
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.UUID

/** Only numeric private IPv4 addresses are accepted; redirects and Internet endpoints are forbidden. */
object VoiceAddress {
    fun valid(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0') }) return false
        val bytes = parts.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 } || bytes.last() == 255) return false
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) || bytes[0] == 127
    }
}

data class ComputerVoice(val host: String, val id: String, val key: String,
                         val languages: Set<String> = setOf("zh-CN", "en-US"),
                         val models: Set<String> = setOf("auto"))

class ComputerVoiceClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("computer_voice", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    val paired: Boolean get() = prefs.getString("key", "").orEmpty().isNotEmpty()
    var model: ComputerVoiceModel
        get() = ComputerVoiceModel.fromStored(prefs.getString("model", null))
        set(value) { prefs.edit().putString("model", value.wireName).apply() }

    fun forget() {
        val savedModel = model
        prefs.edit().clear().putString("model", savedModel.wireName).apply()
    }

    /** Blocking LAN operations: call on Dispatchers.IO. No text is sent during discovery or pairing. */
    fun pair(code: String, manualHost: String = ""): ComputerVoice {
        val key = code.replace(" ", "").trim().uppercase(java.util.Locale.ROOT)
        require(key.matches(Regex("[A-F0-9]{16}"))) { "请输入电脑页面上的 16 位配对码" }
        val hosts = if (manualHost.isNotBlank()) {
            require(VoiceAddress.valid(manualHost.trim())) { "请输入电脑的局域网 IPv4 地址" }
            listOf(manualHost.trim() to "")
        } else discover(null)
        for ((host, id) in hosts) {
            val result = runCatching { health(host, key, id) }.getOrNull() ?: continue
            prefs.edit().putString("host", result.host).putString("id", result.id)
                .putString("key", key).putBoolean("enabled", true).apply()
            return result
        }
        error("未连接到电脑，请检查服务、配对码和同一 Wi-Fi；也可填写电脑 IP")
    }

    fun connect(): ComputerVoice? {
        if (!enabled) return null
        val key = prefs.getString("key", "").orEmpty()
        val id = prefs.getString("id", "").orEmpty()
        if (key.isEmpty() || id.isEmpty()) return null
        val savedHost = prefs.getString("host", "").orEmpty()
        runCatching { health(savedHost, key, id) }.getOrNull()?.let { return it }
        for ((host, foundId) in discover(id)) {
            val result = runCatching { health(host, key, foundId) }.getOrNull() ?: continue
            prefs.edit().putString("host", host).apply()
            return result
        }
        return null
    }

    private fun health(host: String, key: String, expectedId: String): ComputerVoice {
        val connection = connection(host, "/v1/health", key)
        try {
            connection.readTimeout = 1500
            check(connection.responseCode == 200)
            val body = JSONObject(connection.inputStream.use { it.readNBytesCompat(4096).toString(Charsets.UTF_8) })
            check(body.getString("service") == MARKER)
            val id = body.getString("id")
            check(id.matches(Regex("[a-f0-9]{32}")) && (expectedId.isEmpty() || expectedId == id))
            val languages = body.optJSONArray("languages")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.toSet()
            } ?: setOf("zh-CN", "en-US")
            val models = body.optJSONArray("models")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.toSet()
            } ?: setOf("auto")
            return ComputerVoice(host, id, key, languages, models)
        } finally { connection.disconnect() }
    }

    fun synthesize(voice: ComputerVoice, text: String, rate: Float, pitch: Float,
                   language: String = SpeechLanguage.sentence(text),
                   model: ComputerVoiceModel = this.model): ByteArray {
        require(text.isNotBlank() && text.length <= 1200)
        require(language in setOf("zh-CN", "en-US", "ja-JP"))
        check(model.wireName in voice.models) { "电脑尚未提供${model.title}模型，请检查音色或更新电脑语音服务" }
        check(model != ComputerVoiceModel.AUTO || language != "ja-JP" || "ja-JP" in voice.languages) { "电脑还没有日语音色，请更新电脑服务并安装七海日语音色" }
        val connection = connection(voice.host, "/v1/speak", voice.key)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.readTimeout = 12000
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("text", text).put("rate", rate.coerceIn(.5f, 2.5f))
                .put("pitch", pitch.coerceIn(.5f, 2f)).put("language", language)
                .put("model", model.wireName).toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode == 200) { "电脑语音暂不可用" }
            val audio = connection.inputStream.use { it.readNBytesCompat(12_000_000) }
            check(audio.size > 44 && audio.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                audio.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()))
            return audio
        } finally { connection.disconnect() }
    }

    private fun connection(host: String, path: String, key: String): HttpURLConnection {
        require(VoiceAddress.valid(host))
        return (URL("http://$host:37262$path").openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 1200
            readTimeout = 1500
            setRequestProperty("Authorization", "Bearer $key")
        }
    }

    private fun discover(expectedId: String?): List<Pair<String, String>> {
        val found = linkedMapOf<String, String>()
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val payload = JSONObject().put("service", MARKER).put("nonce", nonce).toString().toByteArray()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val addresses = mutableSetOf("255.255.255.255")
                NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
                    network.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast?.hostAddress }
                }
                addresses.forEach { host -> runCatching { socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), 37261)) } }
                val deadline = android.os.SystemClock.elapsedRealtime() + 1600
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    runCatching {
                        val packet = DatagramPacket(ByteArray(2048), 2048)
                        socket.receive(packet)
                        val host = packet.address.hostAddress ?: return@runCatching
                        val body = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        val id = body.getString("id")
                        if (VoiceAddress.valid(host) && body.optString("service") == MARKER && body.optString("nonce") == nonce &&
                            body.optInt("port") == 37262 && id.matches(Regex("[a-f0-9]{32}")) && (expectedId == null || id == expectedId)) found[host] = id
                    }
                }
            }
        }
        return found.toList()
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.size() + count <= limit) { "电脑响应过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object { private const val MARKER = "INKREADER_VOICE_V1" }
}
