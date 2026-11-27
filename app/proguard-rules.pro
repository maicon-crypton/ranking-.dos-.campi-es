# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keep class com.aistudio.santrirank.mcdfbx.AndroidAppBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.TypeConverter { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep class * extends dagger.hilt.android.HiltViewModel { *; }

# Firebase
-keep class com.google.firebase.** { *; }

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}

# Kotlinx Serialization
-keep class kotlinx.serialization.** { *; }

# Coroutines
-keep class kotlinx.coroutines.** { *; }

# Coil
-keep class coil.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Material3
-keep class com.google.android.material.** { *; }

# Keep model classes
-keep class com.aistudio.santrirank.mcdfbx.model.** { *; }
-keep class com.aistudio.santrirank.mcdfbx.ai.** { *; }
-keep class com.aistudio.santrirank.mcdfbx.data.** { *; }
-keep class com.aistudio.santrirank.mcdfbx.security.** { *; }
-keep class com.aistudio.santrirank.mcdfbx.export.** { *; }

# Keep ViewModel classes
-keep class com.aistudio.santrirank.mcdfbx.ui.**ViewModel { *; }