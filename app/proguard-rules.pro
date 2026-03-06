# Add project specific ProGuard rules here.
-keepclassmembers class com.holonav.app.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ARCore
-keep class com.google.ar.** { *; }
-dontwarn com.google.ar.**

# Sceneview
-keep class io.github.sceneview.** { *; }
-dontwarn io.github.sceneview.**
