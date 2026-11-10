# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

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

# The service check report is written and read as JSON (MMKV), so the JSON library
# finds the class, its fields and the names of the enum constants by reflection.
# The generic signature is needed for the `List<ServiceCheckResult>` field of the
# report, without it the library cannot read the elements of the list.
-keepattributes Signature
-keep class com.v2ray.ang.dto.entities.ServiceCheckReport { *; }
-keep class com.v2ray.ang.dto.entities.ServiceCheckResult { *; }
-keep enum com.v2ray.ang.dto.entities.ServiceCheckStatus { *; }

# The message of a real-delay test is sent to the task process as a serialized Java
# object, so its class and its fields are read by reflection there.
-keep class com.v2ray.ang.dto.TestServiceMessage { *; }
