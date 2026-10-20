# Xposed 入口（assets/xposed_init 里写的类名）必须保留
-keep class com.textrec.lsp.HookEntry { *; }
# Hook 回调里重写的 beforeHookedMethod / afterHookedMethod 不能被改名
-keepclassmembers class * extends de.robv.android.xposed.XC_MethodHook { *; }
-dontwarn de.robv.android.xposed.**
# 配置页用字段名反射读写 RecConfig（MainActivity 的 fineSw / fineNum）：字段被改名后按名字找不到，
# 异常又被吞掉，结果是数字框空白、开关全关、保存时退回默认值。RecConfig 的公开成员必须保留原名。
-keep class com.textrec.lsp.RecConfig { public *; }
