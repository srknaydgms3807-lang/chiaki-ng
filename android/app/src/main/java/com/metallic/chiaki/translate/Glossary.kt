// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.translate

import android.content.Context

/**
 * Çeviride bozulmaması gereken isimler (karakter, yer).
 * assets/sozluk.txt: her satır "Isim" (aynen kalır) veya "English=Türkçe".
 */
class Glossary(private val terms: List<Pair<Regex, String>>)
{
	companion object
	{
		private val SPEAKER = Regex("^([A-Z][A-Za-z.'\\- ]{1,24}):\\s+(.+)$")
		private val SLOT = Regex("Zqx\\s*(\\d+)\\s*Q", RegexOption.IGNORE_CASE)

		fun load(context: Context): Glossary
		{
			val lines = try
			{
				context.assets.open("sozluk.txt").bufferedReader(Charsets.UTF_8).readLines()
			}
			catch(e: Exception) { emptyList() }
			val pairs = lines.map { it.trim() }
				.filter { it.isNotEmpty() && !it.startsWith("#") }
				.mapNotNull { line ->
					val en = line.substringBefore("=").trim()
					val tr = line.substringAfter("=", en).trim().ifEmpty { en }
					if(en.isEmpty()) null else en to tr
				}
				.sortedByDescending { it.first.length }   // "Saint Denis" önce, "Saint" sonra
				.map { (en, tr) -> Regex("(?<![A-Za-z])" + Regex.escape(en) + "(?![A-Za-z])") to tr }
			return Glossary(pairs)
		}

		/** "Dutch: We need money." -> ("Dutch", "We need money.") */
		fun splitSpeaker(text: String): Pair<String, String>
		{
			val m = SPEAKER.matchEntire(text) ?: return "" to text
			return m.groupValues[1] to m.groupValues[2]
		}

		fun restore(text: String, slots: List<String>): String =
			SLOT.replace(text) { m ->
				m.groupValues[1].toIntOrNull()?.let { slots.getOrNull(it) } ?: m.value
			}
	}

	/** İsimleri çeviriye girmeyecek işaretlerle değiştirir. */
	fun protect(text: String): Pair<String, List<String>>
	{
		val slots = ArrayList<String>()
		var out = text
		for((regex, tr) in terms)
		{
			if(!regex.containsMatchIn(out))
				continue
			out = regex.replace(out) {
				slots.add(tr)
				"Zqx${slots.size - 1}Q"
			}
		}
		return out to slots
	}
}
