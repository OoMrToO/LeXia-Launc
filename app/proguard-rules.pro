# LeXia: shrink and rename the code in release builds.
# Keep line numbers so a crash report can be read with mapping.txt (kept in the private apk branch).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute L
# Android components named in the manifest are kept by the build tools automatically.
