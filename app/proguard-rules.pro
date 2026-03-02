# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# ── App classes ──────────────────────────────────────────────────────────────

# Keep Room entities, DAOs, and Database
-keep class com.qrscanner.app.data.** { *; }

# Keep the Application class
-keep class com.qrscanner.app.QRScannerApp { *; }

# ── Room ─────────────────────────────────────────────────────────────────────

# Room generated _Impl classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ── ML Kit ───────────────────────────────────────────────────────────────────

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# ── ZXing ────────────────────────────────────────────────────────────────────

-keep class com.google.zxing.** { *; }

# ── Kotlin Coroutines ────────────────────────────────────────────────────────

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Kotlin serialization / reflection ────────────────────────────────────────

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }

# ── Compose ──────────────────────────────────────────────────────────────────

# Compose needs to keep @Composable functions reachable
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── CameraX ──────────────────────────────────────────────────────────────────

-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── General Android ──────────────────────────────────────────────────────────

# Keep FileProvider (referenced in manifest)
-keep class androidx.core.content.FileProvider { *; }

# Suppress warnings for missing optional dependencies
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
