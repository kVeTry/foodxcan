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

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun analyze(p: Product): Result = withContext(Dispatchers.IO) {
        val ingredientes = if (p.additives.isEmpty()) "sin aditivos destacados"
        else p.additives.joinToString(", ") { "${it.code} (${it.name})" }

        val prompt = """
            Eres un nutricionista claro y directo. Analiza este producto alimenticio.

            Producto: ${p.name}
            Marca: ${p.brand}
            Categoria: ${p.categoryName ?: "desconocida"}
            Nutri-Score: ${p.nutriScore?.uppercase() ?: "no disponible"}
            Grupo NOVA: ${p.novaGroup ?: "no disponible"}
            Aditivos: $ingredientes

            Redacta un analisis en espanol para un consumidor normal (sin tecnicismos), con esta estructura:
            1. Resumen (2-3 frases sobre si es buena o mala eleccion y por que).
            2. Ingredientes a vigilar (los mas preocupantes y que efecto tienen).
            3. Consejo practico (con que frecuencia consumirlo o para quien no es recomendable).

            Se conciso. No inventes datos. Maximo 220 palabras. Responde solo con el analisis, sin preambulos.
        """.trimIndent()

        // API gratuita de Pollinations (sin clave). Endpoint POST compatible con OpenAI.
        val body = JSONObject().apply {
            put("model", "openai")
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", prompt)
            }))
            put("private", true)
        }

        val req = Request.Builder()
            .url("https://text.pollinations.ai/openai")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@withContext Result.Error(
                        when (r.code) {
                            429 -> "El servicio de IA esta saturado ahora mismo. Prueba de nuevo en unos segundos."
                            else -> "Error del servicio de IA (${r.code})."
                        }
                    )
                }
                val text = try {
                    JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                } catch (e: Exception) {
                    raw.trim()   // por si devuelve texto plano
                }
                if (text.isEmpty()) Result.Error("La IA no devolvio texto.") else Result.Ok(text)
            }
        } catch (e: Exception) {
            Result.Error("No se pudo conectar con la IA: ${e.message}")
        }
    }
}
