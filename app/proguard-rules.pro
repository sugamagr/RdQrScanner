# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Room entities
-keep class com.qrscanner.app.data.** { *; }




