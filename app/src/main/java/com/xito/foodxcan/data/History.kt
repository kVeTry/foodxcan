package com.xito.foodxcan.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val barcode: String, val name: String, val brand: String,
    val imageUrl: String?, val score: Int, val time: Long
)

object History {
    private const val PREFS = "foodxcan_prefs"
    private const val KEY = "history"
    private const val MAX = 50

    fun add(ctx: Context, p: Product) {
        val list = load(ctx).toMutableList()
        list.removeAll { it.barcode == p.barcode }   // sin duplicados, el más reciente arriba
        list.add(0, HistoryItem(p.barcode, p.name, p.brand, p.imageUrl, p.score, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx, list)
    }

    fun load(ctx: Context): List<HistoryItem> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(HistoryItem(
                        o.getString("barcode"), o.getString("name"), o.optString("brand"),
                        o.optString("imageUrl").ifBlank { null }, o.getInt("score"), o.getLong("time")
                    ))
                }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    private fun save(ctx: Context, list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("barcode", it.barcode); put("name", it.name); put("brand", it.brand)
                put("imageUrl", it.imageUrl ?: ""); put("score", it.score); put("time", it.time)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    // Preferencia de tema
    fun isDark(ctx: Context) = prefs(ctx).getBoolean("dark", false)
    fun setDark(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("dark", v).apply()

    // API key de Anthropic para el análisis con IA
    fun getApiKey(ctx: Context) = prefs(ctx).getString("api_key", "") ?: ""
    fun setApiKey(ctx: Context, v: String) = prefs(ctx).edit().putString("api_key", v).apply()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
