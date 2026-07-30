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

    // Clave de Pollinations (opcional para modelos gratis). Regenerable en enter.pollinations.ai
    private const val TOKEN = "sk_ZhE6E7VeR6hHdut0gVXvDEszN4H5QgqJ"

    // Modelos gratuitos de Pollinations, se prueban en orden hasta que uno responda
    private val FREE_MODELS = listOf("mistral", "openai-fast", "gpt-oss", "llama", "deepseek")

    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun analyze(p: Product): Result = withContext(Dispatchers.IO) {
        val ingredientes = if (p.additives.isEmpty()) "sin aditivos destacados"
        else p.additives.joinToString(", ") { "${it.code} (${it.name}, ${it.risk.label})" }

        val prompt = """
            Eres un nutricionista claro y directo. Analiza este producto alimenticio para un consumidor normal, en espanol y sin tecnicismos.

            Producto: ${p.name}
            Marca: ${p.brand}
            Categoria: ${p.categoryName ?: "desconocida"}
            Nutri-Score: ${p.nutriScore?.uppercase() ?: "no disponible"}
            Grupo NOVA: ${p.novaGroup ?: "no disponible"}
            Aditivos: $ingredientes

            Escribe el analisis con EXACTAMENTE estos apartados y sus titulos en mayusculas seguidos de dos puntos:

            RESUMEN: 2 o 3 frases sobre si es buena o mala eleccion y por que.
            EN QUE AYUDA: aspectos positivos o beneficios reales (nutrientes utiles). Si no aporta nada bueno, dilo claramente.
            EN QUE PERJUDICA: los ingredientes o valores mas preocupantes y su efecto en la salud.
            ALTERNATIVAS MEJORES: 2 o 3 productos o habitos concretos mas saludables que sustituyan a este.
            CONSEJO: con que frecuencia consumirlo o para quien no es recomendable.

            Se conciso. No inventes datos. Maximo 260 palabras. Responde solo con el analisis.
        """.trimIndent()

        var lastError = "No se pudo conectar con la IA."
        for (model in FREE_MODELS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user"); put("content", prompt)
                }))
                put("private", true)
            }
            val req = Request.Builder()
                .url("https://text.pollinations.ai/openai")
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
                        if (text.isNotEmpty()) return@withContext Result.Ok(text)
                        lastError = "La IA no devolvio texto."
                    } else {
                        lastError = when (r.code) {
                            402 -> "Modelo de pago, probando otro..."   // se pasa al siguiente modelo
                            429 -> "El servicio de IA esta saturado. Prueba de nuevo en unos segundos."
                            401, 403 -> "Clave de IA no valida."
                            else -> "Error del servicio de IA (${r.code})."
                        }
                        // Si es saturacion o error de red, no seguimos probando modelos
                        if (r.code == 429) return@withContext Result.Error(lastError)
                    }
                }
            } catch (e: Exception) {
                lastError = "No se pudo conectar con la IA: ${e.message}"
            }
        }
        Result.Error(lastError)
    }
}
