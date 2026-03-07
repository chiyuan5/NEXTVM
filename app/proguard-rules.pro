# NEXTVM ProGuard Rules

# Keep all stub components — Android references these by class name
-keep class com.nextvm.app.stub.** { *; }

# Keep BinderProvider — initialized via manifest
-keep class com.nextvm.core.virtualization.engine.BinderProvider { *; }

# Keep VirtualEngineService — foreground service
-keep class com.nextvm.core.virtualization.engine.VirtualEngineService { *; }

# Keep all classes accessed via reflection
-keep class com.nextvm.core.binder.proxy.** { *; }
-keep class com.nextvm.core.hook.** { *; }

# Android hidden API classes accessed via reflection
-dontwarn dalvik.system.**
-dontwarn android.app.IActivityManager
-dontwarn android.app.IActivityTaskManager
-dontwarn android.content.pm.IPackageManager
-dontwarn android.app.ActivityThread
-dontwarn android.app.LoadedApk
-dontwarn android.util.Singleton

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.** { *; }
