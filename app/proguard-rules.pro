# Keep SQLCipher native bindings
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Room generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# PDFBox
-dontwarn org.apache.pdfbox.**
-keep class org.apache.pdfbox.** { *; }
-keep class com.tom_roush.** { *; }

# PDFBox' JPXFilter reaches for the optional JPEG2000 codec (gemalto jp2), which is not a
# dependency of pdfbox-android. Only a PDF carrying a JPEG2000 image would ever touch it, and
# Czech lab reports do not; without this rule R8 fails the release build on the missing class.
-dontwarn com.gemalto.jp2.**
