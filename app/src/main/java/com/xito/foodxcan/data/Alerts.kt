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

    /** Decodifica entidades HTML: &oacute;, &#243;, &#xF3;, etc. */
    private fun decodeEntities(t: String): String {
        var out = t
        val named = mapOf(
            "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
            "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú",
            "ntilde" to "ñ", "Ntilde" to "Ñ", "uuml" to "ü", "Uuml" to "Ü",
            "agrave" to "à", "egrave" to "è", "ccedil" to "ç", "amp" to "&", "nbsp" to " ",
            "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "deg" to "°",
            "ordm" to "º", "ordf" to "ª", "laquo" to "«", "raquo" to "»", "middot" to "·",
            "mdash" to "—", "ndash" to "–", "hellip" to "…", "euro" to "€", "reg" to "®", "trade" to "™"
        )
        named.forEach { (k, v) -> out = out.replace("&$k;", v) }
        // Numericas decimales &#243; y hexadecimales &#xF3;
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        out = Regex("(?i)&#x([0-9a-f]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return out
    }

    /** Convierte el HTML en texto con saltos de linea por bloque. */
    private fun htmlToLines(html: String): List<String> = decodeEntities(
        html.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(li|p|td|tr|div|h\\d)>"), "\n")
            .replace(Regex("(?s)<script.*?</script>"), " ")
            .replace(Regex("(?s)<style.*?</style>"), " ")
            .replace(Regex("<[^>]*>"), "")
    ).split("\n").map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }

    // ---------- Listado ----------
    private fun fetchListing(url: String, source: String): List<FoodAlert> {
        val html = fetch(url) ?: return emptyList()
        val rx = Regex("""<a[^>]+href="([^"]*(?:alertas_alimentarias|ampliacion)[^"]*\.htm)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return rx.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1]
            val text = decodeEntities(m.groupValues[2].replace(Regex("<[^>]*>"), " "))
                .replace(Regex("\\s+"), " ").trim()
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

    /** Alimentos concretos: solo estos sirven para afirmar que hay coincidencia de tipo. */
    private val FOOD_TERMS = setOf(
        "salchichon", "chorizo", "fuet", "lomo", "jamon", "salchicha", "morcilla", "sobrasada",
        "queso", "quesos", "yogur", "yogures", "leche", "mantequilla", "nata", "cuajada", "kefir",
        "atun", "salmon", "bacalao", "merluza", "anchoa", "anchoas", "sardina", "sardinas",
        "gamba", "gambas", "langostino", "langostinos", "mejillon", "mejillones", "almeja",
        "pulpo", "calamar", "boqueron", "boquerones", "trucha", "pez", "marisco",
        "pollo", "pavo", "cerdo", "ternera", "vacuno", "cordero", "conejo", "hamburguesa",
        "huevo", "huevos", "tortilla", "pate", "foie",
        "chocolate", "cacao", "galleta", "galletas", "bolleria", "bizcocho", "magdalena",
        "helado", "helados", "tarta", "pastel", "turron", "polvoron", "caramelo", "gominola",
        "pan", "harina", "pasta", "arroz", "cereal", "cereales", "avena", "muesli", "tostada",
        "aceite", "aceituna", "aceitunas", "vinagre", "salsa", "mayonesa", "ketchup", "mostaza",
        "hummus", "guacamole", "tomate", "pimiento", "pimenton", "especias", "curcuma", "canela",
        "pistacho", "pistachos", "cacahuete", "cacahuetes", "almendra", "almendras", "nuez",
        "nueces", "avellana", "avellanas", "anacardo", "anacardos", "sesamo", "semillas",
        "pipas", "frutos", "datil", "datiles", "pasas", "higo", "higos", "orejones",
        "zumo", "refresco", "bebida", "agua", "cerveza", "vino", "sidra", "infusion", "cafe",
        "verdura", "verduras", "espinaca", "espinacas", "lechuga", "rucula", "canonigos",
        "legumbre", "legumbres", "garbanzo", "garbanzos", "lenteja", "lentejas", "alubia",
        "judias", "soja", "tofu", "seitan", "pizza", "empanada", "croqueta", "croquetas",
        "sopa", "caldo", "gazpacho", "conserva", "pescado", "carne", "embutido", "fiambre",
        "mermelada", "miel", "azucar", "sal", "gel", "champu", "crema", "locion", "pienso"
    )

    /** Palabras del producto que aparecen en el texto de la alerta. */
    private fun foodOverlap(prodWords: Set<String>, alertWords: Set<String>): Int =
        prodWords.count { w -> w in FOOD_TERMS && alertWords.any { k -> k == w || k.startsWith(w.take(5)) } }

    suspend fun matchFor(p: Product): List<FoodAlert> = coroutineScope {
        val listing = loadListing()
        if (listing.isEmpty()) return@coroutineScope emptyList()

        val prodWords = sig(listOfNotNull(p.name, p.categoryName).joinToString(" "))
        val brandNorm = norm(p.brand)
        if (prodWords.isEmpty() && brandNorm.isBlank()) return@coroutineScope emptyList()

        // 1) Preseleccion: la alerta debe mencionar un alimento concreto del producto
        val candidates = listing.mapNotNull { a ->
            val keys = sig(a.title)
            val food = foodOverlap(prodWords, keys)
            if (food >= 1) a to food else null
        }.sortedByDescending { it.second }.take(10).map { it.first }
        if (candidates.isEmpty()) return@coroutineScope emptyList()

        // 2) Se abren las fichas para leer marca, nombre exacto y lote
        val details = candidates.map { async(Dispatchers.IO) { fetchDetail(it) } }.map { it.await() }

        // 3) Coincidencia estricta: la marca manda; si no, el alimento debe coincidir de verdad
        details.mapNotNull { d ->
            val aBrand = norm(d.brand)
            val brandOk = brandNorm.length >= 4 && aBrand.length >= 4 &&
                (aBrand == brandNorm || aBrand.contains(brandNorm) || brandNorm.contains(aBrand))
            val alertWords = sig(d.productName.ifBlank { d.title })
            val food = foodOverlap(prodWords, alertWords)

            when {
                brandOk && food >= 1 -> d.copy(matchLevel = 2)   // marca + alimento: casi seguro
                food >= 2 -> d.copy(matchLevel = 1)              // dos alimentos coincidentes
                else -> null                                     // el resto se descarta
            }
        }.sortedByDescending { it.matchLevel }.take(3)
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
