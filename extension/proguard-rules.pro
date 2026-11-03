
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.copy.account.**$$serializer { *; }
-keepclassmembers class com.copy.account.** { *** Companion; }
-keepclasseswithmembers class com.copy.account.** { kotlinx.serialization.KSerializer serializer(...); }

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.#
-dontwarn org.conscrypt.#
