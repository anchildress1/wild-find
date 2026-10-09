# ONNX Runtime's JNI library looks up its Java classes and fields by name.
-keep class ai.onnxruntime.** { *; }

# PRD: release builds log nothing; the store's write-failure warnings are for debug builds only.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
