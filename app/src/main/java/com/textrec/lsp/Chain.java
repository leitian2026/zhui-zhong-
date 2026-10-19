package com.textrec.lsp;

import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 链路追踪：以“方法追踪”里的任意一个方法为落脚点（锚点），把它的上游和下游串成一条链。
 *
 * 上游：同步调用栈（本来就记）＋ 异步来源（谁 post / execute 了现在跑的这段代码，一层层往上追）。
 * 下游：锚点执行期间 ① 同一个类、以及 this 里同包字段所属类的方法被调用的先后顺序（临时钩，只在锚点执行期间才记）；
 *       ② 期间发生的 setText / 清空 / 图标 / 状态变化（“效果”）；③ 期间投递出去的异步任务，以及这些任务稍后跑起来后做的事。
 *
 * 全部是开关：chainOn 总开关，chainAsync / chainEffects / chainDown 三个子开关。默认全关，关着的时候零开销。
 * 只对“方法追踪”目标生效，而且是采样的（前几次每次都建，之后每 chainEvery 次建一次）。
 */
final class Chain {
    private Chain() {
    }

    static volatile boolean on, async, effects, down, coroutine;
    static volatile int every = 20, maxDown = 120;

    private static final int FRAMES = 8;
    private static final long CONT_MS = 3000;

    // ---------------------------------------------------------------- 数据

    /** 一次异步投递的来源。 */
    static final class Origin {
        final String thread;
        final String how;
        final String[] frames;
        final Origin prev;      // 投递者自己又是被谁投递的
        final Ctx parent;       // 如果是在某个锚点执行期间投递的，指回那次链
        final long at = SystemClock.uptimeMillis();

        Origin(String thread, String how, String[] frames, Origin prev, Ctx parent) {
            this.thread = thread;
            this.how = how;
            this.frames = frames;
            this.prev = prev;
            this.parent = parent;
        }
    }

    /** 一次锚点调用对应的链。 */
    static final class Ctx {
        final String anchor;
        final String thread;
        final Origin origin;
        final long t0 = SystemClock.uptimeMillis();
        final int nth;
        final List<String> lines = new ArrayList<>();
        int depth;
        int calls;
        int re;
        long cost = -1;
        volatile Rec rec;
        boolean cont;       // 异步续跑
        Ctx parent;

        Ctx(String anchor, String thread, Origin origin, int nth) {
            this.anchor = anchor;
            this.thread = thread;
            this.origin = origin;
            this.nth = nth;
        }

        synchronized void add(String s) {
            if (lines.size() < 200) lines.add(s);
        }
    }

    private static final ThreadLocal<Ctx> CUR = new ThreadLocal<>();
    private static final ThreadLocal<Origin> ORIGIN = new ThreadLocal<>();
    private static final Map<Object, Origin> MSGS = new WeakHashMap<>();
    private static final Map<Object, Origin> RUNS = new WeakHashMap<>();
    private static final Map<String, int[]> COUNT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<String> DOWN_DONE = java.util.Collections.synchronizedSet(new HashSet<String>());
    private static volatile boolean asyncHooked;

    // 异步来源的抓栈限流（锚点执行期间投递的不受限）
    private static long winStart;
    private static int winCount;

    static void configure(RecConfig c) {
        on = c.chainOn;
        async = c.chainOn && c.chainAsync;
        effects = c.chainOn && c.chainEffects;
        down = c.chainOn && c.chainDown;
        coroutine = c.chainOn && c.chainAsync && c.chainCoroutine;
        every = Math.max(1, c.chainEvery);
        maxDown = Math.max(10, Math.min(1000, c.chainMaxDown));
        if (on && async) hookAsync();
    }

    static Ctx cur() {
        return CUR.get();
    }

    // ---------------------------------------------------------------- 锚点进出

    /** 锚点方法进入（before 钩子里调）。 */
    static void enter(Member m, String tkey) {
        if (!on) return;
        Ctx cc = CUR.get();
        if (cc != null) { // 嵌套 / 递归的锚点：外层那次已经在记
            if (!cc.cont && cc.anchor.equals(tkey)) cc.re++;
            return;
        }
        int[] cnt = COUNT.get(tkey);
        if (cnt == null) {
            cnt = new int[1];
            COUNT.put(tkey, cnt);
        }
        int n = ++cnt[0];
        if (n > 5 && n % every != 0) return;
        Thread t = Thread.currentThread();
        Ctx c = new Ctx(tkey, t.getName() + (Looper.myLooper() == Looper.getMainLooper() ? "(主)" : ""), ORIGIN.get(), n);
        CUR.set(c);
        if (down) installDown(m.getDeclaringClass(), tkey);
    }

    /** 锚点方法返回（after 钩子里调）。返回这次的链；不是这个锚点建的链就返回 null。 */
    static Ctx exit(String tkey) {
        Ctx c = CUR.get();
        if (c == null || c.cont || !c.anchor.equals(tkey)) return null;
        if (c.re > 0) {
            c.re--;
            return null;
        }
        c.cost = SystemClock.uptimeMillis() - c.t0;
        CUR.remove();
        return c;
    }

    /** 记录对象建好之后把链挂上去。 */
    static void attach(Ctx c, Rec r) {
        if (c == null || r == null) return;
        c.rec = r;
        r.chain = render(c);
    }

    // ---------------------------------------------------------------- 下游：效果

    static void effect(String s) {
        if (!effects) return;
        Ctx c = CUR.get();
        if (c == null) return;
        c.add(indent(c) + "★ " + cut(s, 80));
        finishCont(c);
    }

    private static String indent(Ctx c) {
        StringBuilder b = new StringBuilder("  ");
        for (int i = 0; i < c.depth && i < 12; i++) b.append("  ");
        return b.toString();
    }

    private static String cut(String s, int n) {
        if (s == null) return "null";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ---------------------------------------------------------------- 下游：临时钩住锚点附近的方法

    private static final XC_MethodHook DOWN_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            Ctx c = CUR.get();
            if (c == null) return;
            if (c.calls >= maxDown) {
                if (c.calls == maxDown) c.add("  … 被调用的方法超过 " + maxDown + " 个，后面不再列");
                c.calls++;
                return;
            }
            c.calls++;
            Member m = p.method;
            c.add(indent(c) + "→ " + simple(m.getDeclaringClass().getName()) + "#" + m.getName());
            c.depth++;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            Ctx c = CUR.get();
            if (c == null) return;
            if (c.depth > 0 && c.calls <= maxDown) c.depth--;
        }
    };

    private static String simple(String cn) {
        int i = cn.lastIndexOf('.');
        return i >= 0 ? cn.substring(i + 1) : cn;
    }

    private static String pkg3(String cn) {
        int n = 0;
        for (int i = 0; i < cn.length(); i++) {
            if (cn.charAt(i) == '.' && ++n == 3) return cn.substring(0, i);
        }
        return cn;
    }

    private static void installDown(Class<?> k, String tkey) {
        if (!DOWN_DONE.add(k.getName())) return;
        try {
            Set<Class<?>> targets = new java.util.LinkedHashSet<>();
            targets.add(k);
            String pk = pkg3(k.getName());
            for (Field f : k.getDeclaredFields()) {
                if (targets.size() >= 8) break;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray() || ft.isInterface()) continue;
                String fn = ft.getName();
                if (fn.startsWith("java.") || fn.startsWith("android.") || fn.startsWith("kotlin.")) continue;
                if (!fn.startsWith(pk)) continue;
                targets.add(ft);
            }
            int hooked = 0;
            for (Class<?> t : targets) {
                for (Method m : t.getDeclaredMethods()) {
                    if (hooked >= 300) break;
                    int mod = m.getModifiers();
                    if (Modifier.isAbstract(mod) || Modifier.isNative(mod) || m.isSynthetic() || m.isBridge()) continue;
                    if ((tkey).equals(t.getName() + "#" + m.getName())) continue; // 锚点自己不重复钩
                    try {
                        XposedBridge.hookMethod(m, DOWN_HOOK);
                        hooked++;
                    } catch (Throwable ignored) {
                    }
                }
            }
            XposedBridge.log("[TextRec] chain down hooked " + hooked + " methods in " + targets.size() + " classes (" + k.getName() + ")");
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 异步来源 / 去向

    private static final XC_MethodHook ENQUEUE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof Message)) return;
                Message msg = (Message) p.args[0];
                String how = "Handler → " + (msg.getCallback() != null ? msg.getCallback().getClass().getName()
                        : (msg.getTarget() != null ? msg.getTarget().getClass().getName() : "?") + " what=" + msg.what);
                Origin o = makeOrigin(how);
                if (o == null) return;
                synchronized (MSGS) {
                    MSGS.put(msg, o);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook DISPATCH = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.args == null || p.args.length < 1 || p.args[0] == null) return;
                Origin o;
                synchronized (MSGS) {
                    o = MSGS.remove(p.args[0]);
                }
                begin(o);
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            end();
        }
    };

    private static final XC_MethodHook POOL_EXEC = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.args == null || p.args.length < 1 || p.args[0] == null) return;
                Origin o = makeOrigin("Executor.execute → " + p.args[0].getClass().getName());
                if (o == null) return;
                synchronized (RUNS) {
                    RUNS.put(p.args[0], o);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook POOL_BEFORE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.args == null || p.args.length < 2 || p.args[1] == null) return;
                Origin o;
                synchronized (RUNS) {
                    o = RUNS.remove(p.args[1]);
                }
                begin(o);
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook POOL_AFTER = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            end();
        }
    };

    private static Origin makeOrigin(String how) {
        Ctx c = CUR.get();
        if (c == null) {
            long now = SystemClock.elapsedRealtime();
            synchronized (Chain.class) {
                if (now - winStart >= 1000) {
                    winStart = now;
                    winCount = 0;
                }
                if (++winCount > 300) return null; // 非链路内的投递限流
            }
        }
        List<StackTraceElement> raw = Stacks.capture(true);
        Stacks.Stk s = Stacks.build(raw, FRAMES, false);
        Origin prev = ORIGIN.get();
        Thread t = Thread.currentThread();
        Origin o = new Origin(t.getName(), how, s.lines, prev, c);
        if (c != null) {
            c.add(indent(c) + "↗ 异步投递：" + cut(how, 90));
        }
        return o;
    }

    /** 异步任务开始跑：记住来源；如果它是链路内投递的，就接着记成这条链的续集。 */
    private static void begin(Origin o) {
        ORIGIN.set(o);
        if (o == null || !down || o.parent == null) return;
        Ctx par = o.parent;
        if (par.rec == null || SystemClock.uptimeMillis() - o.at > CONT_MS) return;
        if (CUR.get() != null) return;
        Ctx c = new Ctx(par.anchor, Thread.currentThread().getName(), o, par.nth);
        c.cont = true;
        c.parent = par;
        CUR.set(c);
    }

    private static void end() {
        Ctx c = CUR.get();
        if (c != null && c.cont) {
            finishCont(c);
            CUR.remove();
        }
        ORIGIN.remove();
    }

    /** 续集里新记了东西，并回母链并刷新文字。 */
    private static void finishCont(Ctx c) {
        if (!c.cont || c.parent == null) return;
        Ctx par = c.parent;
        Rec r = par.rec;
        if (r == null) return;
        synchronized (par) {
            int from = c.lines.size();
            for (int i = 0; i < from; i++) {
                par.add("  ↳ [异步 +" + (c.t0 - par.t0) + "ms · " + c.thread + "] " + c.lines.get(i).trim());
            }
            c.lines.clear();
            c.depth = 0;
        }
        r.chain = render(par);
        Recorder.markChainDirty();
    }

    // ---------------------------------------------------------------- 协程调度（尽力而为）
    /**
     * kotlinx.coroutines 的 Default / IO 调度器用自己的线程池（CoroutineScheduler），不走 ThreadPoolExecutor，
     * 所以单靠上面的 Executor 钩子看不到它投递的任务。这里钩 CoroutineScheduler.dispatch（记投递来源）和
     * TaskImpl.run（任务开始跑时接上来源）。Main 调度器最终走 Handler，已经被 Handler 钩子覆盖。
     * 类名 / 字段名被混淆或没有用协程时找不到，只写一行日志，不影响别的；回到前台时会再试几次。
     */
    private static volatile boolean coHooked;
    private static volatile int coTries;
    private static volatile java.lang.reflect.Field coBlock;

    static void coroutineHook(ClassLoader cl) {
        if (cl == null || coHooked || coTries >= 6) return;
        if (!on || !async || !coroutine) return;
        coTries++;
        try {
            Class<?> sch = Class.forName("kotlinx.coroutines.scheduling.CoroutineScheduler", false, cl);
            Class<?> task = Class.forName("kotlinx.coroutines.scheduling.TaskImpl", false, cl);
            int n = 0;
            for (java.lang.reflect.Method m : sch.getDeclaredMethods()) {
                if (!m.getName().equals("dispatch") || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                if (m.getParameterTypes().length < 1 || !Runnable.class.isAssignableFrom(m.getParameterTypes()[0])) continue;
                XposedBridge.hookMethod(m, CO_DISPATCH);
                n++;
            }
            for (java.lang.reflect.Method m : task.getDeclaredMethods()) {
                if (!m.getName().equals("run") || m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, CO_RUN);
                n++;
            }
            try {
                java.lang.reflect.Field f = task.getDeclaredField("block");
                f.setAccessible(true);
                coBlock = f;
            } catch (Throwable e) {
                XposedBridge.log("[TextRec] chain coroutine: TaskImpl.block 字段找不到（可能被混淆），异步来源接不上: " + e);
            }
            coHooked = n > 0;
            XposedBridge.log("[TextRec] chain coroutine hooked: " + n);
        } catch (Throwable e) {
            XposedBridge.log("[TextRec] chain coroutine 类找不到（没用协程或被混淆）: " + e);
        }
    }

    private static final XC_MethodHook CO_DISPATCH = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (p.args == null || p.args.length < 1 || p.args[0] == null) return;
                Origin o = makeOrigin("Coroutine.dispatch → " + p.args[0].getClass().getName());
                if (o == null) return;
                synchronized (RUNS) {
                    RUNS.put(p.args[0], o);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook CO_RUN = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) {
            try {
                java.lang.reflect.Field f = coBlock;
                Origin o = null;
                if (f != null && p.thisObject != null) {
                    Object blk = f.get(p.thisObject);
                    if (blk != null) {
                        synchronized (RUNS) {
                            o = RUNS.remove(blk);
                        }
                    }
                }
                begin(o);
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            end();
        }
    };

    private static void hookAsync() {
        if (asyncHooked) return;
        synchronized (Chain.class) {
            if (asyncHooked) return;
            asyncHooked = true;
        }
        try {
            XposedBridge.hookAllMethods(android.os.MessageQueue.class, "enqueueMessage", ENQUEUE);
            XposedBridge.hookAllMethods(android.os.Handler.class, "dispatchMessage", DISPATCH);
        } catch (Throwable e) {
            XposedBridge.log("[TextRec] chain handler hook failed: " + e);
        }
        try {
            XposedBridge.hookAllMethods(java.util.concurrent.ThreadPoolExecutor.class, "execute", POOL_EXEC);
            XposedBridge.hookAllMethods(java.util.concurrent.ThreadPoolExecutor.class, "beforeExecute", POOL_BEFORE);
            XposedBridge.hookAllMethods(java.util.concurrent.ThreadPoolExecutor.class, "afterExecute", POOL_AFTER);
        } catch (Throwable e) {
            XposedBridge.log("[TextRec] chain executor hook failed: " + e);
        }
    }

    // ---------------------------------------------------------------- 渲染

    static String render(Ctx c) {
        StringBuilder sb = new StringBuilder();
        sb.append("锚点 ").append(c.anchor).append("  线程 ").append(c.thread)
                .append("  第 ").append(c.nth).append(" 次调用（采样）");
        if (c.cost >= 0) sb.append("  耗时 ").append(c.cost).append("ms");
        sb.append('\n');
        sb.append("上游\n");
        sb.append("  · 同步调用栈：见上面的“调用栈”\n");
        Origin o = c.origin;
        int lvl = 0;
        if (o == null) {
            sb.append(async ? "  · 异步来源：这次调用不是从 Handler / 线程池任务里跑起来的（或来源没抓到，限流时会漏）\n"
                    : "  · 异步来源：没开“异步来源”开关\n");
        }
        while (o != null && lvl < 6) {
            sb.append("  ").append(lvl == 0 ? "↖ " : "   ↖ ").append("由线程 ").append(o.thread).append(" 投递：").append(cut(o.how, 100)).append('\n');
            for (String f : o.frames) sb.append("        at ").append(f.trim()).append('\n');
            o = o.prev;
            lvl++;
        }
        sb.append("下游\n");
        synchronized (c) {
            if (c.lines.isEmpty()) {
                sb.append(down || effects ? "  （这次调用期间没观察到被调用的方法 / 界面效果）\n" : "  （下游开关都没开）\n");
            } else {
                for (String s : c.lines) sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

}
