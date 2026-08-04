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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    // Clave de Pollinations (respaldo gratuito sin registro)
    private const val POLLI_TOKEN = "sk_ZhE6E7VeR6hHdut0gVXvDEszN4H5QgqJ"

    // Claves del usuario, cargadas al abrir la app desde los ajustes
    @Volatile var groqKey: String = ""
    @Volatile var nvidiaKey: String = ""
    /** Modelo de NVIDIA elegido en Ajustes. Vacio = el recomendado. */
    @Volatile var nvidiaModel: String = ""

    /** Modelos de NVIDIA recomendados para esta app, de mejor a mas ligero. */
    val NVIDIA_CATALOG = listOf(
        "meta/llama-3.3-70b-instruct" to "Llama 3.3 70B (recomendado)",
        "nvidia/llama-3.3-nemotron-super-49b-v1" to "Nemotron Super 49B",
        "qwen/qwen2.5-72b-instruct" to "Qwen 2.5 72B",
        "openai/gpt-oss-120b" to "GPT-OSS 120B",
        "meta/llama-3.1-8b-instruct" to "Llama 3.1 8B (mas rapido)"
    )

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val GROQ_MODELS = listOf(
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "openai/gpt-oss-120b"
    )

    // NVIDIA Build (NIM): compatible con OpenAI, gratis con cuenta de desarrollador
    private const val NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
    /** El modelo elegido va primero; el resto quedan de respaldo si desaparece. */
    private fun nvidiaModels(): List<String> {
        val base = NVIDIA_CATALOG.map { it.first }
        return if (nvidiaModel.isBlank()) base
        else listOf(nvidiaModel) + base.filter { it != nvidiaModel }
    }

    private data class Provider(val url: String, val token: String, val models: List<String>, val name: String)

    private fun providers(): List<Provider> = buildList {
        // Groq primero por velocidad; NVIDIA despues; Pollinations como ultimo recurso
        if (groqKey.isNotBlank()) add(Provider(GROQ_URL, groqKey, GROQ_MODELS, "Groq"))
        if (nvidiaKey.isNotBlank()) add(Provider(NVIDIA_URL, nvidiaKey, nvidiaModels(), "NVIDIA"))
        add(Provider("https://text.pollinations.ai/openai", POLLI_TOKEN, listOf("mistral", "openai-fast"), "Pollinations"))
        add(Provider("https://gen.pollinations.ai/v1/chat/completions", POLLI_TOKEN, listOf("mistral"), "Pollinations"))
    }

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    private fun postOnce(p: Provider, model: String, prompt: String, maxTokens: Int): Pair<String?, String> {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.4)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", prompt)
            }))
        }
        val builder = Request.Builder().url(p.url)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (p.token.isNotBlank()) builder.header("Authorization", "Bearer ${p.token}")

        return try {
            http.newCall(builder.build()).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (r.isSuccessful) {
                    val text = try {
                        JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                    } catch (e: Exception) { raw.trim() }
                    if (text.isNotEmpty()) text to "" else null to "respuesta vacia"
                } else {
                    val detalle = try { JSONObject(raw).getJSONObject("error").getString("message") } catch (e: Exception) { "" }
                    null to "http ${r.code} ${detalle.take(80)}"
                }
            }
        } catch (e: Exception) {
            null to (e.message ?: "error de red")
        }
    }

    /** Ultimo diagnostico tecnico, para mostrarlo si algo falla. */
    @Volatile var lastDiagnostic: String = ""

    private fun ask(prompt: String, maxTokens: Int = 900): Result {
        var saturado = false; var sinRed = false; var claveMal = false; var sinCreditos = false
        val log = mutableListOf<String>()
        val provs = providers()
        if (provs.isEmpty()) return Result.Error("No hay ningun servicio de IA configurado.")

        for (intento in 0 until 2) {
            for (p in provs) {
                for (m in p.models) {
                    val (text, err) = postOnce(p, m, prompt, maxTokens)
                    if (text != null) {
                        lastDiagnostic = "OK con ${p.name} / $m"
                        return Result.Ok(text)
                    }
                    log.add("${p.name}/$m: $err")
                    when {
                        err.contains("429") -> saturado = true
                        err.contains("401") || err.contains("403") ->
                            if (p.name == "Groq" || p.name == "NVIDIA") claveMal = true
                        err.contains("402") -> if (p.name == "NVIDIA") sinCreditos = true
                        err.contains("resolve host", true) || err.contains("Unable to resolve", true) ||
                        err.contains("timeout", true) || err.contains("timed out", true) ||
                        err.contains("failed to connect", true) -> sinRed = true
                    }
                }
            }
            // Reintentar solo tiene sentido si fue saturacion pasajera
            if (!saturado) break
            try { Thread.sleep(3000) } catch (e: InterruptedException) { }
        }

        lastDiagnostic = log.distinct().joinToString(" | ").take(400)
        val base = when {
            claveMal -> "La clave de IA no es valida. Revisala en Ajustes."
            sinCreditos -> "El modelo de NVIDIA elegido no esta disponible con tu cuenta. Prueba otro modelo en Ajustes."
            saturado -> "Limite de peticiones alcanzado. Espera un minuto."
            sinRed -> "Sin conexion con el servicio de IA. Comprueba tu internet."
            provs.none { it.name == "Groq" || it.name == "NVIDIA" } ->
                "El servicio gratuito de IA no responde. Anade una clave de Groq o de NVIDIA en Ajustes (son gratis) para que funcione de forma fiable."
            else -> "El servicio de IA no responde."
        }
        return Result.Error("$base\n\nDetalle: $lastDiagnostic")
    }

    /** Comprueba que la IA responde. Devuelve "OK ..." o el motivo del fallo. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        when (val r = ask("Responde unicamente con la palabra: correcto", 20)) {
            is Result.Ok -> "OK: $lastDiagnostic"
            is Result.Error -> r.message
        }
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
