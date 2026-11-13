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
