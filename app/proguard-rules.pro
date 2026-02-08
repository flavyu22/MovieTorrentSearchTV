# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\mark1\AppData\Local\Android\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Optimization settings for TV
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Retrofit & OkHttp
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# GSON
-keep class com.google.gson.** { *; }
-keep class com.example.movietorrentsearchtv.model.** { *; }

# Coil
-keep class coil.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# Keep your data models!
-keepclassmembers class com.example.movietorrentsearchtv.model.** { *; }
