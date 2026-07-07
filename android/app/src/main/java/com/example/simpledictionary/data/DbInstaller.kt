package com.example.simpledictionary.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DbInstaller {

    // GitHub Releases URL of the gzipped full database. When empty the
    // bundled test database is copied instead (development mode).
    const val DOWNLOAD_URL =
        "https://github.com/AfthabEK/Minimal-Dictionary/releases/download/db-v1/dictionary.db.gz"

    private const val TEST_DB_ASSET = "test_dictionary.db"

    sealed interface Progress {
        data object Connecting : Progress

        /** [fraction] is null when the total size is unknown (indeterminate). */
        data class Transferring(val fraction: Float?) : Progress

        data object Verifying : Progress
    }

    suspend fun install(context: Context, onProgress: (Progress) -> Unit) {
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(DictionaryDb.DB_NAME)
            dbFile.parentFile?.mkdirs()
            val tmp = File(dbFile.path + ".tmp")
            try {
                onProgress(Progress.Connecting)
                if (DOWNLOAD_URL.isEmpty()) {
                    copyTestDbFromAssets(context, tmp, onProgress)
                } else {
                    download(tmp, onProgress)
                }
                onProgress(Progress.Verifying)
                verify(tmp)
                if (!tmp.renameTo(dbFile)) throw IOException("Could not move database into place")
            } finally {
                tmp.delete()
            }
        }
    }

    private fun copyTestDbFromAssets(context: Context, dest: File, onProgress: (Progress) -> Unit) {
        val total = try {
            context.assets.openFd(TEST_DB_ASSET).use { it.length }
        } catch (_: IOException) {
            -1L // asset stored compressed; size unknown
        }
        context.assets.open(TEST_DB_ASSET).use { input ->
            copyStream(input, dest, total, onProgress)
        }
    }

    private fun download(dest: File, onProgress: (Progress) -> Unit) {
        val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong // compressed size
            val counting = CountingInputStream(conn.inputStream)
            // Progress tracks compressed bytes read off the wire while the
            // decompressed stream goes straight to disk.
            GZIPInputStream(counting).use { input ->
                copyStream(input, dest, total, onProgress) { counting.bytesRead }
            }
        } finally {
            conn.disconnect()
        }
    }

    private inline fun copyStream(
        input: InputStream,
        dest: File,
        total: Long,
        noinline onProgress: (Progress) -> Unit,
        transferred: () -> Long = { -1L },
    ) {
        dest.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            var lastReport = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                written += n
                val done = transferred().takeIf { it >= 0 } ?: written
                if (done - lastReport > 256 * 1024) {
                    lastReport = done
                    onProgress(
                        Progress.Transferring(
                            if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else null,
                        ),
                    )
                }
            }
        }
    }

    private fun verify(dbFile: File) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM meta WHERE key = 'word_count'", null).use { c ->
                if (!c.moveToFirst() || c.getString(0).toLongOrNull()?.takeIf { it > 0 } == null) {
                    throw IOException("Downloaded database failed verification")
                }
            }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var bytesRead = 0L
            private set

        override fun read(): Int =
            super.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bytesRead += it }
    }
}
