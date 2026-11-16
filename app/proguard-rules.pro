# LeXia: shrink and rename the code in release builds.
# Keep line numbers so a crash report can be read with mapping.txt (kept in the private apk branch).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute L
# Android components named in the manifest are kept by the build tools automatically.

# NuvioTV's copied plugin files read and write their models with Moshi's reflection
# (KotlinJsonAdapterFactory), so the models, their fields and Kotlin's metadata must keep their names.
-keep class com.nuvio.tv.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,Signature,InnerClasses,EnclosingMethod

# Copied from NuvioTV's proguard-rules.pro: QuickJS calls back into these classes from native code,
# and Nuvio's plugin code reads models with Moshi, so R8 must not rename or remove them.
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class ** {
    @com.squareup.moshi.Json <fields>;
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass <init>(...);
}
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }
-keep class com.nuvio.tv.core.plugin.** { *; }
-keepclassmembers class com.nuvio.tv.core.plugin.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okio.** { *; }
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** { *; }
-dontwarn com.google.re2j.**
-dontwarn javax.script.**
-dontwarn okhttp3.internal.sse.**
-dontwarn org.jsoup.helper.Re2jRegex
# jsoup marks its packages with JSpecify annotations that are only needed while compiling.
-dontwarn org.jspecify.annotations.**
-keepattributes *Annotation*
