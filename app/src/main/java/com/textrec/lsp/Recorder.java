package com.textrec.lsp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 目标 App 进程里的总控。只读：所有 Hook 都不改参数、不改返回值、不拦截调用，回调外层全部 try/catch。
 *
 * 采集逻辑：
 *  - setText（最底层汇聚方法）：记“设置栈”，并把它挂到该 TextView 上（WeakHashMap，只留最近一次）。
 *  - TextView.onDraw：用 ThreadLocal 标记“当前正在画哪个 TextView”。
 *  - Canvas.drawText/drawTextRun：画文字时取出该 TextView 的设置栈，和画栈合成一条记录。
 *    没有 TextView 上下文的（自绘 / Compose）只有画栈。
 *  - 限流：用便宜的“代理 key”（控件类 + id + 文字形状[数字折叠]）判断是不是同一个位置，
 *    同一位置只抓前几次栈，之后只计数；再加全局每秒抓栈上限兜底。
 */
final class Recorder {
    private Recorder() {}

    static volatile RecConfig cfg = new RecConfig();
    static volatile KeywordFilter filter = new KeywordFilter(cfg);
    static final Store store = new Store();
    static volatile String activityName = "";

    private static volatile long cfgMtime = -1;
    private static volatile long lastCheck;
    private static volatile long appliedEpoch = Long.MIN_VALUE;
    private static volatile File dir;

    private static final long SEED = 0xcbf29ce484222325L;
    private static final Map<TextView, SetStack> VIEW_SET =
            Collections.synchronizedMap(new WeakHashMap<TextView, SetStack>());
    private static final ThreadLocal<TextView> CUR = new ThreadLocal<>();
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    static void init(XC_LoadPackage.LoadPackageParam lp) {
        File d = new File(lp.appInfo.dataDir, "files/textrec");
        d.mkdirs();
        dir = d;
        store.init(d, lp.processName, lp.packageName);
        SigResolver.addLoader(lp.classLoader);
        reload(true);
        hookActivity();
        hookTextViewDraw();
        hookSetText();
        hookCanvas();
        XposedBridge.log("[TextRec] init pkg=" + lp.packageName + " proc=" + lp.processName + " " + cfg.summary());
    }

    // ------------------------------------------------------------ 配置热更新（事件触发，最多 2 秒查一次文件时间）

    private static void maybeReload() {
        reload(false);
    }

    private static void reload(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCheck < 2000) return;
        File d = dir;
        if (d == null) return;
        synchronized (Recorder.class) {
            lastCheck = now;
            File f = new File(d, "config.properties");
            long m = f.lastModified(); // 文件不存在返回 0
            if (m == cfgMtime) return;
            cfgMtime = m;
            RecConfig c = (m == 0) ? new RecConfig() : RecConfig.read(f);
            cfg = c;
            KeywordFilter kf = new KeywordFilter(c);
            filter = kf;
            store.configure(c);
            store.setWarn(kf.warn);
            if (appliedEpoch != c.epoch) {
                if (appliedEpoch != Long.MIN_VALUE) {
                    store.reset();
                    VIEW_SET.clear();
                }
                appliedEpoch = c.epoch;
            }
            store.markDirty();
        }
    }

    // ------------------------------------------------------------ Hook 安装

    private static void hookActivity() {
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity a = (Activity) p.thisObject;
                        activityName = a.getClass().getName();
                        SigResolver.addLoader(a.getClass().getClassLoader());
                        maybeReload();
                        if (store.appVersion.isEmpty()) {
                            Context ctx = a.getApplicationContext();
                            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                            store.appVersion = pi.versionName + " (" + code + ")";
                            store.markDirty();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] hook Activity.onResume failed: " + t);
        }
    }

    private static void hookTextViewDraw() {
        try {
            Method m = TextView.class.getDeclaredMethod("onDraw", Canvas.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        CUR.set((TextView) p.thisObject);
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        CUR.remove();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] hook TextView.onDraw failed: " + t);
        }
    }

    private static void hookSetText() {
        Method m = null;
        try {
            // 所有 setText 重载最终都汇聚到这个方法
            m = TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class,
                    boolean.class, int.class);
        } catch (Throwable ignored) {
        }
        if (m == null) {
            try {
                m = TextView.class.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class);
                XposedBridge.log("[TextRec] 4-arg setText not found, fallback to 2-arg (may miss some entries)");
            } catch (Throwable t) {
                XposedBridge.log("[TextRec] hook TextView.setText failed: " + t);
                return;
            }
        }
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        onSetText(p);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] hook TextView.setText failed: " + t);
        }
    }

    private static void hookCanvas() {
        XC_MethodHook draw = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    int[] d = DEPTH.get();
                    // Canvas.drawText 会嵌套调用 BaseCanvas.drawText：只处理最外层，避免一次绘制记两次
                    if (d[0]++ == 0) onDrawText(p);
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    int[] d = DEPTH.get();
                    if (d[0] > 0) d[0]--;
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
    }

    // ------------------------------------------------------------ 设置文字

    private static void onSetText(XC_MethodHook.MethodHookParam p) {
        maybeReload();
        RecConfig c = cfg;
        if (!c.enabled || !c.traceSet) return;
        if (!(p.thisObject instanceof TextView)) return;
        TextView v = (TextView) p.thisObject;
        String text = textOf(p.args[0]);
        if (text == null || text.isEmpty() || !filter.accept(text)) {
            VIEW_SET.remove(v); // 不让它和更早的设置栈错误配对
            return;
        }
        store.setEvents.incrementAndGet();

        String cls = v.getClass().getName();
        long key = mix(mix(mix(SEED, cls.hashCode()), v.getId()), shapeHash(text));
        Store.SetProxy sp = store.setProxy(key);
        int n = sp.n.incrementAndGet();
        SetStack ss = sp.last;
        boolean want = ss == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            Stacks.Stk stk = Stacks.build(Stacks.capture(true), c.stackDepth, false);
            ss = store.internSet(new SetStack(stk, Thread.currentThread().getName()), v.getClass().getClassLoader());
            sp.last = ss;
        }
        if (ss != null) {
            ss.count.incrementAndGet();
            VIEW_SET.put(v, ss);
        } else {
            VIEW_SET.remove(v);
        }
        store.markDirty();
    }

    // ------------------------------------------------------------ 画文字

    private static void onDrawText(XC_MethodHook.MethodHookParam p) {
        maybeReload();
        RecConfig c = cfg;
        if (!c.enabled || !c.traceDraw) return;
        Object[] a = p.args;
        if (a == null || a.length < 1) return;

        TextView v = CUR.get();
        String text = null;
        if (v != null) {
            // TextView 的绘制是按行/按段调用的，这里直接取整段文字，关键字和归并都更准
            CharSequence ct = v.getText();
            if (ct != null && ct.length() > 0 && ct.length() <= 4000) text = ct.toString();
        }
        if (text == null) text = drawnText(a);
        if (text == null || text.isEmpty() || !filter.accept(text)) return;

        store.drawEvents.incrementAndGet();
        if (v != null) drawTextView(c, v, text);
        else drawCanvas(c, text);
        store.markDirty();
    }

    private static void drawTextView(RecConfig c, TextView v, String text) {
        SetStack ss = VIEW_SET.get(v);
        String cls = v.getClass().getName();
        long key = mix(mix(mix(mix(mix(SEED, cls.hashCode()), v.getId()), ss == null ? 0 : ss.stk.sig),
                shapeHash(text)), 2);
        Store.DrawProxy dp = store.drawProxy(key);
        int n = dp.n.incrementAndGet();
        Rec r = dp.rec;
        boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            Stacks.Stk d = Stacks.build(Stacks.capture(true), c.stackDepth, true);
            Rec nr = obtain(Rec.TEXTVIEW, cls, idName(v), ss, d, v, text, c);
            if (nr != null) {
                dp.rec = nr;
                r = nr;
            }
        }
        if (r != null) r.touch(text);
    }

    private static void drawCanvas(RecConfig c, String text) {
        long key = mix(mix(mix(SEED, Thread.currentThread().getName().hashCode()), shapeHash(text)), 3);
        Store.DrawProxy dp = store.drawProxy(key);
        int n = dp.n.incrementAndGet();
        Rec r = dp.rec;
        boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            Stacks.Stk d = Stacks.build(Stacks.capture(true), c.stackDepth, true);
            String kind = d.compose ? Rec.COMPOSE : Rec.CANVAS;
            String cls = d.top.length > 0 ? d.top[0][0] : "(系统绘制)";
            Rec nr = obtain(kind, cls, "", null, d, null, text, c);
            if (nr != null) {
                dp.rec = nr;
                r = nr;
            }
        }
        if (r != null) r.touch(text);
    }

    /** 按归并 key 找记录，没有就新建。文字内容不进 key。 */
    private static Rec obtain(String kind, String viewCls, String viewId, SetStack ss, Stacks.Stk d,
                              View v, String text, RecConfig c) {
        String key = kind + '|' + viewCls + '|' + viewId + '|' + (ss == null ? 0 : ss.stk.sig) + '|' + d.sig;
        Rec r = store.recs.get(key);
        if (r != null) return r;
        if (store.recs.size() >= c.maxRecords) {
            store.droppedCap.incrementAndGet();
            return null;
        }
        Rec nr = new Rec(store.nextId.getAndIncrement(), key, kind, viewCls, viewId, activityName,
                v == null ? new String[0] : parentsOf(v), ss, d, Thread.currentThread().getName(), text);
        Rec old = store.recs.putIfAbsent(key, nr);
        if (old != null) return old;
        SigResolver.resolveRec(nr, v == null ? null : v.getClass().getClassLoader());
        return nr;
    }

    // ------------------------------------------------------------ 小工具

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h;
    }

    /** 文字的“形状”：连续数字折叠成一个占位符，所以 00:12 和 00:13 的形状相同。 */
    private static long shapeHash(String s) {
        long h = SEED;
        boolean inDigits = false;
        for (int i = 0, n = s.length(); i < n; i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) {
                if (!inDigits) {
                    h = mix(h, '#');
                    inDigits = true;
                }
            } else {
                inDigits = false;
                h = mix(h, ch);
            }
        }
        return h;
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
        return null;
    }

    /** 从 drawText / drawTextRun 的参数里取出真正画的那一段文字。 */
    private static String drawnText(Object[] a) {
        Object t = a[0];
        try {
            if (t instanceof char[]) {
                char[] cs = (char[]) t;
                if (a.length > 2 && a[1] instanceof Integer && a[2] instanceof Integer) {
                    int idx = (Integer) a[1], cnt = (Integer) a[2];
                    if (idx < 0 || cnt < 0 || idx + cnt > cs.length || cnt > 4000) return null;
                    return new String(cs, idx, cnt);
                }
                return cs.length <= 4000 ? new String(cs) : null;
            }
            if (t instanceof CharSequence) {
                CharSequence cs = (CharSequence) t;
                if (a.length > 2 && a[1] instanceof Integer && a[2] instanceof Integer) {
                    int s = (Integer) a[1], e = (Integer) a[2];
                    if (s < 0 || e < s || e > cs.length() || e - s > 4000) return null;
                    return cs.subSequence(s, e).toString();
                }
                return cs.length() <= 4000 ? cs.toString() : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String idName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return "";
            try {
                return v.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                return "0x" + Integer.toHexString(id);
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** 往上 4 层父 View，用来判断文字属于哪个区域（顶部栏 / 评论列表 / 播放器…）。只在新建记录时算一次。 */
    private static String[] parentsOf(View v) {
        ArrayList<String> out = new ArrayList<>(4);
        try {
            Object p = v.getParent();
            for (int i = 0; i < 4 && p instanceof View; i++) {
                View pv = (View) p;
                String id = idName(pv);
                out.add(pv.getClass().getName() + (id.isEmpty() ? "" : "#" + id));
                p = pv.getParent();
            }
        } catch (Throwable ignored) {
        }
        return out.toArray(new String[0]);
    }
}
