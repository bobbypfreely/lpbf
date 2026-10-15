package com.bobbypfreely.lpbf.lightshow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Load / save user lightshow shortcuts as JSON under filesDir/shortcuts/.
 * Built-ins stay in [BuiltInPatterns]; this only handles **user** saves.
 */
object PatternShortcutStore {

	private fun dir(context: Context): File =
		File(context.applicationContext.filesDir, "shortcuts").also { it.mkdirs() }

	fun listUser(context: Context): List<String> {
		val d = dir(context)
		return d.listFiles()
			?.filter { it.isFile && it.name.endsWith(".json") }
			?.map { it.name.removeSuffix(".json") }
			?.sorted()
			.orEmpty()
	}

	fun load(context: Context, name: String): Pattern? {
		val f = File(dir(context), sanitize(name) + ".json")
		if (!f.exists()) return null
		return try {
			fromJson(JSONObject(f.readText()))
		} catch (_: Exception) {
			null
		}
	}

	fun save(context: Context, pattern: Pattern): Boolean {
		val name = sanitize(pattern.name)
		if (name.isBlank()) return false
		return try {
			val f = File(dir(context), "$name.json")
			f.writeText(toJson(pattern).toString())
			true
		} catch (_: Exception) {
			false
		}
	}

	fun delete(context: Context, name: String): Boolean {
		val f = File(dir(context), sanitize(name) + ".json")
		return f.exists() && f.delete()
	}

	/** Built-ins + user shortcuts (user names win on collision). */
	fun allAvailable(context: Context, gridWidth: Int = 8, gridHeight: Int = 8): List<Pattern> {
		val built = BuiltInPatterns.all(gridWidth, gridHeight)
		val user = listUser(context).mapNotNull { load(context, it) }
		val byName = LinkedHashMap<String, Pattern>()
		built.forEach { byName[it.name] = it }
		user.forEach { byName[it.name] = it }
		return byName.values.toList()
	}

	fun toJson(pattern: Pattern): JSONObject {
		val arr = JSONArray()
		for (k in pattern.keyframes) {
			arr.put(
				JSONObject()
					.put("t", k.t.toDouble())
					.put("x", k.x)
					.put("y", k.y)
					.put("on", k.on)
					.put("v", k.velocity)
					.apply { if (k.color != null) put("color", k.color) },
			)
		}
		return JSONObject()
			.put("name", pattern.name)
			.put("keyframes", arr)
	}

	fun fromJson(o: JSONObject): Pattern {
		val name = o.getString("name")
		val arr = o.getJSONArray("keyframes")
		val kf = ArrayList<Keyframe>(arr.length())
		for (i in 0 until arr.length()) {
			val e = arr.getJSONObject(i)
			kf.add(
				Keyframe(
					t = e.getDouble("t").toFloat().coerceIn(0f, 1f),
					x = e.getInt("x"),
					y = e.getInt("y"),
					on = e.getBoolean("on"),
					velocity = e.optInt("v", 100).coerceIn(1, 127),
					color = if (e.has("color")) e.getInt("color") else null,
				),
			)
		}
		return Pattern(name, kf)
	}

	private fun sanitize(name: String): String =
		name.trim()
			.replace(Regex("[^A-Za-z0-9_\\- ]"), "")
			.replace(' ', '_')
			.take(48)
}
