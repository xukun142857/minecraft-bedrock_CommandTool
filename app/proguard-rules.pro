# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/florinam/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# 1. 保护你的 UserService 实现类不被混淆和移除构造函数
-keep class command.plus.CommandUserService {
    public <init>();
    public <init>(android.content.Context);
}

# 2. 保护 AIDL 生成的接口及 Stub
-keep class command.plus.ICommandService { *; }
-keep class command.plus.ICommandService$Stub { *; }

# 3. 保护 Shizuku 核心反射类
-keep class moe.shizuku.starter.** { *; }