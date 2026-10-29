// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.translate

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Türkçe çeviriyi telefonun metin okuma motoruyla sesli okur (internetsiz).
 * Yeni altyazı gelince önceki okumayı keser, böylece konuşmanın gerisinde kalmaz.
 */
class Narrator(context: Context, private val settings: TranslateSettings): TextToSpeech.OnInitListener
{
	@Volatile var state = "başlatılıyor"
		private set
	@Volatile private var ready = false
	private var counter = 0
	private val tts = TextToSpeech(context.applicationContext, this)

	override fun onInit(status: Int)
	{
		if(status != TextToSpeech.SUCCESS)
		{
			state = "metin okuma motoru açılamadı"
			return
		}
		val result = tts.setLanguage(Locale("tr", "TR"))
		if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
		{
			state = "Türkçe ses yok (Ayarlar > Metin okuma'dan indirin)"
			return
		}
		tts.setAudioAttributes(AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build())
		tts.setSpeechRate(settings.voiceRate / 100f)
		ready = true
		state = "hazır"
	}

	fun speak(text: String)
	{
		if(!ready || !settings.voiceEnabled || text.isBlank())
			return
		tts.setSpeechRate(settings.voiceRate / 100f)
		tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr-${counter++}")
	}

	fun stop()
	{
		if(ready) tts.stop()
	}

	fun shutdown()
	{
		ready = false
		tts.stop()
		tts.shutdown()
	}
}
