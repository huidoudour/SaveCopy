package app.rikka.savecopy

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Helpers for the "share / open with" intent shapes Android produces.
 */
object IntentDataUtils {

    private const val TAG = "IntentDataUtils"

    /** Matches http(s) links inside shared plain text. */
    private val URL_IN_TEXT = Regex(
        "https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+",
        RegexOption.IGNORE_CASE,
    )

    /** Upper bound for scanning a shared text file for a link. */
    private const val MAX_SHARED_TEXT_CHARS = 64 * 1024

    fun isHttpUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme
        return "http".equals(scheme, ignoreCase = true) ||
            "https".equals(scheme, ignoreCase = true)
    }

    fun isLocalUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme
        return "content".equals(scheme, ignoreCase = true) ||
            "file".equals(scheme, ignoreCase = true)
    }

    /**
     * Resolves the first http(s) link carried by an intent, in the order real
     * share sheets use: data URI, ClipData, EXTRA_TEXT, EXTRA_STREAM.
     */
    fun findHttpUrl(intent: Intent?): String? {
        if (intent == null) return null

        httpUrlOf(intent.data)?.let { return it }

        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                httpUrlOf(clipData.getItemAt(i).uri)?.let { return it }
            }
        }

        httpUrlOfText(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { return it }

        for (uri in streamUris(intent)) {
            httpUrlOf(uri)?.let { return it }
        }
        return null
    }

    /**
     * Returns the local URI carried by an intent, which is what the save flow
     * needs. {@code null} when the intent only carries plain text.
     */
    fun findLocalUri(intent: Intent?): Uri? {
        if (intent == null) return null

        val data = intent.data
        if (isLocalUri(data)) return data

        return collectUris(intent, false).firstOrNull(::isLocalUri)
    }

    /**
     * Collects the URIs a save request should copy.
     *
     * @param includeData whether an {@link Intent#ACTION_VIEW} data URI should be
     *                    included (true) or whether only share payloads are
     *                    relevant (false)
     */
    fun collectUris(intent: Intent?, includeData: Boolean): List<Uri> {
        val uris = ArrayList<Uri>()
        if (intent == null) return uris

        if (includeData && intent.data != null) {
            uris.add(intent.data!!)
            return uris
        }

        val clipData = intent.clipData
        if (clipData != null && clipData.itemCount > 0) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let(uris::add)
            }
            if (uris.isNotEmpty()) return uris
        }

        uris.addAll(streamUris(intent))
        return uris
    }

    /** All URIs from EXTRA_STREAM, tolerating both single and list shapes. */
    @Suppress("DEPRECATION")
    fun streamUris(intent: Intent?): List<Uri> {
        val uris = ArrayList<Uri>()
        if (intent == null || !intent.hasExtra(Intent.EXTRA_STREAM)) return uris

        when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> uris.add(raw)
            is ArrayList<*> -> raw.forEach { if (it is Uri) uris.add(it) }
            is Array<*> -> raw.forEach { if (it is Uri) uris.add(it) }
        }
        return uris
    }

    /**
     * Copies the payload of a received intent into an outgoing intent without
     * dropping ClipData (which Android's share sheet prefers) or the read grant.
     */
    fun copyShareData(from: Intent?, to: Intent) {
        if (from == null) return

        val clipData = from.clipData
        val streamUris = streamUris(from)
        val data = from.data
        val type = from.type

        if (clipData != null) {
            to.clipData = clipData
            to.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (streamUris.isNotEmpty()) {
            if (streamUris.size == 1) {
                to.putExtra(Intent.EXTRA_STREAM, streamUris[0])
            } else {
                to.putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM, ArrayList<Parcelable>(streamUris),
                )
            }
        }

        if (data != null && type != null) {
            to.setDataAndType(data, type)
            to.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (data != null) {
            to.data = data
            to.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (type != null) {
            to.type = type
        }

        if (from.hasExtra(Intent.EXTRA_TEXT)) {
            to.putExtra(Intent.EXTRA_TEXT, from.getStringExtra(Intent.EXTRA_TEXT))
        }
    }

    /**
     * Reads the first http(s) link out of a shared text file, used by the
     * "download copy" entry point for {@code content://} documents.
     */
    fun readUrlFromDocument(context: Context?, uri: Uri?): String? {
        if (context == null || uri == null) return null
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                val content = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    content.append(buffer, 0, read)
                    if (content.length > MAX_SHARED_TEXT_CHARS) break
                }
                return httpUrlOfText(content.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read a link from $uri", e)
            return null
        }
    }

    private fun httpUrlOf(uri: Uri?): String? = if (isHttpUri(uri)) uri?.toString() else null

    private fun httpUrlOfText(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val match = URL_IN_TEXT.find(text.trim()) ?: return null
        return stripTrailingPunctuation(match.value)
    }

    private fun stripTrailingPunctuation(url: String): String =
        url.replace(Regex("[.,;!?]+$"), "")

    /** {@code Intent#getParcelableExtra} split by API level to avoid warnings. */
    @Suppress("DEPRECATION")
    fun getParcelableUri(intent: Intent?, key: String): Uri? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            try {
                intent.getParcelableExtra(key)
            } catch (e: ClassCastException) {
                null
            }
        }
    }
}
