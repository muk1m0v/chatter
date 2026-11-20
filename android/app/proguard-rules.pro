# WebRTC's native library looks up Java classes and members by name.
# The current WebRTC AAR does not supply consumer rules for these JNI callbacks.
-keep class org.webrtc.** { *; }
