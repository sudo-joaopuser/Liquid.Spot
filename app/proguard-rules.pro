-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
    public *** on*(...);
}

-dontwarn io.github.libxposed.api.**

-keep class org.lsposed.hiddenapibypass.** { *; }

-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

-keep class app.liquidspot.xposed.features.player.lyrics.ai.NativeAudio {
    native <methods>;
}
