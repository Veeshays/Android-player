# Keep Media3 session + serialization + Coil; drop logging only.
-keep class androidx.media3.session.** { *; }
-keep class com.veeshays.pulse.models.** { *; }
-dontwarn coil3.**
