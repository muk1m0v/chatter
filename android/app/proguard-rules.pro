# WebRTC's native library looks up Java classes and members by name.
# The current WebRTC AAR does not supply consumer rules for these JNI callbacks.
-keep class org.webrtc.** { *; }
# JNI_OnLoad calls this private bootstrap directly. Do not keep the unused
# setJniClassLoader path: the upstream AAR omits its generated JniZeroJni class.
-keep class org.jni_zero.JniZero {
    private static java.lang.Object[] init();
    private static void crashIfMultiplexingMisaligned(long,long);
}
-keep class org.jni_zero.CommonApis { *; }
