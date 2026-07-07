package io.github.afthabek.minimaldictionary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import org.json.JSONArray
import org.json.JSONObject

class DictionaryDb private constructor(private val db: SQLiteDatabase) {

    fun lookup(input: String): Lookup {
        val entries = mutableListOf<Entry>()
        db.rawQuery(
            "SELECT w.word, e.pos, e.data FROM words w JOIN entries e ON e.word_id = w.id " +
                "WHERE w.word_lc = lower(?)",
            arrayOf(input),
        ).use { c ->
            while (c.moveToNext()) {
                entries += parseEntry(c.getString(0), c.getString(1), inflate(c.getBlob(2)))
            }
        }
        val redirects = mutableListOf<Redirect>()
        db.rawQuery(
            "SELECT target, label FROM redirects WHERE form_lc = lower(?)",
            arrayOf(input),
        ).use { c ->
            while (c.moveToNext()) redirects += Redirect(c.getString(0), c.getString(1))
        }
        return Lookup(input, entries, redirects)
    }

    fun suggest(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        // Sorting ~100K single-letter matches costs >100 ms; bare index order
        // is instant, so only ORDER BY for prefixes of 2+ chars.
        val orderBy = if (prefix.length >= 2) "ORDER BY length(word), word " else ""
        val sql = "SELECT word FROM words WHERE word_lc GLOB ? || '*' " + orderBy + "LIMIT 25"
        val out = mutableListOf<String>()
        db.rawQuery(sql, arrayOf(escapeGlob(prefix.lowercase()))).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    fun license(): String =
        db.rawQuery("SELECT value FROM meta WHERE key = 'license'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else ""
        }

    companion object {
        const val DB_NAME = "dictionary.db"

        @Volatile
        private var instance: DictionaryDb? = null

        fun get(context: Context): DictionaryDb =
            instance ?: synchronized(this) {
                instance ?: DictionaryDb(
                    SQLiteDatabase.openDatabase(
                        context.getDatabasePath(DB_NAME).path,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ),
                ).also { instance = it }
            }

        fun isInstalled(context: Context): Boolean = context.getDatabasePath(DB_NAME).exists()

        private fun escapeGlob(s: String): String = buildString {
            for (ch in s) when (ch) {
                '*', '?', '[' -> append('[').append(ch).append(']')
                else -> append(ch)
            }
        }

        private fun inflate(blob: ByteArray): String {
            val inflater = Inflater()
            inflater.setInput(blob)
            val out = ByteArrayOutputStream(blob.size * 4)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
            inflater.end()
            return out.toString("UTF-8")
        }

        private fun parseEntry(word: String, pos: String, json: String): Entry {
            val obj = JSONObject(json)
            return Entry(
                word = word,
                pos = pos,
                senses = parseSenses(obj.optJSONArray("senses")),
                pronunciations = parsePronunciations(obj.optJSONArray("pronunciations")),
            )
        }

        private fun parseSenses(arr: JSONArray?): List<Sense> {
            if (arr == null) return emptyList()
            val senses = mutableListOf<Sense>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                senses += Sense(
                    definition = s.optString("definition"),
                    tags = stringList(s.optJSONArray("tags")),
                    examples = stringList(s.optJSONArray("examples")),
                    formOf = stringList(s.optJSONArray("form_of")),
                    links = parseLinks(s.optJSONArray("links")),
                    subsenses = parseSenses(s.optJSONArray("subsenses")),
                )
            }
            return senses
        }

        private fun parseLinks(arr: JSONArray?): List<Pair<String, String>> {
            if (arr == null) return emptyList()
            val links = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                links += pair.getString(0) to pair.getString(1)
            }
            return links
        }

        private fun parsePronunciations(arr: JSONArray?): List<Pronunciation> {
            if (arr == null) return emptyList()
            val prons = mutableListOf<Pronunciation>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                prons += Pronunciation(
                    ipa = p.optString("ipa"),
                    accents = stringList(p.optJSONArray("accents")),
                )
            }
            return prons
        }

        private fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return List(arr.length()) { arr.getString(it) }
        }
    }
}
