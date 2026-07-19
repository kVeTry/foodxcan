package com.xito.foodxcan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Product(
    val barcode: String,
    val name: String,
    val brand: String,
    val imageUrl: String?,
    val quantity: String,
    val nutriScore: String?,      // a..e
    val novaGroup: Int?,          // 1..4
    val category: String?,        // primera categoría OFF (tag)
    val categoryName: String?,
    val additives: List<AdditiveInfo>,
    val score: Int,               // 0..100
    val positives: List<String>,
    val negatives: List<String>,
    val estimatedPrice: String?,
    val kcal100: Double?, val sugar100: Double?, val salt100: Double?,
    val satFat100: Double?, val protein100: Double?, val fiber100: Double?
)

data class Alternative(val name: String, val brand: String, val imageUrl: String?, val nutriScore: String?, val barcode: String)

object Repo {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun get(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Foodxcan/1.0 (Android)").build())
            .execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    } catch (e: Exception) { null }

    suspend fun fetchProduct(barcode: String): Product? = withContext(Dispatchers.IO) {
        // Se prueban varios servidores/endpoints: muchos productos españoles están en 'es' o en el endpoint v0
        val urls = listOf(
            "https://es.openfoodfacts.org/api/v2/product/$barcode.json",
            "https://world.openfoodfacts.org/api/v2/product/$barcode.json",
            "https://world.openfoodfacts.org/api/v0/product/$barcode.json"
        )
        var json: JSONObject? = null
        for (u in urls) {
            val r = get(u)
            if (r != null && r.optInt("status") == 1 && r.has("product")) { json = r; break }
        }
        if (json == null) return@withContext null
        val p = json.getJSONObject("product")

        val name = p.optString("product_name_es").ifBlank { p.optString("product_name") }.ifBlank { "Producto sin nombre" }
        val brand = p.optString("brands").split(",").firstOrNull()?.trim().orEmpty()
        val img = p.optString("image_front_url").ifBlank { p.optString("image_url") }.ifBlank { null }
        val nutri = p.optString("nutriscore_grade").lowercase().takeIf { it in listOf("a","b","c","d","e") }
        val nova = p.optInt("nova_group", -1).takeIf { it in 1..4 }
        val catTags = p.optJSONArray("categories_tags")
        val catTag = if (catTags != null && catTags.length() > 0) catTags.getString(catTags.length() - 1) else null
        val catName = p.optString("categories").split(",").lastOrNull()?.trim()

        val additiveTags = p.optJSONArray("additives_tags")
        val additives = buildList {
            if (additiveTags != null) for (i in 0 until additiveTags.length()) {
                val t = additiveTags.getString(i)
                add(Additives.find(t) ?: Additives.generic(t))
            }
        }

        val n = p.optJSONObject("nutriments") ?: JSONObject()
        fun d(k: String) = if (n.has(k)) n.optDouble(k).takeIf { !it.isNaN() } else null
        val kcal = d("energy-kcal_100g"); val sugar = d("sugars_100g"); val salt = d("salt_100g")
        val sat = d("saturated-fat_100g"); val prot = d("proteins_100g"); val fib = d("fiber_100g")

        // ---- SCORE 0-100 ----
        var score = when (nutri) { "a" -> 90; "b" -> 75; "c" -> 55; "d" -> 35; "e" -> 18; else -> 50 }
        when (nova) { 4 -> score -= 12; 3 -> score -= 4; 1 -> score += 5 }
        additives.forEach { score -= it.risk.weight }
        if (fib != null && fib >= 3) score += 4
        if (prot != null && prot >= 8) score += 3
        if (sugar != null && sugar > 22) score -= 5
        if (salt != null && salt > 1.5) score -= 5
        score = score.coerceIn(0, 100)

        // ---- Positivos / negativos ----
        val pos = mutableListOf<String>(); val neg = mutableListOf<String>()
        when (nutri) {
            "a", "b" -> pos.add("Buen perfil nutricional (Nutri-Score ${nutri.uppercase()})")
            "d", "e" -> neg.add("Perfil nutricional pobre (Nutri-Score ${nutri.uppercase()})")
        }
        when (nova) {
            1 -> pos.add("Alimento sin procesar o mínimamente procesado")
            4 -> neg.add("Producto ultraprocesado (NOVA 4)")
        }
        if (additives.isEmpty()) pos.add("Sin aditivos") else {
            val bad = additives.count { it.risk.weight >= 10 }
            if (bad > 0) neg.add("$bad aditivo(s) con riesgo moderado o alto")
            else pos.add("Aditivos de bajo riesgo")
        }
        sugar?.let { if (it > 22) neg.add("Muy azucarado (${fmt(it)} g/100g)") else if (it < 5) pos.add("Bajo en azúcares (${fmt(it)} g/100g)") else {} }
        salt?.let { if (it > 1.5) neg.add("Alto en sal (${fmt(it)} g/100g)") else if (it < 0.3) pos.add("Bajo en sal (${fmt(it)} g/100g)") else {} }
        sat?.let { if (it > 5) neg.add("Alto en grasas saturadas (${fmt(it)} g/100g)") else if (it < 1.5) pos.add("Bajo en grasas saturadas") else {} }
        fib?.let { if (it >= 3) pos.add("Buena fuente de fibra (${fmt(it)} g/100g)") }
        prot?.let { if (it >= 8) pos.add("Rico en proteínas (${fmt(it)} g/100g)") }
        kcal?.let { if (it > 450) neg.add("Muy calórico (${fmt(it)} kcal/100g)") }

        Product(barcode, name, brand, img, p.optString("quantity"), nutri, nova, catTag, catName,
            additives, score, pos, neg, estimatePrice(catTag, p.optString("categories")),
            kcal, sugar, salt, sat, prot, fib)
    }

    suspend fun fetchAlternatives(product: Product): List<Alternative> = withContext(Dispatchers.IO) {
        val cat = product.category ?: return@withContext emptyList()
        val myRank = nutriRank(product.nutriScore)
        val fields = "code,product_name,product_name_es,brands,image_front_url,nutriscore_grade"
        // Primero con productos de España; si no hay, se busca a nivel mundial
        val urls = listOf(
            "https://world.openfoodfacts.org/api/v2/search?categories_tags=$cat&countries_tags=en:spain&sort_by=nutriscore_score&fields=$fields&page_size=25",
            "https://world.openfoodfacts.org/api/v2/search?categories_tags=$cat&sort_by=nutriscore_score&fields=$fields&page_size=25"
        )
        val result = mutableListOf<Alternative>()
        for (url in urls) {
            val json = get(url) ?: continue
            val arr = json.optJSONArray("products") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("code")
                if (code == product.barcode || result.any { it.barcode == code }) continue
                val ns = o.optString("nutriscore_grade").lowercase().takeIf { it in listOf("a","b","c","d","e") } ?: continue
                // Solo alternativas con mejor Nutri-Score que el producto escaneado
                if (nutriRank(ns) >= myRank) continue
                val nm = o.optString("product_name_es").ifBlank { o.optString("product_name") }
                if (nm.isBlank()) continue
                result.add(Alternative(nm, o.optString("brands").split(",").firstOrNull()?.trim().orEmpty(),
                    o.optString("image_front_url").ifBlank { null }, ns, code))
                if (result.size >= 4) break
            }
            if (result.isNotEmpty()) break
        }
        result
    }

    private fun nutriRank(g: String?) = when (g?.lowercase()) { "a" -> 1; "b" -> 2; "c" -> 3; "d" -> 4; "e" -> 5; else -> 3 }

    // Precio medio orientativo por tipo de producto (España, estimación)
    private fun estimatePrice(catTag: String?, cats: String): String? {
        val c = (catTag.orEmpty() + " " + cats).lowercase()
        val eur = when {
            "chocolate" in c -> "1,50 – 3,50 €"
            "yogur" in c || "yogurt" in c -> "0,30 – 1,20 € (unidad)"
            "galleta" in c || "biscuit" in c || "cookie" in c -> "1,00 – 3,00 €"
            "refresco" in c || "soda" in c || "beverages" in c && "carbonated" in c -> "0,80 – 2,00 €"
            "cereal" in c -> "1,50 – 4,00 €"
            "pan " in c || "bread" in c -> "0,80 – 2,50 €"
            "leche" in c || "milk" in c -> "0,80 – 1,60 € (litro)"
            "queso" in c || "cheese" in c -> "1,50 – 5,00 €"
            "pizza" in c -> "2,00 – 5,00 €"
            "embutido" in c || "jamón" in c || "sausage" in c || "ham" in c -> "1,50 – 6,00 €"
            "pasta" in c -> "0,80 – 2,50 €"
            "arroz" in c || "rice" in c -> "1,00 – 3,00 €"
            "zumo" in c || "juice" in c -> "1,00 – 2,50 €"
            "snack" in c || "patatas fritas" in c || "chips" in c || "crisps" in c -> "1,00 – 2,50 €"
            "agua" in c || "water" in c -> "0,30 – 1,00 €"
            "café" in c || "coffee" in c -> "2,50 – 6,00 €"
            "helado" in c || "ice cream" in c -> "2,00 – 5,00 €"
            "salsa" in c || "sauce" in c -> "1,00 – 3,00 €"
            "conserva" in c || "canned" in c -> "1,00 – 3,50 €"
            else -> null
        }
        return eur
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)
}
