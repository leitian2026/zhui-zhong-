package com.tracelog.lsp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Paint;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 目标 App 进程里的总控：读配置、安装文本/颜色/类追踪 Hook。全部事件驱动，没有任何轮询。 */
final class Tracer {
    private Tracer() {}

    static volatile TraceConfig cfg = new TraceConfig();
    private static volatile long cfgMtime = -1;
    private static volatile long lastCheck;
    private static boolean textHooked, colorHooked, eventsHooked;

    private static final ConcurrentHashMap<Integer, AtomicInteger> SEEN = new ConcurrentHashMap<>();
    private static final AtomicInteger SAMPLE = new AtomicInteger();

    static void init(XC_LoadPackage.LoadPackageParam lp) {
        TraceLog.init(lp.appInfo.dataDir, lp.processName);
        ClassTracer.addLoader(lp.classLoader);
        reload(true, true);
        TraceLog.log("LOADED", "pkg=" + lp.packageName + " configFile="
                + (TraceLog.configFile().exists() ? "found" : "missing(using defaults)")
                + " " + cfg.summary(), null);
        hookEvents();
    }

    /** 配置文件变了才重读。事件触发时顺带检查，最多每 2 秒检查一次（只是一次 stat）。 */
    static void maybeReload() {
        reload(false, false);
    }

    private static void reload(boolean ignoreThrottle, boolean quiet) {
        long now = SystemClock.elapsedRealtime();
        if (!ignoreThrottle && now - lastCheck < 2000) return;
        synchronized (Tracer.class) {
            lastCheck = now;
            File f = TraceLog.configFile();
            long m = f.lastModified(); // 文件不存在返回 0
            if (m == cfgMtime) return;
            cfgMtime = m;
            cfg = (m == 0) ? new TraceConfig() : TraceConfig.read(f);
            SEEN.clear();              // 重新推送配置 = 重新开始计数
            ClassTracer.resetCounters();
            if (!quiet) TraceLog.log("CONFIG", "reloaded " + cfg.summary(), null);
            applyHooks();
        }
    }

    private static void applyHooks() {
        TraceConfig c = cfg;
        if (c.enabled && c.traceText && !textHooked) hookText();
        if (c.enabled && c.traceColor && !colorHooked) hookColor();
        ClassTracer.setSpecs(c.enabled ? c.classSpecs() : java.util.Collections.<String>emptyList());
        ClassTracer.tryInstall();
    }

    // ---------------------------------------------------------------- 事件

    private static void hookEvents() {
        if (eventsHooked) return;
        eventsHooked = true;
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject != null) ClassTracer.addLoader(p.thisObject.getClass().getClassLoader());
                        maybeReload();
                        ClassTracer.tryInstall();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] hook Activity.onResume failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 文本

    private static void hookText() {
        textHooked = true;
        XC_MethodHook draw = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    maybeReload();
                    TraceConfig c = cfg;
                    if (!c.enabled || !c.traceText) return;
                    Object[] a = p.args;
                    if (a == null || a.length < 2) return;
                    String text = textOf(a[0]);
                    if (text == null) return;
                    String extra = "";
                    Object last = a[a.length - 1];
                    if (last instanceof Paint) {
                        Paint paint = (Paint) last;
                        extra = " color=" + Util.hex(paint.getColor()) + (paint.getShader() != null ? " shader" : "");
                    }
                    onText("Canvas." + p.method.getName(), text, extra, c);
                } catch (Throwable ignored) {
                }
            }
        };
        // 硬件加速绘制在不同 Android 版本落在不同的类上，全部挂上
        String[] canvases = {"android.graphics.Canvas", "android.graphics.BaseCanvas",
                "android.graphics.BaseRecordingCanvas", "android.graphics.RecordingCanvas"};
        for (String cn : canvases) {
            try {
                Class<?> c = Class.forName(cn);
                XposedBridge.hookAllMethods(c, "drawText", draw);
                XposedBridge.hookAllMethods(c, "drawTextRun", draw);
            } catch (Throwable ignored) {
            }
        }
        try {
            Method m = TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        maybeReload();
                        TraceConfig c = cfg;
                        if (!c.enabled || !c.traceText) return;
                        String text = textOf(p.args[0]);
                        if (text == null) return;
                        View v = (View) p.thisObject;
                        String extra = " view=" + v.getClass().getName() + idOf(v)
                                + " color=" + Util.hex(((TextView) v).getCurrentTextColor());
                        onText("TextView.setText", text, extra, c);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] hook TextView.setText failed: " + t);
        }
    }

    private static String idOf(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "";
            String name;
            try {
                name = v.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                name = "0x" + Integer.toHexString(id);
            }
            return " id=" + name;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String textOf(Object o) {
        if (o instanceof String) {
            String s = (String) o;
            return s.length() <= 4000 ? s : null;
        }
        if (o instanceof CharSequence) {
            CharSequence cs = (CharSequence) o;
            return cs.length() <= 4000 ? cs.toString() : null;
        }
        if (o instanceof char[]) {
            char[] cs = (char[]) o;
            return cs.length <= 4000 ? new String(cs) : null;
        }
        return null;
    }

    private static void onText(String source, String text, String extra, TraceConfig c) {
        List<String> kws = c.keywordList();
        boolean sampling = kws.isEmpty();
        if (sampling) {
            // 关键字为空：抽样模式，每 200 次只看 1 次，避免卡顿
            if (SAMPLE.incrementAndGet() % 200 != 0) return;
        } else {
            boolean hit = false;
            for (String k : kws) {
                if (text.contains(k)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return;
        }
        List<StackTraceElement> all = Util.capture(false);
        // 输入框里你正在打的字（Editor.onDraw）也会命中关键字，不是目标，直接跳过
        if (Util.hasFrame(all, "android.widget.Editor")) return;
        List<StackTraceElement> fr = Util.capture(true);
        int key = Util.signature(fr) * 31 + source.hashCode();
        if (!sampling) key = key * 31 + text.hashCode();
        if (!allow(key, sampling ? 2 : 3)) return;
        TraceLog.log("TEXT", source + " text=\"" + Util.clean(text, 200) + "\"" + extra,
                Util.format(fr, c.stackDepth));
    }

    // ---------------------------------------------------------------- 颜色

    private static void hookColor() {
        colorHooked = true;
        try {
            Method m = Paint.class.getDeclaredMethod("setColor", int.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    onColor("Paint.setColor", Util.hex((Integer) p.args[0]));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] hook Paint.setColor failed: " + t);
        }
        try {
            Method m = TextView.class.getDeclaredMethod("setTextColor", int.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    onColor("TextView.setTextColor", Util.hex((Integer) p.args[0]));
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] hook TextView.setTextColor failed: " + t);
        }
        try {
            Method m = TextView.class.getDeclaredMethod("setTextColor", ColorStateList.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object o = p.args[0];
                    onColor("TextView.setTextColor(CSL)",
                            o instanceof ColorStateList ? Util.hex(((ColorStateList) o).getDefaultColor()) : "null");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] hook TextView.setTextColor(CSL) failed: " + t);
        }
        try {
            Method m = Color.class.getDeclaredMethod("parseColor", String.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    Object r = p.getResult();
                    onColor("Color.parseColor(" + Util.clean(String.valueOf(p.args[0]), 20) + ")",
                            r instanceof Integer ? Util.hex((Integer) r) : "?");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TraceLog] hook Color.parseColor failed: " + t);
        }
    }

    private static void onColor(String what, String value) {
        try {
            maybeReload();
            TraceConfig c = cfg;
            if (!c.enabled || !c.traceColor) return;
            List<StackTraceElement> fr = Util.capture(true);
            List<String> filt = c.colorFilterList();
            if (!filt.isEmpty()) {
                String joined = Util.joined(fr);
                boolean ok = false;
                for (String f : filt) {
                    if (joined.contains(f)) {
                        ok = true;
                        break;
                    }
                }
                if (!ok) return;
            }
            int key = Util.signature(fr) * 31 + what.hashCode();
            if (!allow(key, 3)) return;
            TraceLog.log("COLOR", what + " = " + value, Util.format(fr, Math.min(c.stackDepth, 12)));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 去重

    /** 同一个调用位置最多记 cap 次，避免每帧重复绘制把日志刷爆。 */
    private static boolean allow(int key, int cap) {
        AtomicInteger n = SEEN.get(key);
        if (n == null) {
            if (SEEN.size() > 4000) return false;
            AtomicInteger nn = new AtomicInteger();
            n = SEEN.putIfAbsent(key, nn);
            if (n == null) n = nn;
        }
        return n.incrementAndGet() <= cap;
    }
}
