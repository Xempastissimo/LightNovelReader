package com.xempastissimo.lightnovelreader.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.xempastissimo.lightnovelreader.data.network.CookieStore
import com.xempastissimo.lightnovelreader.data.network.HttpFailure
import com.xempastissimo.lightnovelreader.data.network.HttpFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Cover / illustration loader.
 *
 * Coil is not available in this build (see README), so this is a deliberately
 * small replacement: memory LRU + disk cache + `HttpURLConnection`, with the
 * source's hotlink protection handled by sending a `Referer` header.
 */
class ImageLoader(
    private val http: HttpFetcher,
    private val cookieStore: CookieStore,
    private val cacheDir: File,
    private val refererProvider: () -> String,
) {

    private val memory = object : LruCache<String, Bitmap>(memoryCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Returns a decoded bitmap, or null when the image cannot be fetched. */
    suspend fun load(url: String, maxWidth: Int = 0): Bitmap? {
        if (url.isBlank()) return null
        val key = cacheKey(url, maxWidth)
        memory.get(key)?.let { return it }

        val cached = readFromDisk(key, maxWidth)
        if (cached != null) {
            memory.put(key, cached)
            return cached
        }

        val bytes = try {
            http.getBytes(url, referer = refererProvider()).bytes
        } catch (_: HttpFailure) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null

        val bitmap = withContext(Dispatchers.Default) { decode(bytes, maxWidth) } ?: return null
        memory.put(key, bitmap)
        writeToDisk(key, bytes)
        return bitmap
    }

    /** Stores a bitmap that was produced elsewhere (for example a generated placeholder). */
    fun put(url: String, bitmap: Bitmap) {
        memory.put(cacheKey(url, 0), bitmap)
    }

    fun clearDiskCache() {
        memory.evictAll()
        runCatching { cacheDir.deleteRecursively() }
    }

    fun diskCacheSize(): Long =
        cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = if (maxWidth > 0 && bounds.outWidth > maxWidth) {
                Integer.highestOneBit(bounds.outWidth / maxWidth).coerceAtLeast(1)
            } else {
                1
            }
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    /**
     * A cached image, decoded at the width it is about to be *remembered* at.
     *
     * The width has to be passed on: the memory cache is keyed by (url, width), so a cover
     * decoded at full size and then filed under the thumbnail's key would occupy the heap —
     * and the LRU's accounting — as the original plate for the rest of the session, once per
     * distinct cover on screen.
     */
    private fun readFromDisk(key: String, maxWidth: Int): Bitmap? {
        val file = File(cacheDir, key)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val bytes = file.readBytes()
            decode(bytes, maxWidth)
        }.getOrNull()
    }

    private fun writeToDisk(key: String, bytes: ByteArray) {
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, key).writeBytes(bytes)
        }
    }

    private fun cacheKey(url: String, maxWidth: Int): String {
        val digest = MessageDigest.getInstance("MD5").digest((url + "#" + maxWidth).toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return "$name.img"
    }

    private companion object {
        fun memoryCacheSize(): Int {
            val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            return (maxKb / 8).coerceAtLeast(4 * 1024)
        }
    }
}
