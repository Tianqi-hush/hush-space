############################################
# 1. CORE STRUCTURE & REPACKAGING
# Repackage obfuscated classes into the 'enc' folder.
############################################
-dontshrink
-repackageclasses 'com.hush.enc'
-allowaccessmodification
-useuniqueclassmembernames
-dontusemixedcaseclassnames
-adaptclassstrings

############################################
# 2. DANGEROUS FOLDERS - DO NOT TOUCH
# 'env' folder contains deep reflection and environment
# variables that crash if touched. Keep entirely intact.
############################################
-keep class com.hush.env.** { *; }
-keep class black.** { *; }
-keep class top.** { *; }
-keep class android.** { *; }
-keep class com.android.** { *; }
-keep class net_62v.** { *; }

############################################
# 3. GLOBAL CLASS OBFUSCATION
# Allow class names to be obfuscated and moved to 'enc',
# BUT preserve their existence (no shrinking).
# Note: com.hush.activation is excluded so it can be fully shrunk and heavily obfuscated.
############################################
-keep,allowobfuscation class !com.hush.activation.**, !com.hush.app.**, !com.hush.entity.**, com.hush.** { }

# KEEP HushCore COMPLETELY INTACT ALWAYS
-keep class com.hush.HushCore { *; }

############################################
# 4. METHOD & FIELD PROTECTION (PREVENT CRASHES)
# "dont enc methods and arryasy ncouse it ahs has map and ir carsh"
# Keep methods and fields for the core project to prevent 
# reflection/HashMap crashes, but exclude 'activation' so it encrypts fully.
############################################
-keep class com.hush.app.** { *; }
-keepclassmembers class com.hush.core.** { *; }
-keep class com.hush.entity.** { *; }
-keepclassmembers class com.hush.fake.** { *; }
-keepclassmembers class com.hush.jnihook.** { *; }
-keepclassmembers class com.hush.proxy.** { *; }
-keepclassmembers class com.hush.utils.** { *; }


############################################
# 6. FAST STARTUP OPTIMIZATION & DEAD CODE REMOVAL
############################################
-assumenosideeffects class com.hush.HushCore {
    private void initNotificationManager();
    private static java.lang.String getProcessName(android.content.Context);
    public static boolean is64Bit();
    private void startLogcat();
}
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-assumenosideeffects class com.hush.utils.Slog {
    public static void d(...);
    public static void i(...);
    public static void v(...);
}
-assumenosideeffects class java.lang.String {
    public java.lang.String toLowerCase();
    public java.lang.String toUpperCase();
    public boolean contains(java.lang.CharSequence);
    public boolean endsWith(java.lang.String);
}

############################################
# 7. JNI / NATIVE CODE PROTECTION
# Hardcoded JNI targets must remain perfectly intact for C++
############################################
-keep class com.hush.jnihook.jni.JniHook { *; }
-keepclassmembers class com.hush.jnihook.jni.JniHook {
    public static int NATIVE_OFFSET;
    public static int NATIVE_HANDLE;
    public static int NATIVE_PTR;
}

-keepnames class com.hush.core.VNative
-keepclasseswithmembernames class com.hush.core.VNative {
    native <methods>;
}
-keepclassmembers class com.hush.core.VNative {
    public static int getApiLevel();
    public static int getCallingUid(int);
    public static java.lang.String redirectPath(java.lang.String);
    public static java.io.File redirectPath(java.io.File);
    public static long[] loadEmptyDex();
    public static <fields>;
}

-keepclasseswithmembernames class * {
    native <methods>;
}

############################################
# JNI / C++ REFLECTION KEEP RULES
# These classes and methods are accessed from C++ via FindClass / GetMethodID
# They MUST NOT be renamed, otherwise the app will crash after encryption.
############################################
-keep class com.hush.jnihook.MethodUtils { *; }
-keep class com.hush.fake.service.SystemPropertyHook { *; }
-keep class com.hush.activation.SdkCore {
    native <methods>;
    static java.lang.String devId(android.content.Context);
    static java.lang.String _t(java.lang.String,java.lang.String,java.lang.String);
    static void _w(android.content.Context,byte[]);
    static byte[] _r(android.content.Context);
}

############################################
# 8. AIDL INTERFACES & STUBS
############################################
-keep interface * extends android.os.IInterface { *; }
-keep class * implements android.os.IInterface { *; }
-keep class **$Stub { *; }
-keep class **$Stub$Proxy { *; }
-keep class **$Proxy { *; }

############################################
# 9. MANIFEST DECLARED COMPONENTS
# Android requires exact class names to boot processes.
# These specific classes cannot have their names obfuscated.
############################################
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

-keep class com.hush.proxy.ProxyWebActivity { *; }
-keep class com.hush.proxy.HostIntentInterceptActivity { *; }
-keep class com.hush.proxy.ProxyBroadcastReceiver { *; }
-keep class com.hush.proxy.ProxyVpnService { *; }
-keep class com.hush.core.system.DaemonService { *; }
-keep class com.hush.core.system.DaemonService$DaemonInnerService { *; }
-keep class com.hush.app.LauncherActivity { *; }
-keep class com.hush.core.system.SystemCallProvider { *; }
-keep class com.hush.fake.provider.FileProvider { *; }
-keep class com.hush.reflection.akshit.** { *; }

# Proxy Process Arrays (P0 - P24)
-keep class com.hush.proxy.** { *; }

############################################
# 10. TYPE INFORMATION & BUILD BEHAVIOR
############################################
-keepattributes Signature, *Annotation*, Exceptions, InnerClasses, EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-optimizations !code/simplification/arithmetic
-optimizationpasses 3
-dontwarn **
-dontoptimize
