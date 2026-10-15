package com.tracelog.lsp;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    private static final AtomicBoolean DONE = new AtomicBoolean();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        try {
            String pkg = lp.packageName;
            if (pkg == null || lp.appInfo == null) return;
            if ("android".equals(pkg) || "com.tracelog.lsp".equals(pkg)) return;
            if (!DONE.compareAndSet(false, true)) return; // 一个进程只初始化一次
            Tracer.init(lp);
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] init failed: " + t);
        }
    }
}
