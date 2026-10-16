package com.textrec.lsp;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.FileObserver;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 目标 App 进程里的总控。只读：所有 Hook 都不改参数、不改返回值、不拦截调用，回调外层全部 try/catch。
 *
 * 采集逻辑：
 *  - setText（最底层汇聚方法）/ append / setHint：记“设置栈”，并把它挂到该 TextView 上（WeakHashMap，只留最近一次）。
 *  - TextView.updateTextColors：记“文字颜色设置栈”和颜色值。
 *  - TextView.onDraw：用 ThreadLocal 标记“当前正在画哪个 TextView”。
 *  - Canvas.drawText/drawTextRun/drawTextOnPath：画文字时取出该 TextView 的设置栈，和画栈合成一条记录；
 *    同时记画笔（颜色/字号/描边或填充）。没有 TextView 上下文的（自绘 / Compose）只有画栈 + 画布内坐标。
 *  - Compose 文字：自动观察 MultiParagraph.paint*（颜色、DrawStyle 等参数），和随后的 drawText 配对。
 *  - ImageView.setImage* / View.setContentDescription：记图标是谁设置的（按钮图标不是文字，drawText 看不到）。
 *  - 方法追踪（用户在界面里填 类#方法）：记每次调用的参数/返回值变体和调用栈。
 *  - 限流：用便宜的“代理 key”判断是不是同一个位置，同一位置只抓前几次栈，之后只计数；再加全局每秒抓栈上限兜底。
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
    private static final Stacks.Stk EMPTY = new Stacks.Stk(new String[0], 0, 0, new String[0][], false);
    private static final Map<TextView, SetStack> VIEW_SET =
            Collections.synchronizedMap(new WeakHashMap<TextView, SetStack>());
    /** 画过的 TextView（TextView.onDraw 执行过）。用来判断“只设置、从未绘制”的文字。不随清空重置，画过就是画过。 */
    private static final Map<TextView, Boolean> VIEW_DRAWN =
            Collections.synchronizedMap(new WeakHashMap<TextView, Boolean>());
    private static final Map<TextView, ColorSet> VIEW_COLOR =
            Collections.synchronizedMap(new WeakHashMap<TextView, ColorSet>());
    /** 设置过图标 / contentDescription 的 View（弱引用），用来判断 alpha/visibility/selected 的变化是否值得记。 */
    private static final Map<View, Boolean> ICON_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());
    /** 图标最近一次的设置栈：“清空记录”后重新扫描屏幕时用它给静态图标补上设置栈。 */
    private static final Map<View, SetStack> ICON_SET =
            Collections.synchronizedMap(new WeakHashMap<View, SetStack>());
    private static volatile WeakReference<Activity> resumed;
    private static FileObserver cfgObserver; // 静态引用，避免被 GC
    private static final ThreadLocal<TextView> CUR = new ThreadLocal<>();
    private static final ThreadLocal<PaintCtx> PAINT = new ThreadLocal<>();
    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };
    private static final ThreadLocal<int[]> ICON_DEPTH = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };
    private static final ThreadLocal<boolean[]> BUSY = new ThreadLocal<boolean[]>() {
        @Override
        protected boolean[] initialValue() {
            return new boolean[1];
        }
    };

    /** Compose 文字绘制时当前 MultiParagraph.paint 的参数（和随后发生的 drawText 配对）。 */
    static final class PaintCtx {
        final String name;
        final Object[] args;
        final long key;

        PaintCtx(String name, Object[] args, long key) {
            this.name = name;
            this.args = args;
            this.key = key;
        }
    }

    // 方法追踪
    private static final Object TRACE_LOCK = new Object();
    private static volatile Set<String> traceActive = Collections.emptySet();
    private static final Set<String> traceHooked = new HashSet<>();
    private static final Map<String, String> traceMsg = new ConcurrentHashMap<>();
    private static final Map<String, Integer> traceTries = new ConcurrentHashMap<>();
    private static final ExecutorService HOOKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "textrec-hook");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean COMPOSE_HOOKED = new AtomicBoolean();

    static void init(XC_LoadPackage.LoadPackageParam lp) {
        File d = new File(lp.appInfo.dataDir, "files/textrec");
        d.mkdirs();
        dir = d;
        store.init(d, lp.processName, lp.packageName);
        SigResolver.addLoader(lp.classLoader);
        reload(true);
        watchConfig(d);
        hookActivity();
        hookTextViewDraw();
        hookSetText();
        hookTextExtras();
        hookTextColor();
        hookCanvas();
        hookIcons();
        hookState();
        ensureAppCompatHook(lp.classLoader);
        ensureComposeHook(lp.classLoader);
        retryTraceInstall();
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
                    // 只清记录，不清“View → 设置栈 / 颜色栈”的对应：View 的设置发生在清空之前，清掉就再也补不回来了
                    store.reset();
                    rescanLater(); // 屏幕上静态不变的文字不会再画，主动让它们重画一次，才能被记录
                }
                appliedEpoch = c.epoch;
            }
            applyTraceTargets(c);
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
                        resumed = new WeakReference<>(a);
                        ClassLoader acl = a.getClass().getClassLoader();
                        SigResolver.addLoader(acl);
                        maybeReload();
                        ensureComposeHook(acl);
                        ensureAppCompatHook(acl);
                        retryTraceInstall();
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
                        TextView tv = (TextView) p.thisObject;
                        CUR.set(tv);
                        // 不依赖 traceDraw 开关：只要走到 onDraw 就算“画过”
                        if (!VIEW_DRAWN.containsKey(tv)) VIEW_DRAWN.put(tv, Boolean.TRUE);
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

    /** append（不经过 setText）和 setHint（提示文字）也算“设置文字”。 */
    private static void hookTextExtras() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof TextView && p.args != null && p.args.length > 0) {
                        recordSet((TextView) p.thisObject, textOf(p.args[0]));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("append", CharSequence.class, int.class, int.class), h);
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] hook TextView.append failed: " + t);
        }
        try {
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("setHint", CharSequence.class), h);
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] hook TextView.setHint failed: " + t);
        }
    }

    /** 文字颜色：updateTextColors 是 setTextColor / setTextAppearance / XML 属性共同的汇聚点。 */
    private static void hookTextColor() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    onTextColor(p);
                } catch (Throwable ignored) {
                }
            }
        };
        boolean ok = false;
        try {
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("updateTextColors"), h);
            ok = true;
        } catch (Throwable ignored) {
        }
        if (!ok) {
            try {
                XposedBridge.hookAllMethods(TextView.class, "setTextColor", h);
            } catch (Throwable t) {
                XposedBridge.log("[TextRec] hook TextView text color failed: " + t);
            }
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
                XposedBridge.hookAllMethods(c, "drawTextOnPath", draw);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 图标：按钮上的图片不是文字，drawText 看不到。ImageView 的所有 setImage* 最终落到
     * setImageDrawable / setImageResource / ...，再加 View.setContentDescription（自绘按钮常有无障碍描述）。
     * 嵌套调用（setImageBitmap → setImageDrawable）只在最外层记一次。
     * AppCompatImageView.setImageResource 是覆盖实现，不会走到 ImageView.setImageResource，
     * 所以另外单独 hook 它，否则看不到资源名（见 ensureAppCompatHook）。
     */
    private static final XC_MethodHook ICON_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                ICON_DEPTH.get()[0]++;
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                int[] d = ICON_DEPTH.get();
                if (d[0] > 0) d[0]--;
                if (d[0] == 0) onIconSet(p);
            } catch (Throwable ignored) {
            }
        }
    };
    private static final AtomicBoolean APPCOMPAT_HOOKED = new AtomicBoolean();

    private static void hookIcons() {
        String[] names = {"setImageDrawable", "setImageResource", "setImageBitmap", "setImageIcon", "setImageURI"};
        for (String n : names) {
            try {
                XposedBridge.hookAllMethods(ImageView.class, n, ICON_HOOK);
            } catch (Throwable t) {
                XposedBridge.log("[TextRec] hook ImageView." + n + " failed: " + t);
            }
        }
        try {
            XposedBridge.hookAllMethods(View.class, "setContentDescription", ICON_HOOK);
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] hook View.setContentDescription failed: " + t);
        }
    }

    private static void ensureAppCompatHook(ClassLoader cl) {
        if (cl == null || APPCOMPAT_HOOKED.get()) return;
        try {
            Class<?> k = Class.forName("androidx.appcompat.widget.AppCompatImageView", false, cl);
            if (!APPCOMPAT_HOOKED.compareAndSet(false, true)) return;
            XposedBridge.hookAllMethods(k, "setImageResource", ICON_HOOK);
            XposedBridge.log("[TextRec] AppCompatImageView.setImageResource hooked");
        } catch (Throwable ignored) {
            // 目标 App 没有 AppCompat，或类还没法加载：下次 Activity.onResume 再试
        }
    }

    // ------------------------------------------------------------ 配置热更新（事件触发）与“清空后重新采集屏幕”

    /**
     * 用 inotify 监听配置文件：模块那边一改配置（包括“清空记录”）目标进程立刻就知道，
     * 不用等下一次 hook 事件，也不用轮询。屏幕完全静止时旧版就是因为没有事件而一直读不到新配置。
     */
    private static void watchConfig(File d) {
        try {
            cfgObserver = new FileObserver(d.getAbsolutePath(),
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null || !path.equals("config.properties")) return;
                    try {
                        reload(true);
                    } catch (Throwable ignored) {
                    }
                }
            };
            cfgObserver.startWatching();
        } catch (Throwable t) {
            XposedBridge.log("[TextRec] watch config failed: " + t);
        }
    }

    /**
     * 清空记录后，屏幕上已经显示的文字不会再被绘制（硬件加速复用显示列表），没有事件就没有记录。
     * 这里在界面线程遍历当前窗口：文字控件 invalidate() 让它重画一次（走正常的 drawText 记录，设置栈还在）；
     * 图标 / 带内容描述的控件直接补一条记录（带上最近一次的设置栈）。只读，不改任何内容。
     */
    private static void rescanLater() {
        try {
            WeakReference<Activity> w = resumed;
            Activity a = w == null ? null : w.get();
            if (a == null) return;
            final View decor = a.getWindow().getDecorView();
            decor.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        rescan(decor);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void rescan(View root) {
        ArrayList<View> todo = new ArrayList<>();
        todo.add(root);
        int seen = 0;
        while (!todo.isEmpty() && seen < 6000) {
            View v = todo.remove(todo.size() - 1);
            seen++;
            if (v.getVisibility() != View.VISIBLE) continue; // 不可见的整棵子树都不用看
            if (v instanceof TextView) {
                CharSequence ct = ((TextView) v).getText();
                if (ct != null && ct.length() > 0) v.invalidate();
            } else {
                scanIcon(v);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) todo.add(g.getChildAt(i));
            }
        }
        store.markDirty();
    }

    private static void scanIcon(View v) {
        RecConfig c = cfg;
        if (!c.enabled || !c.traceIcons) return;
        CharSequence cd = v.getContentDescription();
        boolean hasDesc = cd != null && cd.length() > 0;
        if (!(v instanceof ImageView) && !hasDesc) return;
        StringBuilder sb = new StringBuilder(64);
        sb.append("屏幕扫描");
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d == null) {
                sb.append(" drawable=null");
            } else {
                sb.append(" drawable=").append(d.getClass().getSimpleName());
                if (d instanceof BitmapDrawable) {
                    Bitmap b = ((BitmapDrawable) d).getBitmap();
                    if (b != null) sb.append(' ').append(b.getWidth()).append('x').append(b.getHeight());
                }
            }
        }
        if (hasDesc) sb.append(" desc=\"").append(cd.length() > 40 ? cd.subSequence(0, 40) + "…" : cd).append('"');
        String desc = sb.toString();
        if (!filter.accept(desc)) return;
        long shape = shapeHash(desc);
        SetStack ss = ICON_SET.get(v);
        Rec r = obtain(Rec.ICON, v.getClass().getName(), idName(v), ss, EMPTY, v, desc, shape, c);
        if (r == null) return;
        r.touch(desc, shape);
        r.trackView(v);
        ICON_VIEWS.put(v, Boolean.TRUE);
    }

    // ------------------------------------------------------------ View 状态变化（alpha / visibility / selected）

    private static final XC_MethodHook STATE_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                onState(p);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 底部标签“选中态”这类效果常常靠 alpha / selected / visibility 实现，颜色本身没变。 */
    private static void hookState() {
        String[][] ms = {{"setAlpha"}, {"setVisibility"}, {"setSelected"}};
        for (String[] m : ms) {
            try {
                XposedBridge.hookAllMethods(View.class, m[0], STATE_HOOK);
            } catch (Throwable t) {
                XposedBridge.log("[TextRec] hook View." + m[0] + " failed: " + t);
            }
        }
    }

    private static boolean isTracked(View v) {
        if (v instanceof TextView) return VIEW_DRAWN.containsKey(v) || VIEW_SET.containsKey(v);
        return ICON_VIEWS.containsKey(v);
    }

    private static String whoDesc(View v) {
        if (v instanceof TextView) {
            CharSequence ct = ((TextView) v).getText();
            String t = ct == null ? "" : ct.toString();
            return "[" + (t.length() > 20 ? t.substring(0, 20) + "…" : t) + "]";
        }
        CharSequence cd = v.getContentDescription();
        return "[" + simpleName(v.getClass().getName()) + (cd == null || cd.length() == 0 ? "" : " " + cd) + "]";
    }

    private static void onState(XC_MethodHook.MethodHookParam p) {
        RecConfig c = cfg;
        if (!c.enabled || !c.traceState) return;
        if (!(p.thisObject instanceof View) || p.args == null || p.args.length < 1) return;
        View v = (View) p.thisObject;
        if (!isTracked(v)) return;
        String m = p.method.getName();
        Object a0 = p.args[0];
        long vb;
        if (a0 instanceof Float) vb = Math.round(((Float) a0) * 10f); // alpha 动画只按 0.1 一档区分，避免每帧一个变体
        else if (a0 instanceof Integer) vb = (Integer) a0;
        else vb = a0 == null ? 0 : a0.hashCode();
        long wh;
        if (v instanceof TextView) {
            CharSequence ct = ((TextView) v).getText();
            wh = ct == null ? 0 : ct.toString().hashCode();
        } else {
            CharSequence cd = v.getContentDescription();
            wh = cd == null ? 0 : cd.toString().hashCode();
        }
        String cls = v.getClass().getName();
        long pkey = mix(mix(mix(mix(SEED, cls.hashCode()), v.getId()), m.hashCode()), 17);
        Store.DrawProxy dp = store.drawProxy(pkey);
        int n = dp.n.incrementAndGet();
        Rec r = dp.rec;
        long shape = mix(mix(mix(SEED, wh), m.hashCode()), vb);
        if (r != null && r.hasSample(shape)) { // 已经记过的变体只计数（动画里 setAlpha 每帧都来）
            r.tick();
            return;
        }
        String val;
        if (a0 instanceof Float) val = String.format(Locale.US, "%.2f", (Float) a0);
        else if (a0 instanceof Integer && m.equals("setVisibility")) {
            int iv = (Integer) a0;
            val = iv == View.VISIBLE ? "VISIBLE" : iv == View.INVISIBLE ? "INVISIBLE" : iv == View.GONE ? "GONE" : String.valueOf(iv);
        } else val = String.valueOf(a0);
        String desc = whoDesc(v) + " " + m + "(" + val + ")";
        boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            Stacks.Stk stk = Stacks.build(Stacks.capture(true), c.stackDepth, false);
            SetStack ss = store.internSet(new SetStack(stk, Thread.currentThread().getName()), v.getClass().getClassLoader());
            ss.count.incrementAndGet();
            Rec nr = obtain(Rec.STATE, cls, idName(v), m, ss, EMPTY, v, desc, shape, c);
            if (nr != null) {
                dp.rec = nr;
                r = nr;
            }
        }
        if (r != null) {
            r.touch(desc, shape);
            r.trackView(v);
        }
        store.markDirty();
    }

    // ------------------------------------------------------------ Compose 文字绘制参数（自动观察）

    private static final XC_MethodHook PAINT_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                RecConfig c = cfg;
                if (!c.enabled || !c.autoParams || !c.traceDraw) {
                    PAINT.remove();
                    return;
                }
                Object[] a = p.args;
                String name = p.method.getName();
                long k = mix(SEED, name.hashCode());
                if (a != null) {
                    for (int i = 1; i < a.length; i++) { // 0 是 Canvas
                        Object o = a[i];
                        if (o == null) k = mix(k, 7);
                        else if (o instanceof Number || o instanceof Boolean || o instanceof Character) k = mix(k, o.hashCode());
                        else k = mix(k, o.getClass().hashCode());
                    }
                }
                PAINT.set(new PaintCtx(name, a, k));
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                PAINT.remove();
            } catch (Throwable ignored) {
            }
        }
    };

    /** androidx.compose.ui.text.MultiParagraph 没被混淆，paint* 的参数里有文字颜色和 DrawStyle（描边/填充）。 */
    private static void ensureComposeHook(ClassLoader cl) {
        if (cl == null || COMPOSE_HOOKED.get()) return;
        try {
            Class<?> mp = Class.forName("androidx.compose.ui.text.MultiParagraph", false, cl);
            if (!COMPOSE_HOOKED.compareAndSet(false, true)) return;
            int n = 0;
            for (Method m : mp.getDeclaredMethods()) {
                String nm = m.getName();
                if (!nm.startsWith("paint") || nm.indexOf('$') >= 0) continue;
                XposedBridge.hookMethod(m, PAINT_HOOK);
                n++;
            }
            XposedBridge.log("[TextRec] compose MultiParagraph.paint* hooked: " + n);
        } catch (Throwable ignored) {
            // 目标 App 没有 Compose，或类还没法加载：下次 Activity.onResume 再试
        }
    }

    // ------------------------------------------------------------ 方法追踪（用户指定 类#方法，只读）

    private static final XC_MethodHook TRACE_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                onTraced(p);
            } catch (Throwable ignored) {
            }
        }
    };

    private static void applyTraceTargets(RecConfig c) {
        HashSet<String> want = new HashSet<>(RecConfig.splitTargets(c.traceMethods));
        traceActive = want;
        if (want.isEmpty()) {
            traceMsg.clear();
            publishTraceWarn();
            return;
        }
        retryTraceInstall();
    }

    private static void retryTraceInstall() {
        final Set<String> want = traceActive;
        if (want.isEmpty()) return;
        boolean need = false;
        synchronized (TRACE_LOCK) {
            for (String t : want) {
                if (!traceHooked.contains(t)) {
                    need = true;
                    break;
                }
            }
        }
        if (!need) return;
        try {
            HOOKER.execute(() -> installTraces(want));
        } catch (Throwable ignored) {
        }
    }

    private static void markHooked(String t) {
        synchronized (TRACE_LOCK) {
            traceHooked.add(t);
        }
    }

    private static void installTraces(Set<String> want) {
        for (String t : want) {
            synchronized (TRACE_LOCK) {
                if (traceHooked.contains(t)) continue;
            }
            int hash = t.indexOf('#');
            if (hash <= 0 || hash == t.length() - 1) {
                traceMsg.put(t, "格式应为 类#方法");
                markHooked(t);
                continue;
            }
            String cn = t.substring(0, hash);
            String mn = t.substring(hash + 1);
            Integer prev = traceTries.get(t);
            int tn = prev == null ? 1 : prev + 1;
            traceTries.put(t, tn);
            Class<?> k = SigResolver.findClass(cn, null);
            if (k == null) {
                if (tn >= 40) {
                    traceMsg.put(t, "类未找到，已放弃重试（检查类名；App 更新后混淆名会变）");
                    markHooked(t);
                } else {
                    traceMsg.put(t, "类暂未找到（可能还没加载，进入相关页面后会自动重试）");
                }
                continue;
            }
            try {
                Set<?> hooked = mn.equals("<init>")
                        ? XposedBridge.hookAllConstructors(k, TRACE_HOOK)
                        : XposedBridge.hookAllMethods(k, mn, TRACE_HOOK);
                markHooked(t);
                if (hooked == null || hooked.isEmpty()) {
                    traceMsg.put(t, "这个类里没有声明该方法（可能在父类，或方法名写错）");
                } else {
                    traceMsg.remove(t);
                }
            } catch (Throwable e) {
                traceMsg.put(t, "hook 失败: " + e);
                markHooked(t);
            }
        }
        publishTraceWarn();
    }

    private static void publishTraceWarn() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : traceMsg.entrySet()) {
            sb.append("追踪 ").append(e.getKey()).append("：").append(e.getValue()).append('\n');
        }
        store.setTraceWarn(sb.toString());
        store.markDirty();
    }

    private static void onTraced(XC_MethodHook.MethodHookParam p) {
        RecConfig c = cfg;
        if (!c.enabled) return;
        Member m = p.method;
        String cn = m.getDeclaringClass().getName();
        String mn = m instanceof Constructor ? "<init>" : m.getName();
        String tkey = cn + "#" + mn;
        if (!traceActive.contains(tkey)) return;
        boolean[] busy = BUSY.get();
        if (busy[0]) return; // 摘要里调 toString 可能又触发被追踪的方法
        busy[0] = true;
        try {
            Store.DrawProxy dp = store.drawProxy(mix(mix(SEED, tkey.hashCode()), 9));
            int n = dp.n.incrementAndGet();
            Rec r = dp.rec;
            if (n > 3000 && n % 10 != 0) { // 高频方法：只计数，每 10 次取一次样
                if (r != null) r.tick();
                return;
            }
            String text = summarizeCall(p);
            long shape = text.hashCode();
            boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
            if (want && store.allowStack(c.stacksPerSec)) {
                Stacks.Stk d = Stacks.build(Stacks.capture(true), c.stackDepth, false);
                Rec nr = obtain(Rec.TRACE, cn, mn, null, d, null, text, shape, c);
                if (nr != null) {
                    dp.rec = nr;
                    r = nr;
                }
            }
            if (r != null) r.touch(text, shape);
            store.markDirty();
        } finally {
            busy[0] = false;
        }
    }

    /** 追踪方法的调用对象：View 给“类#id「文字」”，其他对象给类名。 */
    private static String thisDesc(Object o) {
        if (o == null) return "";
        StringBuilder sb = new StringBuilder(48);
        sb.append(simpleName(o.getClass().getName()));
        if (o instanceof View) {
            View v = (View) o;
            String id = idName(v);
            if (!id.isEmpty()) sb.append('#').append(id);
            if (o instanceof TextView) {
                CharSequence ct = ((TextView) o).getText();
                if (ct != null && ct.length() > 0) {
                    String t = ct.toString();
                    sb.append('「').append(t.length() > 16 ? t.substring(0, 16) + "…" : t).append('」');
                }
            } else {
                CharSequence cd = v.getContentDescription();
                if (cd != null && cd.length() > 0) sb.append('「').append(cd.length() > 16 ? cd.subSequence(0, 16) + "…" : cd).append('」');
            }
        }
        return sb.toString();
    }

    private static String summarizeCall(XC_MethodHook.MethodHookParam p) {
        StringBuilder sb = new StringBuilder(96);
        Object[] a = p.args;
        if (p.thisObject != null) sb.append("this=").append(thisDesc(p.thisObject)).append("  ");
        sb.append('(');
        if (a != null) {
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(argStr(a[i]));
            }
        }
        sb.append(')');
        Member m = p.method;
        if (p.hasThrowable()) {
            sb.append(" ✗ ").append(p.getThrowable().getClass().getSimpleName());
        } else if (m instanceof Method && ((Method) m).getReturnType() != void.class) {
            sb.append("  →  ").append(argStr(p.getResult()));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 设置文字

    private static void onSetText(XC_MethodHook.MethodHookParam p) {
        if (!(p.thisObject instanceof TextView)) return;
        recordSet((TextView) p.thisObject, textOf(p.args[0]));
    }

    private static void recordSet(TextView v, String text) {
        maybeReload();
        RecConfig c = cfg;
        if (!c.enabled || !c.traceSet) return;
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
            if (c.traceSetOnly) noteSetOnly(c, v, text, ss);
        } else {
            VIEW_SET.remove(v);
        }
        store.markDirty();
    }

    /**
     * 登记“这个位置设置过文字”，用于发现从未被绘制的文字（INVISIBLE/GONE/尺寸为 0/未挂窗口）。
     * 记录按 (View 类, id, 设置栈) 归并，每个位置一条；它是否仍然“从未绘制”在写快照时才判断，
     * 所以控件后来被画了，这条记录就不再出现（已有对应的 TEXTVIEW 记录），不会重复。
     */
    private static void noteSetOnly(RecConfig c, TextView v, String text, SetStack ss) {
        String cls = v.getClass().getName();
        long key = mix(mix(mix(mix(SEED, cls.hashCode()), v.getId()), ss.stk.sig), 13);
        Store.DrawProxy dp = store.drawProxy(key);
        Rec r = dp.rec;
        long shape = shapeHash(text);
        if (r == null && dp.n.getAndIncrement() == 0) { // 每个位置只尝试建一次，满额时不反复尝试
            r = obtain(Rec.TEXTSET, cls, idName(v), ss, EMPTY, v, text, shape, c);
            if (r != null) dp.rec = r;
        }
        if (r != null) {
            r.noteSet(text, shape);
            r.trackView(v);
        }
    }

    /** 这条 TEXTSET 记录里，还从未被绘制过的（活着的）TextView。写快照时调用。 */
    static java.util.List<View> undrawn(Rec r) {
        ArrayList<View> out = new ArrayList<>();
        for (View v : r.liveViews()) {
            if (v instanceof TextView && !VIEW_DRAWN.containsKey(v)) out.add(v);
        }
        return out;
    }

    /** 一个从未被绘制的控件，为什么没被画。 */
    static String notDrawnReason(View v) {
        try {
            if (!v.isAttachedToWindow()) return "未挂到窗口（已被移除，或还没加入布局）";
            if (v.getVisibility() == View.GONE) return "自身 GONE";
            if (v.getVisibility() == View.INVISIBLE) return "自身 INVISIBLE";
            Object p = v.getParent();
            for (int i = 0; i < 12 && p instanceof View; i++) {
                View pv = (View) p;
                int vis = pv.getVisibility();
                if (vis != View.VISIBLE) {
                    String id = idName(pv);
                    return "祖先 " + simpleName(pv.getClass().getName()) + (id.isEmpty() ? "" : "#" + id)
                            + (vis == View.GONE ? " 为 GONE" : " 为 INVISIBLE");
                }
                p = pv.getParent();
            }
            if (v.getWidth() <= 0 || v.getHeight() <= 0) return "尺寸为 0（还没测量/布局，或宽高为 0）";
            if (v.getAlpha() < 0.05f) return "alpha≈0";
            return "可见且已布局，但还没被绘制（可能刚设置，下次快照再看；或子类没调用 super.onDraw）";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 文字颜色被设置：记设置栈和颜色值（TextView 的颜色最终由 Paint 带到 drawText，Paint 颜色另记）。 */
    private static void onTextColor(XC_MethodHook.MethodHookParam p) {
        if (!(p.thisObject instanceof TextView)) return;
        maybeReload();
        RecConfig c = cfg;
        if (!c.enabled || !c.traceColor) return;
        TextView v = (TextView) p.thisObject;
        int color = v.getCurrentTextColor();
        String cls = v.getClass().getName();
        long key = mix(mix(mix(SEED, cls.hashCode()), v.getId()), 11);
        Store.SetProxy sp = store.setProxy(key);
        int n = sp.n.incrementAndGet();
        SetStack ss = sp.last;
        boolean want = ss == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            Stacks.Stk stk = Stacks.build(Stacks.capture(true), c.stackDepth, false);
            ss = store.internSet(new SetStack(stk, Thread.currentThread().getName()), v.getClass().getClassLoader());
            sp.last = ss;
        }
        ColorSet old = VIEW_COLOR.get(v);
        if (old == null || old.ss != ss || old.color != color) VIEW_COLOR.put(v, new ColorSet(ss, color));
        store.markDirty();
    }

    // ------------------------------------------------------------ 图标

    private static void onIconSet(XC_MethodHook.MethodHookParam p) {
        if (!(p.thisObject instanceof View)) return;
        View v = (View) p.thisObject;
        if (v instanceof TextView) return; // 文字控件已经由 setText 那条线记录
        maybeReload();
        RecConfig c = cfg;
        if (!c.enabled || !c.traceIcons) return;

        Object[] a = p.args;
        String m = p.method.getName();
        String cls = v.getClass().getName();
        long key = mix(mix(mix(SEED, cls.hashCode()), v.getId()), m.hashCode());
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            key = mix(key, d == null ? 0 : d.getClass().hashCode());
        }
        if (a != null && a.length > 0 && a[0] instanceof Integer) key = mix(key, (Integer) a[0]);
        CharSequence cd = v.getContentDescription();
        if (cd != null) key = mix(key, cd.toString().hashCode());
        key = mix(key, 5);

        Store.DrawProxy dp = store.drawProxy(key);
        int n = dp.n.incrementAndGet();
        Rec r = dp.rec;
        boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        ICON_VIEWS.put(v, Boolean.TRUE);
        if (!want) { // 列表滚动时图标会被反复设置：已知位置只计数
            if (r.set != null) ICON_SET.put(v, r.set);
            r.tick();
            r.trackView(v);
            store.markDirty();
            return;
        }
        String desc = iconDesc(v, p);
        if (!filter.accept(desc)) return;
        long shape = shapeHash(desc);
        store.setEvents.incrementAndGet();
        if (store.allowStack(c.stacksPerSec)) {
            Stacks.Stk stk = Stacks.build(Stacks.capture(true), c.stackDepth, false);
            SetStack ss = store.internSet(new SetStack(stk, Thread.currentThread().getName()), v.getClass().getClassLoader());
            ss.count.incrementAndGet();
            ICON_SET.put(v, ss);
            Rec nr = obtain(Rec.ICON, cls, idName(v), ss, EMPTY, v, desc, shape, c);
            if (nr != null) {
                dp.rec = nr;
                r = nr;
            }
        }
        if (r != null) {
            r.touch(desc, shape);
            r.trackView(v);
        }
        store.markDirty();
    }

    private static String iconDesc(View v, XC_MethodHook.MethodHookParam p) {
        String m = p.method.getName();
        Object[] a = p.args;
        StringBuilder sb = new StringBuilder(64);
        sb.append(m);
        if (m.equals("setImageResource") && a != null && a.length > 0 && a[0] instanceof Integer) {
            sb.append(" res=").append(resName(v, (Integer) a[0]));
        }
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d == null) {
                sb.append(" drawable=null");
            } else {
                sb.append(" drawable=").append(d.getClass().getSimpleName());
                if (d instanceof BitmapDrawable) {
                    Bitmap b = ((BitmapDrawable) d).getBitmap();
                    if (b != null) sb.append(' ').append(b.getWidth()).append('x').append(b.getHeight());
                }
            }
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) {
            sb.append(" desc=\"").append(cd.length() > 40 ? cd.subSequence(0, 40) + "…" : cd).append('"');
        }
        return sb.toString();
    }

    private static String resName(View v, int id) {
        String hex = "0x" + Integer.toHexString(id);
        try {
            return v.getResources().getResourceEntryName(id) + "(" + hex + ")";
        } catch (Throwable t) {
            return hex;
        }
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
        Paint pt = paintOf(a);
        if (v != null) drawTextView(c, v, text, pt);
        else drawCanvas(c, text, pt, a);
        store.markDirty();
    }

    private static void drawTextView(RecConfig c, TextView v, String text, Paint pt) {
        SetStack ss = VIEW_SET.get(v);
        String cls = v.getClass().getName();
        long shape = shapeHash(text);
        long key = mix(mix(mix(mix(mix(SEED, cls.hashCode()), v.getId()), ss == null ? 0 : ss.stk.sig),
                shape), 2);
        Store.DrawProxy dp = store.drawProxy(key);
        int n = dp.n.incrementAndGet();
        Rec r = dp.rec;
        boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            // 抓完整栈再裁成“这个 View 自己的绘制”，去掉祖先 dispatchDraw 带来的布局位置噪声
            Stacks.Stk d = Stacks.build(Stacks.trimToViewDraw(Stacks.capture(false)), c.stackDepth, true);
            Rec nr = obtain(Rec.TEXTVIEW, cls, idName(v), ss, d, v, text, shape, c);
            if (nr != null) {
                dp.rec = nr;
                r = nr;
            }
        }
        if (r != null) {
            r.touch(text, shape);
            r.trackView(v);
            if (c.traceColor) {
                notePaint(r, pt);
                ColorSet cs = VIEW_COLOR.get(v);
                if (cs != null) r.colorSet = cs;
            }
        }
    }

    private static void drawCanvas(RecConfig c, String text, Paint pt, Object[] a) {
        long shape = shapeHash(text);
        PaintCtx ctx = PAINT.get();
        long key = mix(mix(mix(mix(SEED, Thread.currentThread().getName().hashCode()), shape),
                ctx == null ? 0 : ctx.key), 3);
        Store.DrawProxy dp = store.drawProxy(key);
        int n = dp.n.incrementAndGet();
        Rec r = dp.rec;
        boolean want = r == null || n <= c.perKeyStacks || n % c.sampleEvery == 0;
        if (want && store.allowStack(c.stacksPerSec)) {
            Stacks.Stk d = Stacks.build(Stacks.capture(true), c.stackDepth, true);
            String kind = d.compose ? Rec.COMPOSE : Rec.CANVAS;
            String cls = d.top.length > 0 ? d.top[0][0] : "(系统绘制)";
            Rec nr = obtain(kind, cls, "", null, d, null, text, shape, c);
            if (nr != null) {
                dp.rec = nr;
                r = nr;
            }
        }
        if (r != null) {
            r.touch(text, shape);
            setPos(r, a);
            if (c.traceColor) notePaint(r, pt);
        }
    }

    /** 记一次画笔：颜色、字号、描边/填充；Compose 文字再带上 paint 调用的参数。新变体才拼字符串。 */
    private static void notePaint(Rec r, Paint pt) {
        PaintCtx ctx = PAINT.get();
        if (pt == null && ctx == null) return;
        long key = SEED;
        if (pt != null) {
            key = mix(key, pt.getColor());
            key = mix(key, Float.floatToIntBits(pt.getTextSize()));
            Paint.Style st = pt.getStyle();
            key = mix(key, st == null ? 0 : st.ordinal());
        }
        if (ctx != null) key = mix(key, ctx.key);
        if (r.knowsPaint(key)) return;
        boolean[] busy = BUSY.get();
        if (busy[0]) return;
        busy[0] = true;
        try {
            StringBuilder sb = new StringBuilder(128);
            if (ctx != null) sb.append(callDesc(ctx.name, ctx.args, 1)).append("  →  ");
            if (pt != null) sb.append(paintDesc(pt));
            r.addPaint(key, sb.toString());
        } finally {
            busy[0] = false;
        }
    }

    private static String callDesc(String name, Object[] a, int from) {
        StringBuilder sb = new StringBuilder(96);
        sb.append(name).append('(');
        if (a != null) {
            for (int i = from; i < a.length; i++) {
                if (i > from) sb.append(", ");
                sb.append(argStr(a[i]));
            }
        }
        return sb.append(')').toString();
    }

    private static String paintDesc(Paint pt) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("Paint(color=0x").append(String.format(Locale.US, "%08X", pt.getColor()));
        sb.append(" size=").append(pt.getTextSize());
        Paint.Style st = pt.getStyle();
        sb.append(" style=").append(st == null ? "?" : st.name());
        if (pt.getShader() != null) sb.append(" shader=").append(pt.getShader().getClass().getSimpleName());
        return sb.append(')').toString();
    }

    private static Paint paintOf(Object[] a) {
        Object o = a[a.length - 1];
        return o instanceof Paint ? (Paint) o : null;
    }

    /** 画布内坐标：参数里第一对相邻的 float（drawText 的 x,y / drawTextRun 的 x,y / drawTextOnPath 的偏移）。 */
    private static void setPos(Rec r, Object[] a) {
        for (int i = 0; i + 1 < a.length; i++) {
            if (a[i] instanceof Float && a[i + 1] instanceof Float) {
                r.posX = (Float) a[i];
                r.posY = (Float) a[i + 1];
                return;
            }
        }
    }

    /** 参数的可读摘要。long 同时给十六进制；像 Compose Color 打包值（低 32 位为 0）的会标出 ARGB。 */
    private static String argStr(Object o) {
        if (o == null) return "null";
        if (o instanceof Long) {
            long v = (Long) o;
            String h = "0x" + Long.toHexString(v);
            if ((v & 0xFFFFFFFFL) == 0 && v != 0) {
                return h + "(≈Compose Color ARGB 0x" + String.format(Locale.US, "%08X", (int) (v >>> 32)) + ")";
            }
            return v + "(" + h + ")";
        }
        if (o instanceof Integer || o instanceof Short || o instanceof Byte || o instanceof Float
                || o instanceof Double || o instanceof Boolean || o instanceof Character) {
            return String.valueOf(o);
        }
        if (o instanceof CharSequence) {
            String s = o.toString();
            return "\"" + (s.length() > 60 ? s.substring(0, 60) + "…" : s) + "\"";
        }
        if (o instanceof Paint) return paintDesc((Paint) o);
        Class<?> k = o.getClass();
        if (o instanceof View || o instanceof Context || o instanceof Canvas) return simpleName(k.getName());
        if (k.isArray()) return k.getComponentType().getSimpleName() + "[" + java.lang.reflect.Array.getLength(o) + "]";
        if (o instanceof Collection) return simpleName(k.getName()) + "(size=" + ((Collection<?>) o).size() + ")";
        if (o instanceof Map) return simpleName(k.getName()) + "(size=" + ((Map<?, ?>) o).size() + ")";
        String s;
        try {
            s = String.valueOf(o);
        } catch (Throwable t) {
            s = null;
        }
        String cn = k.getName();
        if (s == null || s.startsWith(cn + "@")) return simpleName(cn); // 默认 toString（Kotlin object 如 Fill）只显示类名
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String simpleName(String cn) {
        int i = cn.lastIndexOf('.');
        return i >= 0 ? cn.substring(i + 1) : cn;
    }

    /** 按归并 key 找记录，没有就新建。文字内容不进 key。 */
    private static Rec obtain(String kind, String viewCls, String viewId, SetStack ss, Stacks.Stk d,
                              View v, String text, long shape, RecConfig c) {
        // 方法追踪的“id”其实是方法名，必须进 key；其他类型 View id 不进 key（同一段代码设置 13 个控件只留 1 条）
        return obtain(kind, viewCls, viewId, Rec.TRACE.equals(kind) ? viewId : "", ss, d, v, text, shape, c);
    }

    /**
     * 按“位置”归并：key = 类型 + View 类 + keyExtra + 设置栈 + 绘制栈。View id 不进 key（同一段代码
     * 设置/绘制的一批控件合成一条，id 列表挂在记录上）。keyExtra 用于必须区分的东西（追踪的方法名、状态变化的方法名）。
     */
    private static Rec obtain(String kind, String viewCls, String viewId, String keyExtra, SetStack ss,
                              Stacks.Stk d, View v, String text, long shape, RecConfig c) {
        String key = kind + '|' + viewCls + '|' + keyExtra + '|' + (ss == null ? 0 : ss.stk.sig) + '|' + d.sig;
        Rec r = store.recs.get(key);
        if (r != null) {
            r.addId(viewId);
            return r;
        }
        final boolean setOnly = Rec.TEXTSET.equals(kind);
        if (store.recs.size() >= c.maxRecords) {
            store.droppedCap.incrementAndGet();
            return null;
        }
        if (setOnly && store.setOnlyCount.get() >= c.maxSetOnly) {
            store.droppedSetOnly.incrementAndGet();
            return null;
        }
        Rec nr = new Rec(store.nextId.getAndIncrement(), key, kind, viewCls, viewId, activityName,
                v == null ? new String[0] : parentsOf(v), ss, d, Thread.currentThread().getName(), text, shape);
        Rec old = store.recs.putIfAbsent(key, nr);
        if (old != null) {
            old.addId(viewId);
            return old;
        }
        if (setOnly) store.setOnlyCount.incrementAndGet();
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

    /** 从 drawText / drawTextRun / drawTextOnPath 的参数里取出真正画的那一段文字。 */
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
            if (t != null && "android.graphics.text.MeasuredText".equals(t.getClass().getName())) {
                // drawTextRun(MeasuredText, ...)：文字在 MeasuredText 里，读不到也要留一条记录，不能悄悄丢掉
                try {
                    Method gm = t.getClass().getDeclaredMethod("getChars");
                    gm.setAccessible(true);
                    char[] cs = (char[]) gm.invoke(t);
                    if (cs != null) {
                        if (a.length > 2 && a[1] instanceof Integer && a[2] instanceof Integer) {
                            int s = (Integer) a[1], e = (Integer) a[2];
                            if (s >= 0 && e >= s && e <= cs.length && e - s <= 4000) return new String(cs, s, e - s);
                        }
                        if (cs.length <= 4000) return new String(cs);
                    }
                } catch (Throwable ignored) {
                }
                return "[MeasuredText：读不到文字]";
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
