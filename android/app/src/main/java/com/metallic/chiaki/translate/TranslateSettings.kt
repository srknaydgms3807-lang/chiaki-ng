// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.translate

import android.content.Context
import androidx.preference.PreferenceManager

/** Ayarlar ekranındaki "Türkçe altyazı" bölümünün değerleri. */
class TranslateSettings(context: Context)
{
	companion object
	{
		const val KEY_ENABLED = "tr_subtitles_enabled"
		const val KEY_TOP = "tr_region_top"
		const val KEY_BOTTOM = "tr_region_bottom"
		const val KEY_SIDE = "tr_region_side"
		const val KEY_BRIGHTNESS = "tr_brightness"
		const val KEY_TEXT_SIZE = "tr_text_size"
	}

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	val enabled get() = prefs.getBoolean(KEY_ENABLED, true)

	/** Altyazı şeridinin ekran yüksekliğine göre üst/alt sınırı (%). RDR2 için varsayılanlar. */
	val regionTop get() = prefs.getInt(KEY_TOP, 66).coerceIn(0, 99)
	val regionBottom get() = prefs.getInt(KEY_BOTTOM, 86).coerceIn(regionTop + 1, 100)

	/** Soldan ve sağdan kırpılacak pay (%). */
	val regionLeft get() = prefs.getInt(KEY_SIDE, 15).coerceIn(0, 45)

	/** Altyazı yazısının parlaklık eşiği (0-255). */
	val brightness get() = prefs.getInt(KEY_BRIGHTNESS, 185).coerceIn(100, 250)

	val textSize get() = prefs.getInt(KEY_TEXT_SIZE, 22).coerceIn(12, 48)
}
