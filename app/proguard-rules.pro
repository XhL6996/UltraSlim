# JNI 按名字 FindClass 并调用构造器（native/jni/ultraslim_jni.cpp），只保留名字与构造器。
# UltraHdrBridge 的方法是 external，AGP 已自动保留，无需通配 keep。
-keep class app.ultraslim.bridge.ProbeResult { <init>(...); }
-keep class app.ultraslim.bridge.TranscodeResult { <init>(...); }
