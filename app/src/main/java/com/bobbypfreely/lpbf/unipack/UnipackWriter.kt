package com.bobbypfreely.lpbf.unipack

import com.bobbypfreely.lpbf.marking.ButtonRef
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Assembles a real Unipack zip from already-rendered pieces (WAV bytes, keyLED text).
 * Mirrors UnipackReader's format exactly -- same "info"/"keySound" key names, same
 * 1-indexed chain/x/y on disk, same keyLed/ folder convention -- so anything written
 * here re-imports cleanly and plays on real Unipad/Launchpad hardware.
 *
 * Real packs leave the FIRST LINE of every plain-text file blank (info, keySound, and
 * each keyLED file) -- some parsers, including Unipad's own, don't recognize line 1, so
 * every text entry here is written with a leading blank line to match.
 */
object UnipackWriter {

	/** One fully-rendered cut, ready to drop into the zip as-is. [keyLedFileName] and
	 * [keyLedText] are both null when this cut has no lightshow. */
	data class SoundEntry(
		val button: ButtonRef,
		val soundFileName: String,   // e.g. "001.wav"
		val wavBytes: ByteArray,
		val keyLedFileName: String?,
		val keyLedText: String?,
	)

	fun write(
		output: OutputStream,
		title: String,
		producerName: String,
		buttonX: Int,
		buttonY: Int,
		chainCount: Int,
		entries: List<SoundEntry>,
	) {
		ZipOutputStream(output).use { zip ->
			zip.putNextEntry(ZipEntry("info"))
			zip.write(buildInfoText(title, producerName, buttonX, buttonY, chainCount).toByteArray(Charsets.UTF_8))
			zip.closeEntry()

			zip.putNextEntry(ZipEntry("keySound"))
			zip.write(buildKeySoundText(entries).toByteArray(Charsets.UTF_8))
			zip.closeEntry()

			entries.forEach { e ->
				zip.putNextEntry(ZipEntry("sounds/${e.soundFileName}"))
				zip.write(e.wavBytes)
				zip.closeEntry()
			}

			entries.forEach { e ->
				val name = e.keyLedFileName
				val text = e.keyLedText
				if (name != null && text != null) {
					zip.putNextEntry(ZipEntry("keyLed/$name"))
					zip.write(("\n" + text).toByteArray(Charsets.UTF_8))
					zip.closeEntry()
				}
			}
		}
	}

	private fun buildInfoText(title: String, producerName: String, buttonX: Int, buttonY: Int, chainCount: Int): String {
		val sb = StringBuilder("\n")
		sb.append("title=").append(title).append('\n')
		sb.append("producerName=").append(producerName).append('\n')
		sb.append("buttonX=").append(buttonX).append('\n')
		sb.append("buttonY=").append(buttonY).append('\n')
		sb.append("chain=").append(chainCount).append('\n')
		sb.append("squareButton=true\n")
		return sb.toString()
	}

	private fun buildKeySoundText(entries: List<SoundEntry>): String {
		val sb = StringBuilder("\n")
		entries.forEach { e ->
			val b = e.button
			sb.append(b.chain + 1).append(' ').append(b.x + 1).append(' ').append(b.y + 1)
				.append(' ').append(e.soundFileName).append('\n')
		}
		return sb.toString()
	}
}
