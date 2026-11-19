// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.translate

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * DeepL ile İngilizce → Türkçe çeviri. ML Kit'ten çok daha doğal sonuç verir.
 * - Önceki altyazı "context" olarak gönderilir (çeviri bağlama uyar, bu kısım ücretlendirilmez).
 * - Karakter/yer adları (Glossary yer tutucuları) <x> etiketiyle çevrilmeden korunur.
 * Ücretsiz anahtarlar ":fx" ile biter ve api-free.deepl.com kullanır.
 */
class DeepLTranslator(private val settings: TranslateSettings)
{
	@Volatile var state = "hazır"
		private set
	@Volatile var charsUsed = 0
		private set

	val configured get() = settings.deeplKey.isNotBlank()

	// Ağ çağrıları ana okuma iş parçacığını bloke etmesin
	private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DeepL").also { it.isDaemon = true } }

	fun translate(text: String, context: String, onOk: (String) -> Unit, onFail: (String) -> Unit)
	{
		executor.execute {
			var conn: HttpURLConnection? = null
			try
			{
				val key = settings.deeplKey.trim()
				val host = if(key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
				conn = (URL("https://$host/v2/translate").openConnection() as HttpURLConnection).apply {
					requestMethod = "POST"
					doOutput = true
					connectTimeout = 4000
					readTimeout = 8000
					setRequestProperty("Authorization", "DeepL-Auth-Key $key")
					setRequestProperty("Content-Type", "application/json")
				}
				val body = JSONObject()
					.put("text", JSONArray().put(wrapPlaceholders(text)))
					.put("source_lang", "EN")
					.put("target_lang", "TR")
					.put("tag_handling", "xml")
					.put("ignore_tags", JSONArray().put("x"))
					.put("model_type", "prefer_quality_optimized")
				if(context.isNotBlank())
					body.put("context", context.take(300))
				conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

				val code = conn.responseCode
				if(code != 200)
				{
					val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
					val msg = "HATA $code: " + when(code)
					{
						403 -> "DeepL anahtarı geçersiz"
						456 -> "DeepL aylık kota doldu"
						429 -> "çok fazla istek"
						else -> err.take(80)
					}
					state = msg
					onFail(msg)
					return@execute
				}
				val resp = conn.inputStream.bufferedReader().use { it.readText() }
				val out = JSONObject(resp).getJSONArray("translations").getJSONObject(0).getString("text")
				charsUsed += text.length
				state = "hazır"
				onOk(unwrapPlaceholders(out))
			}
			catch(e: Exception)
			{
				val msg = "HATA: ${e.javaClass.simpleName}"
				state = msg
				onFail(msg)
			}
			finally
			{
				conn?.disconnect()
			}
		}
	}

	/** XML modu için özel karakterleri kaçırır, yer tutucuları <x> ile sarar (DeepL dokunmaz). */
	private fun wrapPlaceholders(text: String): String
	{
		val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
		return PLACEHOLDER.replace(escaped) { "<x>${it.value}</x>" }
	}

	private fun unwrapPlaceholders(text: String): String =
		text.replace("<x>", "").replace("</x>", "")
			.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

	companion object
	{
		// Glossary.protect() çıktısı: Zqx{n}Q
		private val PLACEHOLDER = Regex("Zqx\\d+Q")
	}
}
