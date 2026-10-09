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

data class UnipackKeySoundEntry(
	val button: ButtonRef,
	val soundRelativePath: String,
	val loop: Int,
	val wormhole: Int,
)

data class UnipackReadResult(
	val info: UnipackInfo,
	val entries: List<UnipackKeySoundEntry>,
	val soundsDir: File,
	val keyLedDir: File?,
	val autoPlay: List<AutoPlayPress>,
	val autoPlayProgram: AutoPlayProgram? = null,
	val autoPlayRaw: String? = null,
	val warnings: List<String>,
)

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
		val autoPlayFile = actualRoot.listFiles()?.firstOrNull { it.isFile && it.name.equals("autoPlay", ignoreCase = true) }

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
				if (split.size < 4) return@forEach
				try {
					val c = split[0].toInt() - 1
					val x = split[1].toInt() - 1
					val y = split[2].toInt() - 1
					val soundURL = split[3]
					val loop = if (split.size >= 5) split[4].toInt() else 1
					val wormhole = if (split.size >= 6) {
						val rawWh = split[5].toInt()
						if (rawWh <= 0) -1 else rawWh - 1
					} else {
						-1
					}
					entries.add(
						UnipackKeySoundEntry(
							button = ButtonRef(chain = c, x = x, y = y),
							soundRelativePath = soundURL,
							loop = loop,
							wormhole = wormhole,
						)
					)
				} catch (e: Exception) {
					warnings.add("keySound: [$s] couldn't be parsed (${e.message})")
				}
			}
		}

		val autoPlayProgram = try {
			autoPlayFile?.let {
				AutoPlayReader.read(
					it,
					buttonX = buttonX.coerceAtLeast(8),
					buttonY = buttonY.coerceAtLeast(8),
					chainCount = chainCount.coerceIn(1, 24),
				)
			}
		} catch (e: Exception) {
			warnings.add("autoPlay: couldn't be parsed (${e.message}) -- falling back to keySound file order")
			null
		}
		val autoPlay = autoPlayProgram?.presses.orEmpty()
		val autoPlayRaw = autoPlayProgram?.rawText

		return UnipackReadResult(
			info = UnipackInfo(title, producerName, buttonX, buttonY, chainCount, squareButton, website),
			entries = entries,
			soundsDir = soundsDir,
			keyLedDir = keyLedDir,
			autoPlay = autoPlay,
			autoPlayProgram = autoPlayProgram,
			autoPlayRaw = autoPlayRaw,
			warnings = warnings,
		)
	}

	private fun hasPackFiles(dir: File): Boolean {
		val names = dir.listFiles()?.map { it.name.lowercase() } ?: return false
		return names.contains("info") || names.contains("keysound")
	}
}
