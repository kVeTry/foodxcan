package com.xito.foodxcan.data

enum class Risk(val label: String, val weight: Int) {
    SIN_RIESGO("Sin riesgo", 0),
    LIMITADO("Riesgo limitado", 4),
    MODERADO("Riesgo moderado", 10),
    ALTO("Riesgo alto", 18)
}

data class AdditiveInfo(val code: String, val name: String, val category: String, val risk: Risk, val description: String)

object Additives {
    private val db = listOf(
        // Colorantes
        AdditiveInfo("E100", "Curcumina", "Colorante", Risk.SIN_RIESGO, "Colorante natural amarillo extraído de la cúrcuma. Seguro en las dosis habituales."),
        AdditiveInfo("E102", "Tartrazina", "Colorante", Risk.ALTO, "Colorante amarillo sintético. Puede provocar reacciones alérgicas y se asocia con hiperactividad en niños."),
        AdditiveInfo("E104", "Amarillo de quinoleína", "Colorante", Risk.ALTO, "Colorante sintético asociado con hiperactividad infantil. Prohibido en varios países."),
        AdditiveInfo("E110", "Amarillo anaranjado S", "Colorante", Risk.ALTO, "Colorante azoico. Posibles reacciones alérgicas e hiperactividad en niños."),
        AdditiveInfo("E120", "Cochinilla / Ácido carmínico", "Colorante", Risk.MODERADO, "Colorante rojo de origen animal (insecto cochinilla). Puede causar alergias."),
        AdditiveInfo("E122", "Azorrubina", "Colorante", Risk.ALTO, "Colorante rojo sintético asociado con hiperactividad infantil y alergias."),
        AdditiveInfo("E124", "Ponceau 4R", "Colorante", Risk.ALTO, "Colorante rojo azoico. Asociado con hiperactividad y reacciones alérgicas."),
        AdditiveInfo("E129", "Rojo Allura AC", "Colorante", Risk.ALTO, "Colorante rojo sintético con posibles efectos sobre la atención en niños."),
        AdditiveInfo("E131", "Azul patente V", "Colorante", Risk.MODERADO, "Colorante azul sintético. Puede provocar reacciones alérgicas raras."),
        AdditiveInfo("E132", "Indigotina", "Colorante", Risk.LIMITADO, "Colorante azul. Generalmente bien tolerado, alergias muy raras."),
        AdditiveInfo("E133", "Azul brillante FCF", "Colorante", Risk.LIMITADO, "Colorante azul sintético. Considerado seguro en dosis normales."),
        AdditiveInfo("E140", "Clorofilas", "Colorante", Risk.SIN_RIESGO, "Pigmento verde natural de las plantas. Totalmente seguro."),
        AdditiveInfo("E150a", "Caramelo natural", "Colorante", Risk.SIN_RIESGO, "Caramelo obtenido por calentamiento de azúcar. Seguro."),
        AdditiveInfo("E150c", "Caramelo amónico", "Colorante", Risk.MODERADO, "Contiene compuestos (4-MEI) cuestionados en grandes cantidades."),
        AdditiveInfo("E150d", "Caramelo de sulfito amónico", "Colorante", Risk.MODERADO, "Usado en refrescos de cola. El 4-MEI que contiene está bajo vigilancia."),
        AdditiveInfo("E160a", "Carotenos", "Colorante", Risk.SIN_RIESGO, "Pigmento natural naranja (zanahoria). Precursor de vitamina A. Seguro."),
        AdditiveInfo("E160c", "Extracto de pimentón", "Colorante", Risk.SIN_RIESGO, "Colorante natural del pimentón. Seguro."),
        AdditiveInfo("E162", "Rojo de remolacha", "Colorante", Risk.SIN_RIESGO, "Colorante natural de la remolacha. Seguro."),
        AdditiveInfo("E163", "Antocianinas", "Colorante", Risk.SIN_RIESGO, "Pigmentos naturales de frutas. Con propiedades antioxidantes."),
        AdditiveInfo("E171", "Dióxido de titanio", "Colorante", Risk.ALTO, "Prohibido en la UE desde 2022 por posible genotoxicidad. Evitar."),

        // Conservantes
        AdditiveInfo("E200", "Ácido sórbico", "Conservante", Risk.LIMITADO, "Conservante antimoho. Bien tolerado, irritaciones cutáneas raras."),
        AdditiveInfo("E202", "Sorbato potásico", "Conservante", Risk.LIMITADO, "Conservante muy común contra mohos y levaduras. Bajo riesgo."),
        AdditiveInfo("E210", "Ácido benzoico", "Conservante", Risk.MODERADO, "Puede causar alergias y, combinado con vitamina C, formar benceno."),
        AdditiveInfo("E211", "Benzoato sódico", "Conservante", Risk.MODERADO, "Conservante común en refrescos. Riesgo de formar benceno con vitamina C; posible relación con hiperactividad."),
        AdditiveInfo("E212", "Benzoato potásico", "Conservante", Risk.MODERADO, "Similar al benzoato sódico. Mismas precauciones."),
        AdditiveInfo("E220", "Dióxido de azufre", "Conservante", Risk.MODERADO, "Sulfito. Puede provocar reacciones en asmáticos y destruye la vitamina B1."),
        AdditiveInfo("E221", "Sulfito sódico", "Conservante", Risk.MODERADO, "Sulfito. Riesgo para asmáticos y personas sensibles."),
        AdditiveInfo("E223", "Metabisulfito sódico", "Conservante", Risk.MODERADO, "Sulfito común en vinos y frutas secas. Riesgo para asmáticos."),
        AdditiveInfo("E224", "Metabisulfito potásico", "Conservante", Risk.MODERADO, "Sulfito. Mismas precauciones que otros sulfitos."),
        AdditiveInfo("E228", "Sulfito ácido de potasio", "Conservante", Risk.MODERADO, "Sulfito. Riesgo para personas asmáticas."),
        AdditiveInfo("E234", "Nisina", "Conservante", Risk.LIMITADO, "Conservante natural de origen bacteriano. Bajo riesgo."),
        AdditiveInfo("E249", "Nitrito potásico", "Conservante", Risk.ALTO, "Puede formar nitrosaminas (potencialmente cancerígenas) en carnes procesadas."),
        AdditiveInfo("E250", "Nitrito sódico", "Conservante", Risk.ALTO, "Usado en embutidos. Asociado a la formación de nitrosaminas cancerígenas."),
        AdditiveInfo("E251", "Nitrato sódico", "Conservante", Risk.ALTO, "Puede convertirse en nitrito y formar nitrosaminas. Presente en curados."),
        AdditiveInfo("E252", "Nitrato potásico", "Conservante", Risk.ALTO, "Mismo riesgo que otros nitratos: formación de nitrosaminas."),
        AdditiveInfo("E260", "Ácido acético", "Conservante", Risk.SIN_RIESGO, "El ácido del vinagre. Totalmente seguro."),
        AdditiveInfo("E270", "Ácido láctico", "Conservante", Risk.SIN_RIESGO, "Ácido natural de la fermentación. Seguro."),
        AdditiveInfo("E282", "Propionato cálcico", "Conservante", Risk.LIMITADO, "Antimoho en pan de molde. Estudios sugieren posibles efectos sobre el comportamiento en dosis altas."),
        AdditiveInfo("E290", "Dióxido de carbono", "Conservante", Risk.SIN_RIESGO, "El gas de las bebidas carbonatadas. Seguro."),

        // Antioxidantes y reguladores
        AdditiveInfo("E300", "Ácido ascórbico", "Antioxidante", Risk.SIN_RIESGO, "Vitamina C. Antioxidante beneficioso."),
        AdditiveInfo("E301", "Ascorbato sódico", "Antioxidante", Risk.SIN_RIESGO, "Sal de la vitamina C. Segura."),
        AdditiveInfo("E306", "Tocoferoles", "Antioxidante", Risk.SIN_RIESGO, "Vitamina E natural. Beneficiosa."),
        AdditiveInfo("E320", "BHA", "Antioxidante", Risk.ALTO, "Butilhidroxianisol. Posible disruptor endocrino y clasificado como posible cancerígeno."),
        AdditiveInfo("E321", "BHT", "Antioxidante", Risk.ALTO, "Butilhidroxitolueno. Sospechoso de ser disruptor endocrino."),
        AdditiveInfo("E330", "Ácido cítrico", "Regulador de acidez", Risk.SIN_RIESGO, "Ácido natural de los cítricos. Muy común y seguro."),
        AdditiveInfo("E331", "Citrato sódico", "Regulador de acidez", Risk.SIN_RIESGO, "Sal del ácido cítrico. Segura."),
        AdditiveInfo("E338", "Ácido fosfórico", "Regulador de acidez", Risk.MODERADO, "Usado en colas. Su exceso se asocia con peor salud ósea y renal."),
        AdditiveInfo("E339", "Fosfatos de sodio", "Regulador de acidez", Risk.MODERADO, "El exceso de fosfatos se asocia con riesgo cardiovascular."),
        AdditiveInfo("E340", "Fosfatos de potasio", "Regulador de acidez", Risk.MODERADO, "Mismo caso: el exceso de fosfatos es cuestionado."),
        AdditiveInfo("E341", "Fosfatos de calcio", "Regulador de acidez", Risk.LIMITADO, "Fosfato con calcio. Menos problemático pero cuenta en el total de fosfatos."),

        // Espesantes, emulgentes y estabilizantes
        AdditiveInfo("E322", "Lecitinas", "Emulgente", Risk.SIN_RIESGO, "Emulgente natural (soja, girasol o huevo). Seguro."),
        AdditiveInfo("E407", "Carragenanos", "Espesante", Risk.MODERADO, "Extracto de algas. Su forma degradada es cuestionada por posible inflamación intestinal."),
        AdditiveInfo("E410", "Goma garrofín", "Espesante", Risk.SIN_RIESGO, "Espesante natural del algarrobo. Seguro."),
        AdditiveInfo("E412", "Goma guar", "Espesante", Risk.SIN_RIESGO, "Espesante natural. Puede causar gases en exceso, pero es seguro."),
        AdditiveInfo("E415", "Goma xantana", "Espesante", Risk.SIN_RIESGO, "Espesante de fermentación. Seguro."),
        AdditiveInfo("E420", "Sorbitol", "Edulcorante", Risk.LIMITADO, "Poliol. Efecto laxante en cantidades altas."),
        AdditiveInfo("E421", "Manitol", "Edulcorante", Risk.LIMITADO, "Poliol con efecto laxante en exceso."),
        AdditiveInfo("E422", "Glicerol", "Humectante", Risk.SIN_RIESGO, "Humectante común. Seguro en dosis alimentarias."),
        AdditiveInfo("E433", "Polisorbato 80", "Emulgente", Risk.MODERADO, "Emulgente sintético. Estudios sugieren posible alteración de la microbiota intestinal."),
        AdditiveInfo("E440", "Pectinas", "Espesante", Risk.SIN_RIESGO, "Fibra natural de las frutas. Beneficiosa."),
        AdditiveInfo("E450", "Difosfatos", "Estabilizante", Risk.MODERADO, "Fosfatos. Su exceso se asocia con riesgo cardiovascular y renal."),
        AdditiveInfo("E451", "Trifosfatos", "Estabilizante", Risk.MODERADO, "Fosfatos añadidos, cuestionados en exceso."),
        AdditiveInfo("E452", "Polifosfatos", "Estabilizante", Risk.MODERADO, "Común en carnes procesadas. Exceso de fósforo cuestionado."),
        AdditiveInfo("E460", "Celulosa", "Estabilizante", Risk.SIN_RIESGO, "Fibra vegetal. Segura."),
        AdditiveInfo("E466", "Carboximetilcelulosa", "Espesante", Risk.MODERADO, "Estudios recientes sugieren posible alteración de la microbiota intestinal."),
        AdditiveInfo("E471", "Mono y diglicéridos de ácidos grasos", "Emulgente", Risk.LIMITADO, "Emulgente muy común. Seguro, aunque puede contener grasas trans según el origen."),
        AdditiveInfo("E472e", "Ésteres de mono y diglicéridos", "Emulgente", Risk.LIMITADO, "Emulgente de panadería. Bajo riesgo."),
        AdditiveInfo("E476", "Polirricinoleato de poliglicerol", "Emulgente", Risk.LIMITADO, "Usado en chocolate para reducir manteca de cacao. Bajo riesgo en dosis normales."),
        AdditiveInfo("E481", "Estearoil lactilato sódico", "Emulgente", Risk.LIMITADO, "Emulgente de panadería. Bajo riesgo."),

        // Potenciadores del sabor
        AdditiveInfo("E620", "Ácido glutámico", "Potenciador del sabor", Risk.MODERADO, "Potenciador de sabor umami. Sensibilidad en algunas personas."),
        AdditiveInfo("E621", "Glutamato monosódico", "Potenciador del sabor", Risk.MODERADO, "El potenciador más usado. Puede causar el 'síndrome del restaurante chino' en personas sensibles y fomenta el sobreconsumo."),
        AdditiveInfo("E627", "Guanilato disódico", "Potenciador del sabor", Risk.MODERADO, "Suele usarse con glutamato. No recomendado para personas con gota."),
        AdditiveInfo("E631", "Inosinato disódico", "Potenciador del sabor", Risk.MODERADO, "Potenciador combinado con glutamato. Precaución en gota."),
        AdditiveInfo("E635", "5'-ribonucleótidos disódicos", "Potenciador del sabor", Risk.MODERADO, "Mezcla de E627 y E631. Mismas precauciones."),

        // Edulcorantes
        AdditiveInfo("E950", "Acesulfamo K", "Edulcorante", Risk.MODERADO, "Edulcorante sintético. Estudios en curso sobre efectos en microbiota y metabolismo."),
        AdditiveInfo("E951", "Aspartamo", "Edulcorante", Risk.MODERADO, "Clasificado por la IARC como posible cancerígeno (grupo 2B) en 2023, aunque la EFSA lo mantiene seguro en dosis autorizadas. Prohibido en fenilcetonuria."),
        AdditiveInfo("E952", "Ciclamato", "Edulcorante", Risk.MODERADO, "Prohibido en EE. UU. Cuestionado por estudios antiguos en animales."),
        AdditiveInfo("E954", "Sacarina", "Edulcorante", Risk.MODERADO, "El edulcorante sintético más antiguo. Posibles efectos sobre la microbiota."),
        AdditiveInfo("E955", "Sucralosa", "Edulcorante", Risk.MODERADO, "Edulcorante sintético. Estudios recientes cuestionan sus efectos al calentarse y sobre la microbiota."),
        AdditiveInfo("E960", "Glucósidos de esteviol (estevia)", "Edulcorante", Risk.LIMITADO, "Edulcorante de origen natural (planta estevia). Bien tolerado."),
        AdditiveInfo("E965", "Maltitol", "Edulcorante", Risk.LIMITADO, "Poliol. Efecto laxante y eleva algo la glucosa."),
        AdditiveInfo("E967", "Xilitol", "Edulcorante", Risk.LIMITADO, "Poliol. Efecto laxante en exceso. Muy tóxico para perros."),
        AdditiveInfo("E968", "Eritritol", "Edulcorante", Risk.LIMITADO, "Poliol mejor tolerado. Estudios recientes investigan su relación con eventos cardiovasculares en dosis altas.")
    )

    private val map = db.associateBy { it.code.uppercase() }

    fun find(tag: String): AdditiveInfo? {
        // tags de Open Food Facts vienen como "en:e330"
        val code = tag.substringAfter(":").uppercase().replace("I", "i") // conservar sufijos tipo e150a
        val norm = code.replaceFirstChar { it.uppercase() }
        return map[norm.uppercase()] ?: map[code.uppercase()]
    }

    fun generic(tag: String): AdditiveInfo {
        val code = tag.substringAfter(":").uppercase()
        return AdditiveInfo(code, "Aditivo $code", "Aditivo", Risk.LIMITADO,
            "Aditivo autorizado en la UE. No hay información detallada en la base local; consulta su ficha en Open Food Facts.")
    }
}
