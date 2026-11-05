# Ember TV release build: R8 shrinks and optimises the code, which makes Compose start and scroll
# noticeably faster on TV boxes. Most libraries ship their own rules; these cover the rest.

# Keep line numbers so crash logs (Settings > About > Crash log) point at the right place.
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# kotlinx.serialization: our @Serializable models and their generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class app.ember.tv.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.ember.tv.**$$serializer { *; }
-keepclassmembers class app.ember.tv.** {
    *** Companion;
}
-keepclasseswithmembers class app.ember.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# NewPipe Extractor (experimental in-app trailers) runs YouTube's player JavaScript with Rhino,
# which loads classes by name.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**

# Nextlib FFmpeg decoders: native methods and the classes they call back are looked up by name from the .so.
-keep class io.github.anilbeesetti.nextlib.** { *; }
# Media3 discovers the MPEG-H renderer by name; JNI also accesses decoder fields.
-keep class androidx.media3.decoder.mpegh.** { *; }
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# EncryptedSharedPreferences (Tink) and OkHttp optional platform classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# The screensaver is started by the system by class name (also kept by the manifest).
-keep class app.ember.tv.screensaver.EmberDream { *; }

# libmpv JNI observers are called by their original class/member names.
-keep class dev.jdtech.mpv.** { *; }

# SFTP selects crypto implementations by class name; SMB's provider registers algorithms reflectively.
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }
# SMBJ's event bus invokes annotated handlers, which need to survive release shrinking.
-keepattributes *Annotation*
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}

# Not offered by Ember: desktop Kerberos/SSH agents, Log4j or event-bus EL conditions.
# SMB uses NTLM password authentication; SFTP explicitly selects password/keyboard authentication.
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn javax.el.**
