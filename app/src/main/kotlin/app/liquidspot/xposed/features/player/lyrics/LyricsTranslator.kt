package app.liquidspot.xposed.features.player.lyrics

import android.os.Handler
import android.os.Looper
import app.liquidspot.xposed.util.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class LyricsTranslator(cacheRoot: File) {
    private val dir = File(cacheRoot, "liquidspot/translations")
    private val memory = object : LinkedHashMap<String, Map<String, String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 12
    }
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LiquidSpot-Translate").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun targetLanguage(): String {
        val locale = Locale.getDefault()
        return when (locale.language) {
            "zh" -> if (locale.country in setOf("TW", "HK", "MO")) "zh-TW" else "zh-CN"
            "in" -> "id"
            else -> locale.language.ifEmpty { "en" }
        }
    }

    fun translate(trackKey: String, texts: List<String>, callback: (Map<String, String>?) -> Unit) {
        val target = targetLanguage()
        val key = "$trackKey|$target|${texts.size}"
        synchronized(memory) { memory[key] }?.let {
            callback(it)
            return
        }
        executor.execute {
            val result = runCatching { readDisk(key) ?: fetch(texts, target).also { writeDisk(key, it) } }
                .onFailure { Logger.w("Tradução: falha ao traduzir a letra", it) }
                .getOrNull()
            if (result != null) synchronized(memory) { memory[key] = result }
            main.post { callback(result) }
        }
    }

    private fun fetch(texts: List<String>, target: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (batch in batches(texts)) {
            val translated = request(batch.joinToString("\n"), target)?.split('\n')?.map { it.trim() }
            if (translated != null && translated.size == batch.size) {
                batch.forEachIndexed { i, text -> out[text] = translated[i] }
            } else {
                for (text in batch) request(text, target)?.trim()?.let { out[text] = it }
            }
        }
        return out
    }

    private fun batches(texts: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var size = 0
        for (text in texts) {
            val cost = URLEncoder.encode(text, "UTF-8").length + 3
            if (current.isNotEmpty() && size + cost > MAX_QUERY) {
                out += current
                current = ArrayList()
                size = 0
            }
            current += text
            size += cost
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun request(text: String, target: String): String? {
        val url = ENDPOINT + "&tl=" + URLEncoder.encode(target, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) return null
            val json = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            val parts = json.optJSONArray(0) ?: return null
            return buildString {
                for (i in 0 until parts.length()) append(parts.optJSONArray(i)?.optString(0).orEmpty())
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun readDisk(key: String): Map<String, String>? = runCatching {
        val file = fileFor(key)
        if (!file.isFile) return null
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrNull()

    private fun writeDisk(key: String, map: Map<String, String>) {
        if (map.isEmpty()) return
        runCatching {
            dir.mkdirs()
            fileFor(key).writeText(JSONObject(map).toString())
        }
    }

    private companion object {
        const val ENDPOINT = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t"
        const val MAX_QUERY = 3_500
    }
}
