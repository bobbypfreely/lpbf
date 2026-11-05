package com.bobbypfreely.lpbf.lightshow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Opt-in “notes for the bot”.
 *
 * When the user agrees, a saved pattern can be written here with tags /
 * a short description. [LightsBot] checks this library before falling
 * back to built-in primitives.
 *
 * Storage: filesDir/bot_notes/<sanitized_name>.json
 */
object BotNoteStore {

	data class Note(
		val name: String,
		val tags: List<String>,
		val description: String,
		val pattern: Pattern,
	)

	private fun dir(context: Context): File =
		File(context.applicationContext.filesDir, "bot_notes").also { it.mkdirs() }

	private fun sanitize(name: String): String =
		name.trim().lowercase().replace(Regex("[^a-z0-9_\\-]+"), "_").take(64)

	fun list(context: Context): List<String> {
		return dir(context).listFiles()
			?.filter { it.isFile && it.name.endsWith(".json") }
			?.map { it.name.removeSuffix(".json") }
			?.sorted()
			.orEmpty()
	}

	fun load(context: Context, name: String): Note? {
		val f = File(dir(context), sanitize(name) + ".json")
		if (!f.exists()) return null
		return try {
			fromJson(JSONObject(f.readText()))
		} catch (_: Exception) {
			null
		}
	}

	/**
	 * Save a pattern as a bot note. Caller must only invoke this when
	 * the user has explicitly opted in.
	 */
	fun save(
		context: Context,
		name: String,
		pattern: Pattern,
		tags: List<String> = emptyList(),
		description: String = "",
	): Boolean {
		val key = sanitize(name)
		if (key.isBlank()) return false
		return try {
			val f = File(dir(context), "$key.json")
			f.writeText(toJson(name, pattern, tags, description).toString())
			true
		} catch (_: Exception) {
			false
		}
	}

	fun delete(context: Context, name: String): Boolean {
		val f = File(dir(context), sanitize(name) + ".json")
		return f.exists() && f.delete()
	}

	/**
	 * Very simple match: any tag or the name appears as a substring
	 * of the prompt (case-insensitive). First hit wins.
	 */
	fun match(context: Context, prompt: String): Note? {
		val p = prompt.lowercase()
		for (key in list(context)) {
			val note = load(context, key) ?: continue
			if (note.name.lowercase() in p) return note
			if (note.tags.any { it.lowercase() in p }) return note
			if (note.description.isNotBlank() && note.description.lowercase() in p) return note
		}
		return null
	}

	// ── JSON ──────────────────────────────────────────────────────────────

	private fun toJson(
		name: String,
		pattern: Pattern,
		tags: List<String>,
		description: String,
	): JSONObject {
		val kfArr = JSONArray()
		for (k in pattern.keyframes) {
			kfArr.put(
				JSONObject()
					.put("t", k.t.toDouble())
					.put("x", k.x)
					.put("y", k.y)
					.put("on", k.on)
					.put("v", k.velocity),
			)
		}
		val tagArr = JSONArray()
		tags.forEach { tagArr.put(it) }
		return JSONObject()
			.put("name", name)
			.put("tags", tagArr)
			.put("description", description)
			.put("keyframes", kfArr)
	}

	private fun fromJson(obj: JSONObject): Note {
		val name = obj.getString("name")
		val tags = mutableListOf<String>()
		obj.optJSONArray("tags")?.let { arr ->
			for (i in 0 until arr.length()) tags += arr.getString(i)
		}
		val description = obj.optString("description", "")
		val kfArr = obj.getJSONArray("keyframes")
		val keyframes = ArrayList<Keyframe>(kfArr.length())
		for (i in 0 until kfArr.length()) {
			val k = kfArr.getJSONObject(i)
			keyframes += Keyframe(
				t = k.getDouble("t").toFloat(),
				x = k.getInt("x"),
				y = k.getInt("y"),
				on = k.getBoolean("on"),
				velocity = k.optInt("v", 100),
			)
		}
		return Note(name, tags, description, Pattern(name, keyframes))
	}
}