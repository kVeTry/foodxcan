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
        .readTimeout(90, TimeUnit.SECONDS)   // la búsqueda web tarda más
        .build()

    private const val MODEL = "claude-sonnet-5"

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun analyze(apiKey: String, p: Product): Result = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("Falta la API key. Ve a Ajustes y añade tu clave de Anthropic.")

        val ingredientes = if (p.additives.isEmpty()) "sin aditivos destacados"
        else p.additives.joinToString(", ") { "${it.code} (${it.name})" }

        val prompt = """
            Eres un nutricionista claro y directo. Analiza este producto alimenticio buscando información actualizada en internet.

            Producto: ${p.name}
            Marca: ${p.brand}
            Categoría: ${p.categoryName ?: "desconocida"}
            Nutri-Score: ${p.nutriScore?.uppercase() ?: "no disponible"}
            Grupo NOVA: ${p.novaGroup ?: "no disponible"}
            Aditivos: $ingredientes

            Busca en internet información fiable sobre este producto y sus ingredientes. Después redacta un análisis en español para un consumidor normal (sin tecnicismos), con esta estructura:

            1. Resumen (2-3 frases sobre si es una buena o mala elección y por qué).
            2. Ingredientes a vigilar (los más preocupantes y qué efecto tienen).
            3. Consejo práctico (con qué frecuencia consumirlo o para quién no es recomendable).

            Sé conciso. No inventes datos: si algo no lo encuentras, dilo. Máximo 250 palabras.
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1024)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", prompt)
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("type", "web_search_20250305"); put("name", "web_search")
            }))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = try { JSONObject(raw).getJSONObject("error").getString("message") } catch (e: Exception) { "Error ${r.code}" }
                    return@withContext Result.Error(
                        when (r.code) {
                            401 -> "API key inválida. Revísala en Ajustes."
                            429 -> "Has superado el límite de uso de la API. Inténtalo más tarde."
                            else -> msg
                        }
                    )
                }
                // Se concatenan todos los bloques de texto de la respuesta
                val content = JSONObject(raw).optJSONArray("content") ?: return@withContext Result.Error("Respuesta vacía.")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) Result.Error("La IA no devolvió texto.") else Result.Ok(text)
            }
        } catch (e: Exception) {
            Result.Error("No se pudo conectar con la IA: ${e.message}")
        }
    }
}
