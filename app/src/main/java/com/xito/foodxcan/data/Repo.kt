package com.xito.foodxcan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class Source(val label: String, val host: String) {
    FOOD("Alimentacion", "world.openfoodfacts.org"),
    FOOD_ES("Alimentacion", "es.openfoodfacts.org"),
    BEAUTY("Cosmetica", "world.openbeautyfacts.org"),
    PET("Mascotas", "world.openpetfoodfacts.org"),
    OTHER("Otros productos", "world.openproductsfacts.org")
}

data class Nutrient(val name: String, val value: Double, val unit: String, val level: String?)

data class Product(
    val barcode: String,
    val name: String,
    val brand: String,
    val imageUrl: String?,
    val quantity: String,
    val source: Source,
    val nutriScore: String?,
    val novaGroup: Int?,
    val ecoScore: String?,
    val category: String?,
    val categoryTags: List<String>,
    val categoryName: String?,
    val ingredientsText: String?,
    val allergens: List<String>,
    val labels: List<String>,
    val additives: List<AdditiveInfo>,
    val score: Int,
    val positives: List<String>,
    val negatives: List<String>,
    val nutrients: List<Nutrient>,
    val servingSize: String?,
    val estimatedPrice: String?,
    val kcal100: Double?, val sugar100: Double?, val salt100: Double?,
    val satFat100: Double?, val protein100: Double?, val fiber100: Double?
)

data class Alternative(val name: String, val brand: String, val imageUrl: String?, val nutriScore: String?, val barcode: String)

object Repo {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    private fun get(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Foodxcan/2.0 (Android)").build())
            .execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    } catch (e: Exception) { null }

    suspend fun fetchProduct(barcode: String): Product? = withContext(Dispatchers.IO) {
        // Se recorren las bases de datos del proyecto Open Food Facts:
        // alimentacion, cosmetica, comida de mascotas y otros productos
        val candidates = listOf(
            Source.FOOD_ES to "https://es.openfoodfacts.org/api/v2/product/$barcode.json",
            Source.FOOD to "https://world.openfoodfacts.org/api/v2/product/$barcode.json",
            Source.FOOD to "https://world.openfoodfacts.org/api/v0/product/$barcode.json",
            Source.BEAUTY to "https://world.openbeautyfacts.org/api/v2/product/$barcode.json",
            Source.PET to "https://world.openpetfoodfacts.org/api/v2/product/$barcode.json",
            Source.OTHER to "https://world.openproductsfacts.org/api/v2/product/$barcode.json"
        )
        var json: JSONObject? = null
        var src = Source.FOOD
        for ((s, u) in candidates) {
            val r = get(u)
            if (r != null && r.optInt("status") == 1 && r.has("product")) {
                val prod = r.getJSONObject("product")
                val hasName = prod.optString("product_name_es").isNotBlank() || prod.optString("product_name").isNotBlank()
                if (hasName) { json = r; src = if (s == Source.FOOD_ES) Source.FOOD else s; break }
            }
        }
        if (json == null) return@withContext null
        val p = json.getJSONObject("product")

        val name = p.optString("product_name_es").ifBlank { p.optString("product_name") }.ifBlank { "Producto sin nombre" }
        val brand = p.optString("brands").split(",").firstOrNull()?.trim().orEmpty()
        val img = p.optString("image_front_url").ifBlank { p.optString("image_url") }.ifBlank { null }
        val nutri = p.optString("nutriscore_grade").lowercase().takeIf { it in listOf("a","b","c","d","e") }
        val nova = p.optInt("nova_group", -1).takeIf { it in 1..4 }
        val eco = p.optString("ecoscore_grade").lowercase().takeIf { it in listOf("a","b","c","d","e") }

        val catTags = p.optJSONArray("categories_tags")
        val catList = buildList { if (catTags != null) for (i in 0 until catTags.length()) add(catTags.getString(i)) }
        val catTag = catList.lastOrNull()
        val catName = p.optString("categories").split(",").lastOrNull()?.trim()?.ifBlank { null }

        val ingText = p.optString("ingredients_text_es").ifBlank { p.optString("ingredients_text") }.ifBlank { null }
        val servingSize = p.optString("serving_size").ifBlank { null }

        fun tagList(key: String, strip: Boolean = true): List<String> {
            val arr = p.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val raw = arr.getString(i)
                    val clean = if (strip) raw.substringAfter(":").replace("-", " ")
                        .replaceFirstChar { it.uppercase() } else raw
                    if (clean.isNotBlank()) add(clean)
                }
            }
        }
        val allergens = tagList("allergens_tags")
        val labels = tagList("labels_tags").take(8)

        val additiveTags = p.optJSONArray("additives_tags")
        val additives = buildList {
            if (additiveTags != null) for (i in 0 until additiveTags.length()) {
                val t = additiveTags.getString(i)
                add(Additives.find(t) ?: Additives.generic(t))
            }
        }

        val n = p.optJSONObject("nutriments") ?: JSONObject()
        fun d(k: String) = if (n.has(k)) n.optDouble(k).takeIf { !it.isNaN() } else null
        val levels = p.optJSONObject("nutrient_levels")
        fun lvl(k: String) = levels?.optString(k)?.ifBlank { null }?.let {
            when (it) { "low" -> "Bajo"; "moderate" -> "Medio"; "high" -> "Alto"; else -> null }
        }

        val kcal = d("energy-kcal_100g"); val sugar = d("sugars_100g"); val salt = d("salt_100g")
        val sat = d("saturated-fat_100g"); val prot = d("proteins_100g"); val fib = d("fiber_100g")
        val fat = d("fat_100g"); val carbs = d("carbohydrates_100g"); val sodium = d("sodium_100g")

        val nutrients = buildList {
            kcal?.let { add(Nutrient("Energia", it, "kcal", null)) }
            fat?.let { add(Nutrient("Grasas", it, "g", lvl("fat"))) }
            sat?.let { add(Nutrient("de las cuales saturadas", it, "g", lvl("saturated-fat"))) }
            carbs?.let { add(Nutrient("Hidratos de carbono", it, "g", null)) }
            sugar?.let { add(Nutrient("de los cuales azucares", it, "g", lvl("sugars"))) }
            fib?.let { add(Nutrient("Fibra", it, "g", null)) }
            prot?.let { add(Nutrient("Proteinas", it, "g", null)) }
            salt?.let { add(Nutrient("Sal", it, "g", lvl("salt"))) }
            sodium?.let { add(Nutrient("Sodio", it, "g", null)) }
        }

        // ---- SCORE 0-100 ----
        var score = when (nutri) { "a" -> 90; "b" -> 75; "c" -> 55; "d" -> 35; "e" -> 18; else -> 50 }
        when (nova) { 4 -> score -= 12; 3 -> score -= 4; 1 -> score += 5 }
        additives.forEach { score -= it.risk.weight }
        if (fib != null && fib >= 3) score += 4
        if (prot != null && prot >= 8) score += 3
        if (sugar != null && sugar > 22) score -= 5
        if (salt != null && salt > 1.5) score -= 5
        score = score.coerceIn(0, 100)

        // ---- Positivos y negativos (todos los detectables) ----
        val pos = mutableListOf<String>(); val neg = mutableListOf<String>()
        when (nutri) {
            "a" -> pos.add("Excelente perfil nutricional (Nutri-Score A)")
            "b" -> pos.add("Buen perfil nutricional (Nutri-Score B)")
            "c" -> neg.add("Perfil nutricional medio (Nutri-Score C)")
            "d" -> neg.add("Perfil nutricional pobre (Nutri-Score D)")
            "e" -> neg.add("Perfil nutricional muy pobre (Nutri-Score E)")
        }
        when (nova) {
            1 -> pos.add("Alimento sin procesar o minimamente procesado")
            2 -> pos.add("Ingrediente culinario poco procesado")
            3 -> neg.add("Alimento procesado (NOVA 3)")
            4 -> neg.add("Producto ultraprocesado (NOVA 4)")
        }
        when (eco) {
            "a", "b" -> pos.add("Buen impacto medioambiental (Eco-Score ${eco.uppercase()})")
            "d", "e" -> neg.add("Impacto medioambiental elevado (Eco-Score ${eco.uppercase()})")
        }
        if (additives.isEmpty()) pos.add("Sin aditivos") else {
            val altos = additives.filter { it.risk == Risk.ALTO }
            val mods = additives.filter { it.risk == Risk.MODERADO }
            altos.forEach { neg.add("Contiene ${it.code} ${it.name}: riesgo alto") }
            mods.forEach { neg.add("Contiene ${it.code} ${it.name}: riesgo moderado") }
            if (altos.isEmpty() && mods.isEmpty()) pos.add("Todos los aditivos son de bajo riesgo")
        }
        sugar?.let {
            if (it > 22) neg.add("Muy azucarado (${fmt(it)} g por 100 g)")
            else if (it > 10) neg.add("Contenido de azucar apreciable (${fmt(it)} g por 100 g)")
            else if (it < 5) pos.add("Bajo en azucares (${fmt(it)} g por 100 g)")
            else {}
        }
        salt?.let {
            if (it > 1.5) neg.add("Alto en sal (${fmt(it)} g por 100 g)")
            else if (it > 0.9) neg.add("Sal moderadamente alta (${fmt(it)} g por 100 g)")
            else if (it < 0.3) pos.add("Bajo en sal (${fmt(it)} g por 100 g)")
            else {}
        }
        sat?.let {
            if (it > 5) neg.add("Alto en grasas saturadas (${fmt(it)} g por 100 g)")
            else if (it < 1.5) pos.add("Bajo en grasas saturadas (${fmt(it)} g por 100 g)")
            else {}
        }
        fat?.let { if (it > 20) neg.add("Alto contenido de grasas (${fmt(it)} g por 100 g)") }
        fib?.let {
            if (it >= 6) pos.add("Muy rico en fibra (${fmt(it)} g por 100 g)")
            else if (it >= 3) pos.add("Buena fuente de fibra (${fmt(it)} g por 100 g)")
            else {}
        }
        prot?.let {
            if (it >= 12) pos.add("Muy rico en proteinas (${fmt(it)} g por 100 g)")
            else if (it >= 8) pos.add("Buena fuente de proteinas (${fmt(it)} g por 100 g)")
            else {}
        }
        kcal?.let {
            if (it > 450) neg.add("Muy calorico (${fmt(it)} kcal por 100 g)")
            else if (it < 100) pos.add("Bajo en calorias (${fmt(it)} kcal por 100 g)")
            else {}
        }
        if (allergens.isNotEmpty()) neg.add("Contiene alergenos: ${allergens.joinToString(", ")}")
        labels.forEach { l ->
            val low = l.lowercase()
            if (low.contains("bio") || low.contains("organic") || low.contains("ecolog")) pos.add("Producto ecologico certificado")
            if (low.contains("vegan")) pos.add("Apto para veganos")
            if (low.contains("sin gluten") || low.contains("gluten free")) pos.add("Sin gluten")
            if (low.contains("fairtrade") || low.contains("comercio justo")) pos.add("Comercio justo")
        }

        Product(barcode, name, brand, img, p.optString("quantity"), src, nutri, nova, eco,
            catTag, catList, catName, ingText, allergens, labels, additives, score,
            pos.distinct(), neg.distinct(), nutrients, servingSize,
            estimatePrice(catTag, p.optString("categories")),
            kcal, sugar, salt, sat, prot, fib)
    }

    suspend fun fetchAlternatives(product: Product): List<Alternative> = withContext(Dispatchers.IO) {
        val myRank = nutriRank(product.nutriScore)
        val fields = "code,product_name,product_name_es,brands,image_front_url,nutriscore_grade"
        val cats = product.categoryTags.reversed().take(3).ifEmpty { listOfNotNull(product.category) }
        if (cats.isEmpty()) return@withContext emptyList()
        val host = if (product.source == Source.FOOD) "world.openfoodfacts.org" else product.source.host
        val result = mutableListOf<Alternative>()
        for (cat in cats) {
            val url = "https://$host/api/v2/search?categories_tags=$cat&sort_by=nutriscore_score&fields=$fields&page_size=30"
            val json = get(url) ?: continue
            val arr = json.optJSONArray("products") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val code = o.optString("code")
                if (code == product.barcode || result.any { it.barcode == code }) continue
                val ns = o.optString("nutriscore_grade").lowercase().takeIf { it in listOf("a","b","c","d","e") } ?: continue
                if (nutriRank(ns) >= myRank) continue
                val nm = o.optString("product_name_es").ifBlank { o.optString("product_name") }
                if (nm.isBlank()) continue
                result.add(Alternative(nm, o.optString("brands").split(",").firstOrNull()?.trim().orEmpty(),
                    o.optString("image_front_url").ifBlank { null }, ns, code))
                if (result.size >= 4) break
            }
            if (result.size >= 4) break
        }
        result
    }

    private fun nutriRank(g: String?) = when (g?.lowercase()) { "a" -> 1; "b" -> 2; "c" -> 3; "d" -> 4; "e" -> 5; else -> 3 }

    private fun estimatePrice(catTag: String?, cats: String): String? {
        val c = (catTag.orEmpty() + " " + cats).lowercase()
        return when {
            "chocolate" in c -> "1,50 - 3,50 EUR"
            "yogur" in c || "yogurt" in c -> "0,30 - 1,20 EUR"
            "galleta" in c || "biscuit" in c || "cookie" in c -> "1,00 - 3,00 EUR"
            "refresco" in c || "soda" in c -> "0,80 - 2,00 EUR"
            "cereal" in c -> "1,50 - 4,00 EUR"
            "pan" in c || "bread" in c -> "0,80 - 2,50 EUR"
            "leche" in c || "milk" in c -> "0,80 - 1,60 EUR"
            "queso" in c || "cheese" in c -> "1,50 - 5,00 EUR"
            "pizza" in c -> "2,00 - 5,00 EUR"
            "embutido" in c || "jamon" in c || "sausage" in c || "ham" in c -> "1,50 - 6,00 EUR"
            "pasta" in c -> "0,80 - 2,50 EUR"
            "arroz" in c || "rice" in c -> "1,00 - 3,00 EUR"
            "zumo" in c || "juice" in c -> "1,00 - 2,50 EUR"
            "snack" in c || "chips" in c || "crisps" in c -> "1,00 - 2,50 EUR"
            "agua" in c || "water" in c -> "0,30 - 1,00 EUR"
            "cafe" in c || "coffee" in c -> "2,50 - 6,00 EUR"
            "helado" in c || "ice cream" in c -> "2,00 - 5,00 EUR"
            "salsa" in c || "sauce" in c -> "1,00 - 3,00 EUR"
            "conserva" in c || "canned" in c -> "1,00 - 3,50 EUR"
            "champu" in c || "shampoo" in c -> "1,50 - 8,00 EUR"
            "crema" in c || "cream" in c || "lotion" in c -> "2,00 - 15,00 EUR"
            "gel" in c || "jabon" in c || "soap" in c -> "1,00 - 6,00 EUR"
            "pienso" in c || "dog" in c || "cat" in c || "perro" in c || "gato" in c -> "3,00 - 25,00 EUR"
            else -> null
        }
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)
}
