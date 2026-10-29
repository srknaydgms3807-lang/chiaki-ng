// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Uygulama çökerse sessizce kapanmak yerine hatayı ekranda gösterir,
 * böylece hata telefondan kopyalanıp geliştiriciye iletilebilir.
 */
class TrApp: Application()
{
	override fun onCreate()
	{
		super.onCreate()
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try
			{
				val sw = StringWriter()
				error.printStackTrace(PrintWriter(sw))
				val report = buildString {
					append("Uygulama: ").append(BuildConfig.APPLICATION_ID)
						.append(" ").append(BuildConfig.VERSION_NAME).append('\n')
					append("Cihaz: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
						.append(" / Android ").append(Build.VERSION.RELEASE)
						.append(" / ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
					append("İş parçacığı: ").append(thread.name).append("\n\n")
					append(sw.toString())
				}
				startActivity(Intent(this, CrashActivity::class.java)
					.putExtra(CrashActivity.EXTRA_REPORT, report)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
				Process.killProcess(Process.myPid())
				exitProcess(10)
			}
			catch(e: Throwable)
			{
				previous?.uncaughtException(thread, error)
			}
		}
	}
}

class CrashActivity: Activity()
{
	companion object
	{
		const val EXTRA_REPORT = "report"
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val report = intent.getStringExtra(EXTRA_REPORT) ?: "Hata bilgisi yok"
		val pad = (16 * resources.displayMetrics.density).toInt()

		val title = TextView(this).apply {
			text = "PS5 Türkçe çöktü"
			textSize = 22f
			setTextColor(Color.WHITE)
			setPadding(0, 0, 0, pad / 2)
		}
		val hint = TextView(this).apply {
			text = "Aşağıdaki düğmeyle hatayı kopyalayıp Claude'a yapıştırın veya bu ekranın görüntüsünü gönderin."
			setTextColor(Color.LTGRAY)
			setPadding(0, 0, 0, pad)
		}
		val copy = Button(this).apply {
			text = "Hatayı kopyala"
			setOnClickListener {
				val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
				cm.setPrimaryClip(ClipData.newPlainText("PS5 Türkçe hata", report))
				Toast.makeText(this@CrashActivity, "Kopyalandı", Toast.LENGTH_SHORT).show()
			}
		}
		val body = TextView(this).apply {
			text = report
			textSize = 12f
			setTextColor(Color.WHITE)
			setTextIsSelectable(true)
			setPadding(0, pad, 0, pad)
		}
		val column = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(pad, pad * 2, pad, pad)
			addView(title)
			addView(hint)
			addView(copy)
			addView(body)
		}
		setContentView(ScrollView(this).apply {
			setBackgroundColor(Color.parseColor("#1E1E1E"))
			addView(column)
		})
	}
}
