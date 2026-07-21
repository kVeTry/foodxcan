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

    // Clave de Pollinations. Puedes regenerarla en enter.pollinations.ai
    private const val TOKEN = "sk_ZhE6E7VeR6hHdut0gVXvDEszN4H5QgqJ"

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

            Escribe el analisis con EXACTAMENTE estos apartados y sus titulos:

            RESUMEN: 2 o 3 frases sobre si es buena o mala eleccion y por que.
            EN QUE AYUDA: aspectos positivos o beneficios reales del producto (nutrientes utiles, si aporta algo bueno). Si no aporta nada bueno, dilo claramente.
            EN QUE PERJUDICA: los ingredientes o valores mas preocupantes y que efecto tienen en la salud.
            ALTERNATIVAS MEJORES: sugiere 2 o 3 tipos de productos o habitos concretos mas saludables que sustituyan a este (por ejemplo "yogur natural sin azucar en vez de este postre lacteo").
            CONSEJO: con que frecuencia consumirlo o para quien no es recomendable.

            Se conciso. No inventes datos. Maximo 260 palabras. Responde solo con el analisis.
        """.trimIndent()

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
            .header("Authorization", "Bearer $TOKEN")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    return@withContext Result.Error(
                        when (r.code) {
                            401, 403 -> "La clave de IA no es valida. Regenera una en enter.pollinations.ai."
                            402 -> "La clave de IA no tiene saldo/creditos disponibles."
                            429 -> "El servicio de IA esta saturado. Prueba de nuevo en unos segundos."
                            else -> "Error del servicio de IA (${r.code})."
                        }
                    )
                }
                val text = try {
                    JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                } catch (e: Exception) {
                    raw.trim()
                }
                if (text.isEmpty()) Result.Error("La IA no devolvio texto.") else Result.Ok(text)
            }
        } catch (e: Exception) {
            Result.Error("No se pudo conectar con la IA: ${e.message}")
        }
    }
}
