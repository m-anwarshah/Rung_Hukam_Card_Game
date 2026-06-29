# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.champ.rung.**$$serializer { *; }
-keepclassmembers class com.champ.rung.** {
    *** Companion;
}
-keepclasseswithmembers class com.champ.rung.** {
    kotlinx.serialization.KSerializer serializer(...);
}
