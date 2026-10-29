// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.translate

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * ElevenLabs yapay zekâ seslendirmesi.
 * Ses ham PCM olarak akış halinde gelir ve ilk parça ulaşır ulaşmaz çalınır,
 * böylece cümlenin tamamının üretilmesi beklenmez (gecikme en aza iner).
 * Yeni altyazı gelince devam eden okuma kesilir.
 */
class ElevenLabsVoice(private val settings: TranslateSettings)
{
	companion object
	{
		private const val SAMPLE_RATE = 22050
	}

	@Volatile var state = "hazır"
		private set
	@Volatile var charsUsed = 0
		private set

	private val thread = HandlerThread("ElevenLabs").also { it.start() }
	private val handler = Handler(thread.looper)
	@Volatile private var generation = 0
	@Volatile private var connection: HttpURLConnection? = null

	private val track = AudioTrack.Builder()
		.setAudioAttributes(AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_GAME)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build())
		.setAudioFormat(AudioFormat.Builder()
			.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
			.setSampleRate(SAMPLE_RATE)
			.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
			.build())
		.setTransferMode(AudioTrack.MODE_STREAM)
		.setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(SAMPLE_RATE,
			AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, SAMPLE_RATE))
		.build()

	val configured get() = settings.elevenKey.isNotBlank()

	/**
	 * Metni seslendirir. Başarısız olursa (anahtar yanlış, kredi bitti, internet yok)
	 * [onFail] çağrılır; böylece telefonun kendi sesine geçilebilir.
	 */
	fun speak(text: String, onFail: () -> Unit)
	{
		val gen = ++generation
		connection?.disconnect()
		handler.post {
			if(gen != generation)
				return@post
			try
			{
				track.pause()
				track.flush()
			}
			catch(_: Exception) { }
			state = "üretiliyor"
			var conn: HttpURLConnection? = null
			try
			{
				val model = settings.elevenModel
				val url = URL("https://api.elevenlabs.io/v1/text-to-speech/" +
					settings.elevenVoiceId + "/stream?output_format=pcm_22050")
				conn = (url.openConnection() as HttpURLConnection).apply {
					requestMethod = "POST"
					doOutput = true
					connectTimeout = 4000
					readTimeout = 10000
					setRequestProperty("xi-api-key", settings.elevenKey.trim())
					setRequestProperty("Content-Type", "application/json")
				}
				connection = conn
				val voiceSettings = JSONObject()
					.put("stability", settings.elevenStability / 100.0)
					.put("similarity_boost", 0.8)
					.put("use_speaker_boost", true)
					.put("speed", (settings.voiceRate / 100.0).coerceIn(0.7, 1.2))
				val body = JSONObject()
					.put("text", text)
					.put("model_id", model)
					.put("voice_settings", voiceSettings)
				if(model.startsWith("eleven_multilingual"))
					voiceSettings.put("style", settings.elevenStyle / 100.0)  // duygu/vurgu
				else
					body.put("language_code", "tr")                        // hızlı model: Türkçeyi zorla
				conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

				val code = conn.responseCode
				if(code != 200)
				{
					val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
					state = "HATA $code: " + when(code)
					{
						401 -> "API anahtarı geçersiz"
						402, 429 -> if(err.contains("quota", true) || err.contains("credit", true))
							"kredi bitti" else "çok fazla istek / kota"
						404 -> "ses kimliği bulunamadı"
						else -> err.take(80)
					}
					if(gen == generation) onFail()
					return@post
				}

				charsUsed += text.length
				val buffer = ByteArray(4096)
				var leftover = -1        // tek kalan bayt (16 bit örneklerin yarısı)
				var started = false
				conn.inputStream.use { input ->
					while(gen == generation)
					{
						val n = input.read(buffer, if(leftover >= 0) 1 else 0,
							buffer.size - if(leftover >= 0) 1 else 0)
						if(n < 0) break
						var len = n
						if(leftover >= 0)
						{
							buffer[0] = leftover.toByte()
							len += 1
							leftover = -1
						}
						if(len % 2 == 1)
						{
							leftover = buffer[len - 1].toInt() and 0xFF
							len -= 1
						}
						if(len > 0)
						{
							track.write(buffer, 0, len)
							if(!started)
							{
								track.play()
								started = true
								state = "konuşuyor"
							}
						}
					}
				}
				if(gen == generation) state = "hazır"
			}
			catch(e: Exception)
			{
				if(gen == generation)
				{
					state = "HATA: ${e.javaClass.simpleName}"
					onFail()
				}
			}
			finally
			{
				conn?.disconnect()
			}
		}
	}

	fun stop()
	{
		generation++
		connection?.disconnect()
		handler.post {
			try
			{
				track.pause()
				track.flush()
			}
			catch(_: Exception) { }
		}
	}

	fun release()
	{
		stop()
		handler.post {
			track.release()
			thread.quitSafely()
		}
	}
}
