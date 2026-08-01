package com.xito.foodxcan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val notes: String, val url: String, val apkUrl: String?)

object Updates {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private const val API = "https://api.github.com/repos/Xito-Development/foodxcan/releases/latest"
    const val RELEASES = "https://github.com/Xito-Development/foodxcan/releases"

    /** Normaliza "v1.2.0" o "V1.2" a la lista [1,2,0]. */
    private fun parts(v: String): List<Int> =
        v.trim().trimStart('v', 'V').trim()
            .split(".", "-", "_", " ")
            .mapNotNull { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() }

    /** Compara versiones tipo 1.2.3 ignorando la v inicial y los ceros finales. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = parts(remote); val l = parts(local)
        // Sin numeros legibles en alguno de los dos: no se avisa
        if (r.isEmpty() || l.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false   // iguales (1.2 y 1.2.0 se consideran la misma)
    }

    /** Consulta la ultima release publicada. Devuelve null si ya esta al dia. */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Foodxcan-Android").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = JSONObject(r.body!!.string())
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) return@withContext null
                val tag = o.optString("tag_name").ifBlank { return@withContext null }
                android.util.Log.d("Foodxcan", "Version instalada: $currentVersion / publicada: $tag")
                if (!isNewer(tag, currentVersion)) return@withContext null

                val assets = o.optJSONArray("assets")
                var apk: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        apk = a.optString("browser_download_url"); break
                    }
                }
                UpdateInfo(
                    version = tag,
                    notes = o.optString("body").take(600),
                    url = o.optString("html_url").ifBlank { RELEASES },
                    apkUrl = apk
                )
            }
        } catch (e: Exception) { null }
    }
}
