# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn kotlinx.serialization.**

-keep,includedescriptorclasses class app.opencode.mobile.**$$serializer { *; }
-keepclassmembers class app.opencode.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class app.opencode.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
