package com.xito.foodxcan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

data class FoodAlert(
    val title: String,
    val url: String,
    val source: String,
    val date: String = "",
    val productName: String = "",
    val brand: String = "",
    val lots: List<String> = emptyList(),
    val bestBefore: String = "",
    val weight: String = "",
    /** 2 = coincide marca y producto, 1 = coincide el tipo de alimento */
    val matchLevel: Int = 1
)

data class Hazard(val name: String, val reason: String, val severity: Int)

object Alerts {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private const val BASE = "https://www.aesan.gob.es"
    private val PAGES = listOf(
        "$BASE/AECOSAN/web/seguridad_alimentaria/subseccion/otras_alertas_alimentarias.htm" to "AESAN",
        "$BASE/AECOSAN/web/seguridad_alimentaria/subseccion/alertas_de_alergenos.htm" to "AESAN alergenos",
        "$BASE/AECOSAN/web/seguridad_alimentaria/subseccion/alertas_complementos_alimenticios.htm" to "AESAN complementos"
    )

    private val STOP = setOf(
        "alerta", "alertas", "presencia", "posible", "informacion", "ampliacion", "sobre", "procedente",
        "procedentes", "espana", "francia", "italia", "alemania", "portugal", "belgica", "paises",
        "bajos", "checa", "republica", "estados", "unidos", "india", "rusia", "grecia", "polonia",
        "marruecos", "argentina", "japon", "china", "irlanda", "rumania", "bulgaria", "suecia", "malta",
        "correccion", "errores", "retirada", "actualizacion", "interes", "toda", "poblacion",
        "salmonella", "salmonela", "listeria", "monocytogenes", "escherichia", "coli", "toxina",
        "toxinas", "cuerpos", "extranos", "fragmentos", "particulas", "piezas", "producto", "productos",
        "riesgo", "para", "como", "junta", "andalucia", "comunidad", "autonoma", "exclusivamente",
        "determinados", "lotes", "lote", "varios", "varias", "shiga", "productor", "productora",
        "sistema", "posibles", "otros", "tipo", "tipos", "marca", "nombre", "aspecto", "unidad"
    )

    @Volatile private var listCache: List<FoodAlert>? = null
    @Volatile private var listTime = 0L
    private val detailCache = mutableMapOf<String, FoodAlert>()

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun fetch(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Foodxcan/2.0 (Android)").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) { null }

    /** Convierte el HTML en texto con saltos de linea por bloque. */
    private fun htmlToLines(html: String): List<String> = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(li|p|td|tr|div|h\\d)>"), "\n")
        .replace(Regex("(?s)<script.*?</script>"), " ")
        .replace(Regex("(?s)<style.*?</style>"), " ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&aacute;", "a")
        .replace("&eacute;", "e").replace("&iacute;", "i").replace("&oacute;", "o")
        .replace("&uacute;", "u").replace("&ntilde;", "n").replace("&#243;", "o")
        .split("\n").map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }

    // ---------- Listado ----------
    private fun fetchListing(url: String, source: String): List<FoodAlert> {
        val html = fetch(url) ?: return emptyList()
        val rx = Regex("""<a[^>]+href="([^"]*(?:alertas_alimentarias|ampliacion)[^"]*\.htm)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return rx.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1]
            val text = m.groupValues[2].replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
            if (text.length < 15) null
            else FoodAlert(text, if (href.startsWith("http")) href else BASE + href, source)
        }.distinctBy { it.url }.toList()
    }

    suspend fun loadListing(): List<FoodAlert> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        listCache?.let { if (now - listTime < 6 * 60 * 60 * 1000L) return@withContext it }
        val all = PAGES.flatMap { (u, s) -> fetchListing(u, s) }
        if (all.isNotEmpty()) { listCache = all; listTime = now }
        all
    }

    // ---------- Ficha individual ----------
    private fun fetchDetail(a: FoodAlert): FoodAlert {
        detailCache[a.url]?.let { return it }
        val html = fetch(a.url) ?: return a
        val lines = htmlToLines(html)

        fun field(vararg labels: String): String {
            for (l in lines) {
                for (lab in labels) {
                    val rx = Regex("^\\s*${Regex.escape(lab)}\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
                    val m = rx.find(l)
                    if (m != null) {
                        val v = m.groupValues[1].trim().trimStart(':').trim()
                        if (v.isNotBlank() && v.length < 120) return v
                    }
                }
            }
            return ""
        }
        // Puede haber varios lotes en la misma alerta
        val lots = lines.mapNotNull { l ->
            Regex("^\\s*(?:N.?mero de lote|Lote|Lotes)\\s*:?\\s*(.+)$", RegexOption.IGNORE_CASE)
                .find(l)?.groupValues?.get(1)?.trim()
        }.flatMap { it.split(",", ";", " y ") }.map { it.trim() }
            .filter { it.isNotBlank() && it.length < 40 }.distinct()

        val fecha = lines.firstOrNull { it.startsWith("Fecha:", true) }
            ?.removePrefix("Fecha:")?.trim().orEmpty()

        val detail = a.copy(
            date = fecha,
            productName = field("Nombre del producto", "Denominacion del producto", "Nombre comercial"),
            brand = field("Nombre de marca", "Marca", "Nombre de la marca"),
            lots = lots,
            bestBefore = field("Fecha de consumo preferente", "Fecha de caducidad"),
            weight = field("Peso de unidad", "Peso", "Formato")
        )
        detailCache[a.url] = detail
        return detail
    }

    // ---------- Cruce con el producto escaneado ----------
    private fun sig(s: String) = norm(s).split(" ").filter { it.length > 3 && it !in STOP }.toSet()

    suspend fun matchFor(p: Product): List<FoodAlert> = coroutineScope {
        val listing = loadListing()
        if (listing.isEmpty()) return@coroutineScope emptyList()

        val prodWords = sig(listOfNotNull(p.name, p.categoryName).joinToString(" "))
        val brandNorm = norm(p.brand)

        // 1) Preseleccion barata por el titulo del listado
        val candidates = listing.mapNotNull { a ->
            val keys = sig(a.title)
            val hits = keys.count { k -> prodWords.any { w -> w == k || (k.length > 5 && w.startsWith(k.take(5))) } }
            if (hits >= 1) a to hits else null
        }.sortedByDescending { it.second }.take(12).map { it.first }
        if (candidates.isEmpty()) return@coroutineScope emptyList()

        // 2) Se abren las fichas para leer marca, nombre exacto y lote
        val details = candidates.map { async(Dispatchers.IO) { fetchDetail(it) } }.map { it.await() }

        // 3) Puntuacion: marca coincidente pesa mucho mas que el tipo de alimento
        details.mapNotNull { d ->
            val aBrand = norm(d.brand)
            val brandOk = brandNorm.isNotBlank() && aBrand.isNotBlank() &&
                (aBrand.contains(brandNorm) || brandNorm.contains(aBrand)) && brandNorm.length >= 3
            val nameWords = sig(d.productName.ifBlank { d.title })
            val nameHits = nameWords.count { k -> prodWords.any { w -> w == k || (k.length > 5 && w.startsWith(k.take(5))) } }

            when {
                brandOk && nameHits >= 1 -> d.copy(matchLevel = 2)
                brandOk -> d.copy(matchLevel = 2)
                nameHits >= 2 -> d.copy(matchLevel = 1)
                else -> null
            }
        }.sortedByDescending { it.matchLevel }.take(4)
    }

    // ---------- Sustancias prohibidas o vigiladas ----------
    fun hazards(p: Product): List<Hazard> {
        val out = mutableListOf<Hazard>()
        val ing = norm(p.ingredientsText.orEmpty())

        p.additives.forEach { a ->
            when (a.code.uppercase()) {
                "E171" -> out.add(Hazard("E171 Dioxido de titanio",
                    "Prohibido en la Union Europea desde 2022 por posible genotoxicidad. No deberia estar en un producto a la venta en la UE.", 3))
                "E102", "E104", "E110", "E122", "E124", "E129" -> out.add(Hazard("${a.code} ${a.name}",
                    "Colorante azoico. La UE obliga a advertir que puede afectar a la actividad y la atencion de los ninos.", 3))
                "E249", "E250", "E251", "E252" -> out.add(Hazard("${a.code} ${a.name}",
                    "Nitritos y nitratos: pueden formar nitrosaminas, clasificadas como probablemente cancerigenas en carne procesada.", 3))
                "E320", "E321" -> out.add(Hazard("${a.code} ${a.name}",
                    "Antioxidante sintetico sospechoso de actuar como disruptor endocrino.", 2))
                "E951" -> out.add(Hazard("E951 Aspartamo",
                    "Clasificado por la IARC en 2023 como posible cancerigeno (grupo 2B). Prohibido en fenilcetonuria.", 2))
            }
        }
        if (ing.contains("parcialmente hidrogenad") || ing.contains("grasas trans"))
            out.add(Hazard("Grasas parcialmente hidrogenadas",
                "Fuente de grasas trans industriales, limitadas por ley en la UE por su riesgo cardiovascular.", 3))
        if (ing.contains("aceite de palma") && !ing.contains("no contiene aceite de palma"))
            out.add(Hazard("Aceite de palma",
                "Alto en grasas saturadas y puede contener contaminantes de proceso si se refina a alta temperatura.", 1))

        return out.distinctBy { it.name }.sortedByDescending { it.severity }
    }
}
