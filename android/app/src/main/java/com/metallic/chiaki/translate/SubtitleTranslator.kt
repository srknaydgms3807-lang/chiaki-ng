// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// PS5 Türkçe Altyazı: oyun görüntüsündeki İngilizce altyazıyı telefonda okur,
// internetsiz olarak Türkçeye çevirir ve görüntünün üstüne yazar.

package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class SubtitleTranslator(
	private val context: Context,
	private val surfaceView: SurfaceView,
	private val overlay: TextView,
	private val settings: TranslateSettings
)
{
	companion object
	{
		private const val TAG = "TrAltyazi"
		private const val CHECK_INTERVAL_MS = 120L      // ucuz parlaklık kontrolü sıklığı
		private const val FALLBACK_OCR_MS = 1000L       // kontrol yazıyı kaçırsa bile bu aralıkla oku
		private const val EMPTY_READS_TO_CLEAR = 2
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val workerThread = HandlerThread("TrAltyaziWorker").also { it.start() }
	private val worker = Handler(workerThread.looper)
	private val workerExecutor = Executor { worker.post(it) }

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val translator: Translator = Translation.getClient(
		TranslatorOptions.Builder()
			.setSourceLanguage(TranslateLanguage.ENGLISH)
			.setTargetLanguage(TranslateLanguage.TURKISH)
			.build())
	private val glossary = Glossary.load(context)
	private val cache = HashMap<String, String>()

	@Volatile private var running = false
	@Volatile private var modelReady = false
	private var busy = false
	private var lastMask: BooleanArray? = null
	private var lastOcrTime = 0L
	private var shownSource = ""
	private var emptyReads = 0
	private var bitmap: Bitmap? = null

	fun start()
	{
		if(running || !settings.enabled)
			return
		running = true
		showText("Çeviri modeli hazırlanıyor…")
		// İlk kullanımda ~30 MB'lık İngilizce→Türkçe modeli indirilir; sonra tamamen internetsiz çalışır.
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener {
				modelReady = true
				showText("✓ Türkçe altyazı hazır")
				mainHandler.postDelayed({ if(shownSource.isEmpty()) showText("") }, 3000)
			}
			.addOnFailureListener {
				Log.e(TAG, "Model indirilemedi", it)
				showText("Çeviri modeli indirilemedi. İnternete bağlanıp akışı yeniden başlatın.")
			}
		worker.post(loop)
	}

	fun stop()
	{
		running = false
		worker.removeCallbacksAndMessages(null)
		mainHandler.post { overlay.visibility = View.GONE }
	}

	fun release()
	{
		stop()
		recognizer.close()
		translator.close()
		workerThread.quitSafely()
	}

	// ------------------------------------------------------------ ana döngü
	private val loop = object: Runnable
	{
		override fun run()
		{
			if(!running)
				return
			if(!busy)
				captureRegion()
			worker.postDelayed(this, CHECK_INTERVAL_MS)
		}
	}

	private fun captureRegion()
	{
		val holder = surfaceView.holder
		val frame = holder.surfaceFrame
		if(!holder.surface.isValid || frame.width() <= 0 || frame.height() <= 0)
			return
		val src = regionRect(frame.width(), frame.height())
		if(src.width() <= 0 || src.height() <= 0)
			return
		val bmp = bitmap?.takeIf { it.width == src.width() && it.height == src.height() }
			?: Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888).also { bitmap = it }
		busy = true
		try
		{
			PixelCopy.request(surfaceView, src, bmp, { result ->
				if(result == PixelCopy.SUCCESS)
					onFrame(bmp)
				else
					busy = false
			}, worker)
		}
		catch(e: Exception)
		{
			busy = false
		}
	}

	private fun regionRect(w: Int, h: Int): Rect
	{
		val top = (h * settings.regionTop / 100f).roundToInt()
		val bottom = (h * settings.regionBottom / 100f).roundToInt()
		val left = (w * settings.regionLeft / 100f).roundToInt()
		return Rect(left, top, w - left, max(bottom, top + 1))
	}

	// Ucuz ön kontrol: altyazı yazısı parlak beyazdır. Sadece parlak piksel deseni
	// değişince OCR çalışır; arka plandaki hareket (at, kar, kamera) yok sayılır.
	private fun onFrame(bmp: Bitmap)
	{
		val step = 3
		val cols = bmp.width / step
		val rows = bmp.height / step
		val pixels = IntArray(bmp.width * bmp.height)
		bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
		val mask = BooleanArray(cols * rows)
		var bright = 0
		val threshold = settings.brightness
		for(y in 0 until rows)
			for(x in 0 until cols)
			{
				val p = pixels[(y * step) * bmp.width + x * step]
				val on = Color.red(p) > threshold && Color.green(p) > threshold && Color.blue(p) > threshold
				mask[y * cols + x] = on
				if(on) bright++
			}
		val textVisible = bright > mask.size * 0.002
		val prev = lastMask
		var changed = prev == null || prev.size != mask.size
		if(!changed && prev != null)
		{
			var diff = 0
			for(i in mask.indices) if(mask[i] != prev[i]) diff++
			changed = diff > mask.size * 0.004
		}
		lastMask = mask

		val now = System.currentTimeMillis()
		val fallback = now - lastOcrTime > FALLBACK_OCR_MS
		if((textVisible && changed) || fallback)
		{
			lastOcrTime = now
			runOcr(bmp.copy(Bitmap.Config.ARGB_8888, false))
		}
		else
			busy = false
	}

	private fun runOcr(bmp: Bitmap)
	{
		recognizer.process(InputImage.fromBitmap(bmp, 0))
			.addOnSuccessListener(workerExecutor) { result ->
				val text = result.textBlocks
					.flatMap { it.lines }
					.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
					.joinToString(" ") { it.text.trim() }
					.replace(Regex("\\s+"), " ")
					.trim()
				onText(text)
			}
			.addOnFailureListener(workerExecutor) { busy = false }
	}

	private fun onText(text: String)
	{
		if(text.count { it.isLetter() } < 4)
		{
			emptyReads++
			if(emptyReads >= EMPTY_READS_TO_CLEAR && shownSource.isNotEmpty())
			{
				shownSource = ""
				showText("")
			}
			busy = false
			return
		}
		emptyReads = 0
		if(similar(text, shownSource) || !modelReady || text.length > 350)
		{
			busy = false
			return
		}
		cache[text]?.let {
			shownSource = text
			showText(it)
			busy = false
			return
		}
		val (speaker, body) = Glossary.splitSpeaker(text)
		val (protected, slots) = glossary.protect(body)
		translator.translate(protected)
			.addOnSuccessListener(workerExecutor) { raw ->
				var tr = Glossary.restore(raw, slots)
				if(speaker.isNotEmpty())
					tr = "$speaker: $tr"
				cache[text] = tr
				shownSource = text
				showText(tr)
				busy = false
			}
			.addOnFailureListener(workerExecutor) {
				Log.e(TAG, "Çeviri hatası", it)
				busy = false
			}
	}

	// ------------------------------------------------------------ gösterim
	private fun showText(text: String)
	{
		mainHandler.post {
			if(text.isEmpty())
			{
				overlay.visibility = View.GONE
				return@post
			}
			positionOverlay()
			overlay.text = text
			overlay.visibility = View.VISIBLE
		}
	}

	// Türkçe kutuyu, ekranda İngilizce altyazının olduğu bölgenin alt kenarına hizalar.
	private fun positionOverlay()
	{
		val sv = IntArray(2)
		val parent = IntArray(2)
		surfaceView.getLocationInWindow(sv)
		(overlay.parent as View).getLocationInWindow(parent)
		val w = surfaceView.width
		val h = surfaceView.height
		if(w == 0 || h == 0)
			return
		val r = regionRect(w, h)
		val lp = overlay.layoutParams
		lp.width = r.width()
		overlay.layoutParams = lp
		overlay.x = (sv[0] - parent[0] + r.left).toFloat()
		overlay.post {
			overlay.y = (sv[1] - parent[1] + r.bottom - overlay.height).toFloat()
		}
	}

	private fun similar(a: String, b: String): Boolean
	{
		if(a.isEmpty() || b.isEmpty()) return false
		if(a == b) return true
		val x = a.lowercase()
		val y = b.lowercase()
		if(abs(x.length - y.length) > max(x.length, y.length) * 0.2) return false
		return 1.0 - levenshtein(x, y).toDouble() / max(x.length, y.length) >= 0.85
	}

	private fun levenshtein(a: String, b: String): Int
	{
		var prev = IntArray(b.length + 1) { it }
		var cur = IntArray(b.length + 1)
		for(i in 1..a.length)
		{
			cur[0] = i
			for(j in 1..b.length)
				cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if(a[i - 1] == b[j - 1]) 0 else 1)
			val t = prev; prev = cur; cur = t
		}
		return prev[b.length]
	}
}
