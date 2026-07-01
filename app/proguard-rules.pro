# Add project specific ProGuard rules here.
# Activities and Services declared in AndroidManifest are auto-kept by R8.
# Other classes (FileUtils, SaveException, Settings, callbacks) are reachable
# from Manifest entries via call graph analysis.

# Keep annotations and signatures (required for Android runtime)
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Override proguard-android-optimize.txt: only strip verbose logs, keep debug/info
-assumenosideeffects class android.util.Log {
    public static int v(...);
}

# Don't warn about missing classes from optional dependencies
-dontwarn com.github.LJINBIN.**
-dontwarn androidx.documentfile.**
