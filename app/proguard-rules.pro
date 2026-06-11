# YuMark ProGuard Rules

# Keep domain models
-keep class com.yumark.app.domain.model.** { *; }

# Keep Room entities
-keep class com.yumark.app.data.local.db.entity.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# WebView JavaScript interface
-keep class com.yumark.app.core.webview.JsBridge { *; }
-keepclassmembers class com.yumark.app.core.webview.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
