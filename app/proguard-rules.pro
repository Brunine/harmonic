# Keep stack traces readable; the size gain from obfuscation is small for this app.
-dontobfuscate

## NewPipeExtractor (rules from the NewPipe project)
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.ocpsoft.prettytime.i18n.Resources* { *; }

## Rhino JavaScript engine (used by the extractor to decipher stream URLs)
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**

## Protobuf lite (extractor dependency)
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

## OkHttp / misc annotations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jspecify.**

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# The extractor pulls in several JVM-only optional dependencies. Missing optional classes
# must not fail the release build.
-ignorewarnings
