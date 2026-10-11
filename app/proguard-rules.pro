# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep useful crash traces in release builds without exposing source file names.
-keepattributes SourceFile,LineNumberTable,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,Signature,InnerClasses,EnclosingMethod

# OkHttp/Okio and Android platform APIs emit harmless optional-class warnings on some toolchains.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Spotify App Remote is shipped as a local AAR and uses callbacks/reflection internally.
-keep class com.spotify.** { *; }
-dontwarn com.spotify.**

# Preserve app components referenced from AndroidManifest.
-keep class com.example.voicebrainlive.** extends android.app.Activity { *; }
-keep class com.example.voicebrainlive.** extends android.app.Service { *; }
-keep class com.example.voicebrainlive.** extends android.content.BroadcastReceiver { *; }
-keep class com.example.voicebrainlive.** extends android.content.ContentProvider { *; }
