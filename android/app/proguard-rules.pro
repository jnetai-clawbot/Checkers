# Checkers keep rules
-keepattributes Signature, *Annotation*

# zxing / QR scanning
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# kotlinx coroutines (if used)
-dontwarn kotlinx.coroutines.**

# Gson (if used)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

-keepattributes InnerClasses