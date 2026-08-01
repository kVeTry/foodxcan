package com.xito.foodxcan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiRepo {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // Clave de Pollinations. Regenerable en enter.pollinations.ai
    private const val TOKEN = "sk_ZhE6E7VeR6hHdut0gVXvDEszN4H5QgqJ"

    // Endpoints compatibles con OpenAI. gen.pollinations.ai es el host unificado actual.
    private val ENDPOINTS = listOf(
        "https://gen.pollinations.ai/v1/chat/completions",
        "https://text.pollinations.ai/openai"
    )
    private val FREE_MODELS = listOf("mistral", "openai-fast", "gpt-oss", "llama", "deepseek")

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    // ---------- Llamada generica con reintentos ----------
    private fun ask(prompt: String, maxTokens: Int = 900): Result {
        var lastError = "No se pudo conectar con la IA."
        for (endpoint in ENDPOINTS) {
            for (model in FREE_MODELS) {
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", maxTokens)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user"); put("content", prompt)
                    }))
                    put("private", true)
                }
                val req = Request.Builder()
                    .url(endpoint)
                    .header("content-type", "application/json")
                    .header("Authorization", "Bearer $TOKEN")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                try {
                    http.newCall(req).execute().use { r ->
                        val raw = r.body?.string().orEmpty()
                        if (r.isSuccessful) {
                            val text = try {
                                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                                    .getJSONObject("message").getString("content").trim()
                            } catch (e: Exception) { raw.trim() }
                            if (text.isNotEmpty()) return Result.Ok(text)
                            lastError = "La IA no devolvio texto."
                        } else {
                            lastError = when (r.code) {
                                402 -> "Modelo de pago, probando otro..."
                                429 -> "Servicio de IA saturado. Prueba en unos segundos."
                                401, 403 -> "Clave de IA no valida."
                                else -> "Error del servicio de IA (${r.code})."
                            }
                            if (r.code == 429) return Result.Error(lastError)
                        }
                    }
                } catch (e: Exception) {
                    lastError = if (e.message?.contains("resolve host") == true)
                        "Sin conexion a internet o servidor de IA no disponible."
                    else "No se pudo conectar con la IA: ${e.message}"
                }
            }
        }
        return Result.Error(lastError)
    }

    // ---------- Analisis de un producto ya conocido ----------
    suspend fun analyze(p: Product): Result = withContext(Dispatchers.IO) {
        val ingredientes = if (p.additives.isEmpty()) "sin aditivos destacados"
        else p.additives.joinToString(", ") { "${it.code} (${it.name}, ${it.risk.label})" }

        val prompt = """
            Eres un nutricionista claro y directo. Analiza este producto para un consumidor normal, en espanol y sin tecnicismos.

            Producto: ${p.name}
            Marca: ${p.brand}
            Categoria: ${p.categoryName ?: "desconocida"}
            Nutri-Score: ${p.nutriScore?.uppercase() ?: "no disponible"}
            Grupo NOVA: ${p.novaGroup ?: "no disponible"}
            Aditivos: $ingredientes

            Usa EXACTAMENTE estos apartados, cada uno en su linea y en mayusculas seguido de dos puntos:

            RESUMEN: 2 o 3 frases sobre si es buena o mala eleccion y por que.
            EN QUE AYUDA: beneficios reales. Si no aporta nada bueno, dilo claramente.
            EN QUE PERJUDICA: ingredientes o valores mas preocupantes y su efecto en la salud.
            ALTERNATIVAS MEJORES: 2 o 3 productos concretos mas saludables que lo sustituyan.
            CONSEJO: con que frecuencia consumirlo o para quien no es recomendable.

            Se conciso. No inventes datos. Maximo 260 palabras. Responde solo con el analisis.
        """.trimIndent()
        ask(prompt)
    }

    // ---------- Identificacion de un producto que no esta en la base de datos ----------
    data class Guess(
        val name: String, val brand: String, val category: String, val confidence: String,
        val kcal: Double?, val fat: Double?, val satFat: Double?, val carbs: Double?,
        val sugar: Double?, val fiber: Double?, val protein: Double?, val salt: Double?,
        val score: Int, val positives: List<String>, val negatives: List<String>, val note: String
    )

    suspend fun identify(barcode: String): Pair<Guess?, String?> = withContext(Dispatchers.IO) {
        val prompt = """
            Un usuario ha escaneado el codigo de barras $barcode y el producto no aparece en Open Food Facts.
            Deduce de que producto se trata a partir del codigo (el prefijo indica el pais: 84 = Espana, 80 = Italia, 87 = Paises Bajos, etc.) y de tu conocimiento.

            Responde UNICAMENTE con un objeto JSON valido, sin texto antes ni despues, sin bloques de codigo, con esta estructura exacta:
            {
              "name": "nombre del producto o 'Desconocido'",
              "brand": "marca o ''",
              "category": "categoria del producto",
              "confidence": "alta|media|baja",
              "kcal": numero o null,
              "fat": numero o null,
              "satFat": numero o null,
              "carbs": numero o null,
              "sugar": numero o null,
              "fiber": numero o null,
              "protein": numero o null,
              "salt": numero o null,
              "score": numero entero del 0 al 100,
              "positives": ["punto positivo", "..."],
              "negatives": ["punto negativo", "..."],
              "note": "una frase explicando en que te has basado"
            }

            Los valores nutricionales son por 100 g o 100 ml. Si no sabes el producto, pon name "Desconocido" y confidence "baja".
        """.trimIndent()

        when (val r = ask(prompt, 800)) {
            is Result.Error -> null to r.message
            is Result.Ok -> {
                try {
                    val clean = r.text.substringAfter("{", "").let { "{$it" }
                        .substringBeforeLast("}", "").let { "$it}" }
                    val o = JSONObject(clean)
                    fun num(k: String): Double? {
                        if (!o.has(k) || o.isNull(k)) return null
                        return o.optDouble(k).takeIf { !it.isNaN() }
                    }
                    fun list(k: String): List<String> {
                        val a = o.optJSONArray(k) ?: return emptyList()
                        return buildList { for (i in 0 until a.length()) add(a.getString(i)) }
                    }
                    val g = Guess(
                        name = o.optString("name", "Desconocido"),
                        brand = o.optString("brand", ""),
                        category = o.optString("category", ""),
                        confidence = o.optString("confidence", "baja"),
                        kcal = num("kcal"), fat = num("fat"), satFat = num("satFat"),
                        carbs = num("carbs"), sugar = num("sugar"), fiber = num("fiber"),
                        protein = num("protein"), salt = num("salt"),
                        score = o.optInt("score", 50).coerceIn(0, 100),
                        positives = list("positives"), negatives = list("negatives"),
                        note = o.optString("note", "")
                    )
                    g to null
                } catch (e: Exception) {
                    null to "La IA no devolvio un resultado interpretable."
                }
            }
        }
    }

    /** Convierte la estimacion de la IA en un Product para reutilizar la ficha. */
    fun guessToProduct(barcode: String, g: Guess): Product {
        val nutrients = buildList {
            g.kcal?.let { add(Nutrient("Energia", it, "kcal", null)) }
            g.fat?.let { add(Nutrient("Grasas", it, "g", null)) }
            g.satFat?.let { add(Nutrient("de las cuales saturadas", it, "g", null)) }
            g.carbs?.let { add(Nutrient("Hidratos de carbono", it, "g", null)) }
            g.sugar?.let { add(Nutrient("de los cuales azucares", it, "g", null)) }
            g.fiber?.let { add(Nutrient("Fibra", it, "g", null)) }
            g.protein?.let { add(Nutrient("Proteinas", it, "g", null)) }
            g.salt?.let { add(Nutrient("Sal", it, "g", null)) }
        }
        return Product(
            barcode = barcode, name = g.name, brand = g.brand, imageUrl = null, quantity = "",
            source = Source.FOOD, nutriScore = null, novaGroup = null, ecoScore = null,
            category = null, categoryTags = emptyList(), categoryName = g.category,
            ingredientsText = null, allergens = emptyList(), labels = emptyList(),
            additives = emptyList(), score = g.score,
            positives = g.positives.map { Insight("label", it, "", 0) },
            negatives = g.negatives.map { Insight("label", it, "", 2) },
            nutrients = nutrients,
            servingSize = null, estimatedPrice = null,
            kcal100 = g.kcal, sugar100 = g.sugar, salt100 = g.salt,
            satFat100 = g.satFat, protein100 = g.protein, fiber100 = g.fiber,
            aiEstimated = true
        )
    }
}
