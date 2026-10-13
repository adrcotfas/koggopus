# JNI looks these up by name.
-keepclasseswithmembernames,includedescriptorclasses class io.github.adrcotfas.koggopus.** {
    native <methods>;
}
-keep class io.github.adrcotfas.koggopus.OggOpusException {
    <init>(int, java.lang.String);
}
