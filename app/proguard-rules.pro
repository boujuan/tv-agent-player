# Media3 and OkHttp ship their own consumer rules.
# Keep the names in crash stack traces readable against the mapping file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Debug and verbose logging compiled out of release builds (nothing in them may carry a link anyway).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
