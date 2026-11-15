package app.rikka.savecopy;

import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import java.util.Locale;

public final class FileUtils {

    public static final String MIME_APK = "application/vnd.android.package-archive";
    public static final String MIME_OCTET_STREAM = "application/octet-stream";

    /** Extensions a package installer can consume. */
    private static final String[] INSTALLER_EXTENSIONS = {".apk", ".apks", ".xapk", ".apkm"};

    private FileUtils() {
    }

    public static String[] spiltFileName(String filename) {
        String name;
        String extension;
        int index = filename.lastIndexOf('.');
        if (index > 0) {
            name = filename.substring(0, index);
            extension = filename.substring(index);
        } else {
            name = filename;
            extension = "";
        }
        return new String[]{name, extension};
    }

    public static String getMimeTypeForFileName(@Nullable String fileName) {
        if (fileName == null) return MIME_OCTET_STREAM;

        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        if (extension == null || extension.isEmpty()) {
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) extension = fileName.substring(dot + 1);
        }
        if (extension == null || extension.isEmpty()) return MIME_OCTET_STREAM;

        extension = extension.toLowerCase(Locale.ROOT);
        // Some devices miss the apk mapping in MimeTypeMap; the installer path
        // depends on this type being exact.
        if (isInstallerExtension("." + extension)) return MIME_APK;

        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mimeType != null ? mimeType : MIME_OCTET_STREAM;
    }

    /** True when the name looks like something the package installer can open. */
    public static boolean isInstallerPackageFile(@Nullable String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : INSTALLER_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    public static boolean isApkMime(@Nullable String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase(Locale.ROOT);
        return lower.startsWith(MIME_APK) || lower.contains("x-apk");
    }

    /**
     * Guarantees an installer friendly extension when the server advertised the
     * APK mime type but the redirect URL and Content-Disposition did not.
     * Without this the file lands as {@code .bin} and later "opens" with a
     * viewer instead of the installer.
     */
    public static String ensureInstallerExtension(String fileName, @Nullable String mimeType) {
        if (fileName == null || fileName.isEmpty()) return "download.apk";
        if (!isApkMime(mimeType)) return fileName;
        if (isInstallerPackageFile(fileName)) return fileName;
        return fileName + ".apk";
    }

    private static boolean isInstallerExtension(String extension) {
        for (String candidate : INSTALLER_EXTENSIONS) {
            if (candidate.equals(extension)) return true;
        }
        return false;
    }
}
