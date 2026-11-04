# JNI entry points are resolved by class + method name at runtime.
# JNI 入口按类名 + 方法名在运行时解析，必须保留。
-keep class com.zxhhyj.kap.android.KtxNative { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
