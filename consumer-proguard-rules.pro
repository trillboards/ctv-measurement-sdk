# Consumer ProGuard rules for ctv-measurement-sdk.
# Keep the public TrillboardsMeasurement facade + JNA + UniFFI runtime classes
# so partner R8/ProGuard configurations don't strip the SDK's entry points.

-keep class com.trillboards.measurement.** { *; }
-keep class com.trillboards.discovery.** { *; }
-keep class uniffi.discovery.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
