package app.rikka.savecopy

import android.webkit.MimeTypeMap
import java.util.Locale

object FileUtils {

    const val MIME_APK = "application/vnd.android.package-archive"
    const val MIME_OCTET_STREAM = "application/octet-stream"

    /** Extensions a package installer can consume. */
    private val INSTALLER_EXTENSIONS = arrayOf(".apk", ".apks", ".xapk", ".apkm")

    /** MediaStore appends " (1)", " (2)"… without keeping the extension last. */
    private val MEDIASTORE_COLLISION_SUFFIX = Regex("\\s*\\(\\d+\\)$")

    private fun normalizedName(fileName: String): String =
        fileName.replace(MEDIASTORE_COLLISION_SUFFIX, "")

    fun splitFileName(filename: String): Array<String> {
        val index = filename.lastIndexOf('.')
        return if (index > 0) {
            arrayOf(filename.substring(0, index), filename.substring(index))
        } else {
            arrayOf(filename, "")
        }
    }

    fun getMimeTypeForFileName(fileName: String?): String {
        if (fileName == null) return MIME_OCTET_STREAM

        val normalized = normalizedName(fileName)
        var extension = MimeTypeMap.getFileExtensionFromUrl(normalized) ?: ""
        if (extension.isEmpty()) {
            val dot = normalized.lastIndexOf('.')
            if (dot > 0) extension = normalized.substring(dot + 1)
        }
        if (extension.isEmpty()) return MIME_OCTET_STREAM

        extension = extension.lowercase(Locale.ROOT)
        // Some devices miss the apk mapping in MimeTypeMap; the installer path
        // depends on this type being exact.
        if (isInstallerExtension(".$extension")) return MIME_APK

        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: MIME_OCTET_STREAM
    }

    /** True when the name looks like something the package installer can open. */
    fun isInstallerPackageFile(fileName: String?): Boolean {
        if (fileName == null) return false
        val lower = normalizedName(fileName).lowercase(Locale.ROOT)
        return INSTALLER_EXTENSIONS.any(lower::endsWith)
    }

    fun isApkMime(mimeType: String?): Boolean {
        if (mimeType == null) return false
        val lower = mimeType.lowercase(Locale.ROOT)
        return lower.startsWith(MIME_APK) || lower.contains("x-apk")
    }

    /**
     * Guarantees an installer friendly extension when the server advertised the
     * APK mime type but the redirect URL and Content-Disposition did not.
     * Without this the file lands as {@code .bin} and later "opens" with a
     * viewer instead of the installer.
     */
    fun ensureInstallerExtension(fileName: String?, mimeType: String?): String {
        if (fileName.isNullOrEmpty()) return "download.apk"
        if (!isApkMime(mimeType)) return fileName
        if (isInstallerPackageFile(fileName)) return fileName
        return "$fileName.apk"
    }

    private fun isInstallerExtension(extension: String): Boolean =
        INSTALLER_EXTENSIONS.any { it == extension }
}
