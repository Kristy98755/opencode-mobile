# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn kotlinx.serialization.**

-keep,includedescriptorclasses class kanami.isa.opencode.connect.**$$serializer { *; }
-keepclassmembers class kanami.isa.opencode.connect.** {
    *** Companion;
}
-keepclasseswithmembers class kanami.isa.opencode.connect.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
