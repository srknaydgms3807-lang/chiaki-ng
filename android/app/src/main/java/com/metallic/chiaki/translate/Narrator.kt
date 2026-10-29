// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.translate

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Türkçe çeviriyi telefonun metin okuma motoruyla sesli okur.
 * - Telefondaki Türkçe sesler arasından seçim (Ayarlar > Ses numarası)
 * - Ses kalınlığı (düşük = daha kalın, sert erkek sesi)
 * - Ünlemli cümleler biraz daha yüksek ve hızlı, soru cümleleri biraz daha yavaş okunur
 * Yeni altyazı gelince önceki okumayı keser, böylece konuşmanın gerisinde kalmaz.
 */
class Narrator(context: Context, private val settings: TranslateSettings): TextToSpeech.OnInitListener
{
	@Volatile var state = "başlatılıyor"
		private set
	@Volatile private var ready = false
	private var counter = 0
	private var voices: List<Voice> = emptyList()
	private var appliedVoiceIndex = -1
	private val tts = TextToSpeech(context.applicationContext, this)
	private val eleven = ElevenLabsVoice(context, settings)

	/** Teşhis satırı için: hangi motor, durum ve harcanan karakter. */
	val summary: String
		get() = if(useEleven)
			"ElevenLabs: ${eleven.state} (${eleven.charsUsed} krk, önbellek ${eleven.cacheHits}) / yedek: $state"
		else state

	private val useEleven get() = settings.voiceEngine == "elevenlabs" && eleven.configured

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
		voices = try
		{
			tts.voices.orEmpty()
				.filter { it.locale.language == "tr" }
				.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
		}
		catch(e: Exception) { emptyList() }
		ready = true
		applyVoice()
	}

	/** Ayarlardaki ses numarasını uygular (1'den başlar, ses sayısını aşarsa başa döner). */
	private fun applyVoice()
	{
		if(voices.isEmpty())
		{
			state = "hazır (varsayılan ses)"
			return
		}
		val index = (settings.voiceIndex - 1).mod(voices.size)
		if(index != appliedVoiceIndex)
		{
			tts.voice = voices[index]
			appliedVoiceIndex = index
		}
		val v = voices[index]
		state = "ses ${index + 1}/${voices.size}: ${v.name}" + if(v.isNetworkConnectionRequired) " (internet)" else ""
	}

	fun speak(text: String)
	{
		if(!settings.voiceEnabled || text.isBlank())
			return
		if(useEleven)
		{
			if(ready) tts.stop()
			// ElevenLabs başarısız olursa telefonun kendi sesiyle oku
			eleven.speak(text) { speakWithPhone(text) }
			return
		}
		speakWithPhone(text)
	}

	private fun speakWithPhone(text: String)
	{
		if(!ready)
			return
		applyVoice()
		var pitch = settings.voicePitch / 100f
		var rate = settings.voiceRate / 100f
		val t = text.trimEnd()
		when
		{
			t.endsWith("!") -> { pitch *= 1.08f; rate *= 1.06f }   // heyecan, bağırma
			t.endsWith("?") -> { rate *= 0.96f }                   // soru: biraz daha yavaş
			t.endsWith("...") || t.endsWith("…") -> { rate *= 0.92f; pitch *= 0.97f }  // duraksama
		}
		tts.setPitch(pitch)
		tts.setSpeechRate(rate)
		tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr-${counter++}")
	}

	fun stop()
	{
		eleven.stop()
		if(ready) tts.stop()
	}

	fun shutdown()
	{
		ready = false
		eleven.release()
		tts.stop()
		tts.shutdown()
	}
}
