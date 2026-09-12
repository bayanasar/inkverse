# The accessibility service and the diagnostic receiver are instantiated by the
# platform from the manifest, so their names must survive.
-keep class com.bayanasar.inkverse.InvertAccessibilityService { *; }
-keep class com.bayanasar.inkverse.DiagReceiver { *; }
-keep class com.bayanasar.inkverse.MainActivity { *; }

# TakeScreenshotCallback is implemented against a platform interface.
-keep class * implements android.accessibilityservice.AccessibilityService$TakeScreenshotCallback { *; }
