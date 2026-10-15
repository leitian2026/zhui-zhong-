# Xposed 入口（assets/xposed_init 里写的类名）必须保留
-keep class com.textrec.lsp.HookEntry { *; }
# Hook 回调里重写的 beforeHookedMethod / afterHookedMethod 不能被改名
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook { *; }
-dontwarn de.robv.android.xposed.**
