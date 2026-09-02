package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class UnipackInfo(
	val title: String,
	val producerName: String,
	val buttonX: Int,
	val buttonY: Int,
	val chainCount: Int,
	val squareButton: Boolean,
	val website: String?,
)

data class UnipackKeySoundEntry(val button: ButtonRef, val soundRelativePath: String, val loop: Int, val wormhole: Int)

data class UnipackReadResult(
	val info: UnipackInfo,
	val entries: List<UnipackKeySoundEntry>,
	val soundsDir: File,
	val keyLedDir: File?,
	val warnings: List<String>,
)

/**
 * Reads a Unipack zip -- format extracted directly from Unipad's own UniPackFolder.kt
 * (github.com/bobbypfreely/unipad-android) so anything read here stays compatible with
 * real Unipad/Launchpad hardware:
 *   info file:       plain text "key=value" lines (title, producerName, buttonX,
 *                     buttonY, chain, squareButton, website)
 *   keySound file:    plain text, one mapping per line:
 *                     "chain x y soundFileName [loop] [wormhole]" (all 1-indexed on
 *                     disk, converted to 0-indexed here). The same chain/x/y CAN repeat
 *                     across multiple lines -- Unipad plays those as a queue, which is
 *                     exactly LPBF's own multi-trigger stacking, so it needs no special
 *                     handling here at all, just import each line as its own segment.
 *   sounds/           the actual audio files keySound's soundFileName refers to.
 *   keyLed/           optional -- one file per button mapping (matched to keySound
 *                     entries 1:1 in file order, see KeyLedReader), each containing that
 *                     cut's own lightshow. Parsed by the caller via KeyLedReader, not
 *                     here -- this class only locates the folder, since turning it into
 *                     Patterns needs each sound's decoded duration, which isn't known
 *                     until MultiClipImporter decodes it.
 *
 * info + keySound (the audio/mapping data) are always read. keyLedDir is exposed but
 * left unparsed for the same reason noted above -- if the pack has no keyLed folder,
 * this is simply null and callers skip lightshow import for it.
 */
object UnipackReader {

	fun extractZip(zipFile: File, targetDir: File) {
		targetDir.mkdirs()
		ZipInputStream(zipFile.inputStream()).use { zis ->
			var entry = zis.nextEntry
			while (entry != null) {
				val outFile = File(targetDir, entry.name)
				if (entry.isDirectory) {
					outFile.mkdirs()
				} else {
					outFile.parentFile?.mkdirs()
					outFile.outputStream().use { out -> zis.copyTo(out) }
				}
				zis.closeEntry()
				entry = zis.nextEntry
			}
		}
	}

	fun read(rootFolder: File): UnipackReadResult {
		// Some packs zip with an extra top-level folder wrapping everything -- if the
		// expected files aren't at the top level, step into the single child folder.
		val actualRoot = if (hasPackFiles(rootFolder)) {
			rootFolder
		} else {
			rootFolder.listFiles()?.singleOrNull { it.isDirectory && hasPackFiles(it) } ?: rootFolder
		}

		val infoFile = actualRoot.listFiles()?.firstOrNull { it.isFile && it.name.equals("info", ignoreCase = true) }
			?: throw IllegalArgumentException("Not a Unipack -- 'info' file missing")
		val keySoundFile = actualRoot.listFiles()?.firstOrNull { it.isFile && it.name.equals("keySound", ignoreCase = true) }
			?: throw IllegalArgumentException("Not a Unipack -- 'keySound' file missing")
		val soundsDir = actualRoot.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("sounds", ignoreCase = true) }
			?: throw IllegalArgumentException("Not a Unipack -- 'sounds' folder missing")
		val keyLedDir = actualRoot.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("keyLed", ignoreCase = true) }

		var title = ""
		var producerName = ""
		var buttonX = 0
		var buttonY = 0
		var chainCount = 0
		var squareButton = true
		var website: String? = null

		BufferedReader(InputStreamReader(infoFile.inputStream())).useLines { lines ->
			lines.forEach { raw ->
				val s = raw.trim()
				if (s.isEmpty()) return@forEach
				val split = s.split("=", limit = 2)
				if (split.size < 2) return@forEach
				val key = split[0].trim()
				val value = split[1].trim()
				when (key) {
					"title" -> title = value
					"producerName" -> producerName = value
					"buttonX" -> buttonX = value.toIntOrNull() ?: 0
					"buttonY" -> buttonY = value.toIntOrNull() ?: 0
					"chain" -> chainCount = value.toIntOrNull() ?: 0
					"squareButton" -> squareButton = value == "true"
					"website" -> website = value
				}
			}
		}

		val warnings = mutableListOf<String>()
		if (title.isEmpty()) warnings.add("info: title missing")
		if (producerName.isEmpty()) warnings.add("info: producerName missing")
		if (buttonX == 0 || buttonY == 0) warnings.add("info: buttonX/buttonY missing")
		if (chainCount !in 1..24) warnings.add("info: chain count ($chainCount) out of expected 1-24 range")

		val entries = mutableListOf<UnipackKeySoundEntry>()
		BufferedReader(InputStreamReader(keySoundFile.inputStream())).useLines { lines ->
			lines.forEach { raw ->
				val s = raw.trim()
				if (s.isEmpty()) return@forEach
				val split = s.trim().split("\\s+".toRegex())
				if (split.size <= 2) return@forEach
				try {
					val c = split[0].toInt() - 1
					val x = split[1].toInt() - 1
					val y = split[2].toInt() - 1
					val soundURL = split[3]
					val loop = if (split.size >= 5) split[4].toInt() - 1 else 0
					val wormhole = if (split.size >= 6) split[5].toInt() - 1 else -1
					entries.add(UnipackKeySoundEntry(ButtonRef(chain = c, x = x, y = y), soundURL, loop, wormhole))
				} catch (e: Exception) {
					warnings.add("keySound: [$s] couldn't be parsed (${e.message})")
				}
			}
		}

		return UnipackReadResult(
			info = UnipackInfo(title, producerName, buttonX, buttonY, chainCount, squareButton, website),
			entries = entries,
			soundsDir = soundsDir,
			keyLedDir = keyLedDir,
			warnings = warnings,
		)
	}

	private fun hasPackFiles(dir: File): Boolean {
		val names = dir.listFiles()?.map { it.name.lowercase() } ?: return false
		return names.contains("info") || names.contains("keysound")
	}
}
