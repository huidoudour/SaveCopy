package app.rikka.savecopy;

import android.webkit.MimeTypeMap;

public class FileUtils {

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

    public static String getMimeTypeForFileName(String fileName) {
        if (fileName == null) return "application/octet-stream";
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        if (extension == null || extension.isEmpty()) {
            // Try our own extraction
            int dot = fileName.lastIndexOf('.');
            if (dot > 0) {
                extension = fileName.substring(dot + 1);
            }
        }
        if (extension != null && !extension.isEmpty()) {
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
            if (mimeType != null) return mimeType;
        }
        return "application/octet-stream";
    }
}
