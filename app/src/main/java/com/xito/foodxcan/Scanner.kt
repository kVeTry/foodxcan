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

object Beeper {
    private var tone: ToneGenerator? = null
    fun beep() {
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) { /* sin sonido disponible */ }
    }
}
