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

    private const val API = "https://api.github.com/repos/kVeTry/foodxcan/releases/latest"
    const val RELEASES = "https://github.com/kVeTry/foodxcan/releases"

    /** Compara versiones tipo 1.2.3 ignorando la v inicial. */
    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split(".", "-")
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
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
