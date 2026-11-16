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
