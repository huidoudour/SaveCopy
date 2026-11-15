package app.rikka.savecopy;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for the "share / open with" intent shapes Android produces. The same
 * URI digging used to be duplicated in {@code ChoiceActivity},
 * {@code SaveActivity} and {@code SaveService}; it now lives here once.
 */
public final class IntentDataUtils {

    private static final String TAG = "IntentDataUtils";

    /** Matches http(s) links inside shared plain text. */
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://[^\\s<>\"'{}|\\\\^`\\[\\]]+", Pattern.CASE_INSENSITIVE);

    /** Upper bound for scanning a shared text file for a link. */
    private static final int MAX_SHARED_TEXT_CHARS = 64 * 1024;

    private IntentDataUtils() {
    }

    public static boolean isHttpUri(@Nullable Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    public static boolean isLocalUri(@Nullable Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        return "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
    }

    /**
     * Resolves the first http(s) link carried by an intent, in the order real
     * share sheets use: data URI, ClipData, EXTRA_TEXT, EXTRA_STREAM.
     */
    @Nullable
    public static String findHttpUrl(@Nullable Intent intent) {
        if (intent == null) return null;

        String fromData = httpUrlOf(intent.getData());
        if (fromData != null) return fromData;

        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                String fromClip = httpUrlOf(clipData.getItemAt(i).getUri());
                if (fromClip != null) return fromClip;
            }
        }

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        String fromText = httpUrlOfText(text);
        if (fromText != null) return fromText;

        for (Uri uri : streamUris(intent)) {
            String fromStream = httpUrlOf(uri);
            if (fromStream != null) return fromStream;
        }
        return null;
    }

    /**
     * Returns the local URI carried by an intent, which is what the save flow
     * needs. {@code null} when the intent only carries plain text.
     */
    @Nullable
    public static Uri findLocalUri(@Nullable Intent intent) {
        if (intent == null) return null;

        Uri data = intent.getData();
        if (isLocalUri(data)) return data;

        for (Uri uri : collectUris(intent, false)) {
            if (isLocalUri(uri)) return uri;
        }
        return null;
    }

    /**
     * Collects the URIs a save request should copy.
     *
     * @param includeData whether an {@link Intent#ACTION_VIEW} data URI should be
     *                    included (true) or whether only share payloads are
     *                    relevant (false)
     */
    public static List<Uri> collectUris(@Nullable Intent intent, boolean includeData) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null) return uris;

        if (includeData && intent.getData() != null) {
            uris.add(intent.getData());
            return uris;
        }

        ClipData clipData = intent.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
            if (!uris.isEmpty()) return uris;
        }

        uris.addAll(streamUris(intent));
        return uris;
    }

    /** All URIs from EXTRA_STREAM, tolerating both single and list shapes. */
    public static List<Uri> streamUris(@Nullable Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (intent == null || !intent.hasExtra(Intent.EXTRA_STREAM)) return uris;

        Object raw = streamExtra(intent);
        if (raw instanceof Uri) {
            uris.add((Uri) raw);
        } else if (raw instanceof ArrayList) {
            for (Object item : (ArrayList<?>) raw) {
                if (item instanceof Uri) uris.add((Uri) item);
            }
        } else if (raw instanceof Parcelable[]) {
            for (Parcelable item : (Parcelable[]) raw) {
                if (item instanceof Uri) uris.add((Uri) item);
            }
        }
        return uris;
    }

    /**
     * Reads {@code EXTRA_STREAM} without assuming its shape: senders use a
     * single Uri, an {@code ArrayList<Uri>} or a {@code Parcelable[]}. Bundle's
     * type-safe getters need the shape up front (and only exist from API 33),
     * so the shape-agnostic getter is still the right tool here.
     */
    @SuppressWarnings("deprecation")
    @Nullable
    private static Object streamExtra(Intent intent) {
        Bundle extras = intent.getExtras();
        return extras != null ? extras.get(Intent.EXTRA_STREAM) : null;
    }

    /**
     * Copies the payload of a received intent into an outgoing intent without
     * dropping ClipData (which Android's share sheet prefers) or the read grant.
     */
    public static void copyShareData(@Nullable Intent from, Intent to) {
        if (from == null) return;

        ClipData clipData = from.getClipData();
        List<Uri> streamUris = streamUris(from);
        Uri data = from.getData();
        String type = from.getType();

        if (clipData != null) {
            to.setClipData(clipData);
            to.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (!streamUris.isEmpty()) {
            if (streamUris.size() == 1) {
                to.putExtra(Intent.EXTRA_STREAM, streamUris.get(0));
            } else {
                to.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(streamUris));
            }
        }

        if (data != null && type != null) {
            to.setDataAndType(data, type);
            to.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (data != null) {
            to.setData(data);
            to.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (type != null) {
            to.setType(type);
        }

        if (from.hasExtra(Intent.EXTRA_TEXT)) {
            to.putExtra(Intent.EXTRA_TEXT, from.getStringExtra(Intent.EXTRA_TEXT));
        }
    }

    /**
     * Reads the first http(s) link out of a shared text file, used by the
     * "download copy" entry point for {@code content://} documents.
     */
    @Nullable
    public static String readUrlFromDocument(Context context, @Nullable Uri uri) {
        if (context == null || uri == null) return null;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, read);
                    if (content.length() > MAX_SHARED_TEXT_CHARS) break;
                }
                return httpUrlOfText(content.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read a link from " + uri, e);
            return null;
        }
    }

    @Nullable
    private static String httpUrlOf(@Nullable Uri uri) {
        return isHttpUri(uri) ? uri.toString() : null;
    }

    @Nullable
    private static String httpUrlOfText(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        String trimmed = text.trim();
        if (trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8)) {
            // Reject values that carry extra prose after the link.
            Matcher matcher = URL_IN_TEXT.matcher(trimmed);
            if (matcher.find()) return stripTrailingPunctuation(matcher.group());
            return null;
        }
        Matcher matcher = URL_IN_TEXT.matcher(trimmed);
        return matcher.find() ? stripTrailingPunctuation(matcher.group()) : null;
    }

    private static String stripTrailingPunctuation(String url) {
        return url.replaceAll("[.,;!?]+$", "");
    }

    /** {@code Context#getParcelableExtra} split by API level to avoid warnings. */
    @Nullable
    @SuppressWarnings("deprecation")
    public static Uri getParcelableUri(@Nullable Intent intent, String key) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, Uri.class);
        }
        try {
            return intent.getParcelableExtra(key);
        } catch (ClassCastException e) {
            return null;
        }
    }
}
