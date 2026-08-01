package com.xito.foodxcan

import android.media.AudioManager
import android.media.ToneGenerator

object BarcodeUtils {
    /** Valida el digito de control de EAN-13, EAN-8 y UPC-A para evitar lecturas erroneas. */
    fun isValid(code: String): Boolean {
        if (!code.all { it.isDigit() }) return false
        if (code.length !in listOf(8, 12, 13, 14)) return false
        val digits = code.map { it - '0' }
        val check = digits.last()
        val body = digits.dropLast(1).reversed()
        var sum = 0
        body.forEachIndexed { i, d -> sum += if (i % 2 == 0) d * 3 else d }
        val expected = (10 - sum % 10) % 10
        return check == expected
    }
}

/**
 * Controla el escaneo continuo: confirma la lectura varias veces y evita
 * volver a disparar el mismo codigo una y otra vez (bucle infinito).
 */
class ScanGate {
    private var candidate: String? = null
    private var repeats = 0
    private var acceptedCode: String? = null
    private var acceptedAt = 0L

    /** Devuelve true solo cuando hay que lanzar la busqueda de ese codigo. */
    fun offer(code: String, cooldownMs: Long = 4000L): Boolean {
        // Confirmacion: el mismo codigo debe leerse 3 veces seguidas
        if (code == candidate) repeats++ else { candidate = code; repeats = 1 }
        if (repeats < 3) return false

        val now = System.currentTimeMillis()
        // Mismo producto que el ultimo aceptado: se ignora durante el tiempo de espera
        if (code == acceptedCode && now - acceptedAt < cooldownMs) return false

        acceptedCode = code
        acceptedAt = now
        repeats = 0
        candidate = null
        return true
    }

    fun reset() {
        candidate = null; repeats = 0; acceptedCode = null; acceptedAt = 0L
    }
}

object Beeper {
    private var tone: ToneGenerator? = null
    fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) { }
    }
}
