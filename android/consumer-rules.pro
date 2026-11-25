# Applied to every app that uses this plugin, so release builds can keep
# minifyEnabled / shrinkResources on.

# PAX POSLink Peripheries SDK: the keep rules from the SDK's README
# (POSLink Peripheries V2.02.00G). The SDK loads its printer code through
# reflection, so these classes must keep their names.
-keep class com.pax.gl.** { *; }
-keep class com.pax.serialport.** { *; }
-keep class com.pax.log.** { *; }
-keep class com.pax.poscomm.** { *; }
-keep class com.pax.poscore.** { *; }
-keep class com.pax.posmodel.** { *; }
-keep class com.pax.posproto.** { *; }
-keep class com.pax.us.** { *; }
-keep class com.pax.poslinkperipheries.** { *; }
-keep class com.google.zxing.** { *; }

# Classes the SDK's core library refers to but that are not part of the SDK
# package or not used by the printer: the optional GLComm (Bluetooth and serial)
# and GLExtPrinter (Bluetooth printer) jars, Gson and OkHttp (POSLink's network
# terminal protocol), and PAX code-generation classes. Without these rules R8
# stops the release build with "Missing class" errors.
-dontwarn com.pax.gl.commhelper.**
-dontwarn com.pax.gl.extprinter.**
-dontwarn com.pax.framework.compiler.annotation.**
-dontwarn com.pax.posproto.filter.pj.generate.**
-dontwarn com.pax.posproto.strategy.generate.**
-dontwarn com.google.gson.**
-dontwarn okhttp3.**
