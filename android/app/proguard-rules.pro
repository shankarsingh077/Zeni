# Add project specific ProGuard rules here.

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.zeni.voiceai.protocol.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
