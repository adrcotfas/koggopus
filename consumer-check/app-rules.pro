# The instrumentation APK does not carry its own Kotlin runtime; it uses this app's copy,
# so that copy must not be shrunk. This does not affect how R8 treats the library.
-keep class kotlin.** { *; }
