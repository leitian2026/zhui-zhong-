package com.tracelog.lsp;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 指定类追踪：配置里写 "类名" 或 "类名#方法名"，把这个类的所有（或指定）方法挂上，
 * 每次调用记录 参数 + 返回值。类可能稍后才加载（插件/动态 dex），找不到的会挂起等待，
 * 在 Activity 恢复或有新的 ClassLoader 创建时重试。
 */
final class ClassTracer {
    private ClassTracer() {}

    private static final CopyOnWriteArrayList<WeakReference<ClassLoader>> LOADERS = new CopyOnWriteArrayList<>();
    private static final Set<String> DONE = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final CopyOnWriteArrayList<CallHook> HOOKS = new CopyOnWriteArrayList<>();
    private static volatile Set<String> wanted = Collections.emptySet();
    private static volatile List<String> specs = Collections.emptyList();
    private static boolean loaderHooked;

    static void addLoader(ClassLoader cl) {
        if (cl == null) return;
        for (WeakReference<ClassLoader> r : LOADERS) {
            if (r.get() == cl) return;
        }
        LOADERS.add(new WeakReference<>(cl));
    }

    static synchronized void setSpecs(List<String> s) {
        specs = new ArrayList<>(s);
        wanted = new HashSet<>(s); // 已挂的 Hook 不卸载，只靠 wanted 开关；删掉再加回来立即恢复
        if (!s.isEmpty() && !loaderHooked) {
            loaderHooked = true;
            try {
                XposedBridge.hookAllConstructors(Class.forName("dalvik.system.BaseDexClassLoader"), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.thisObject instanceof ClassLoader) {
                                addLoader((ClassLoader) p.thisObject);
                                if (pending()) TraceLog.async(ClassTracer::tryInstall);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[TraceLog] hook BaseDexClassLoader failed: " + t);
            }
        }
    }

    static void resetCounters() {
        for (CallHook h : HOOKS) {
            h.cnt.set(0);
            h.min = Long.MAX_VALUE;
            h.max = Long.MIN_VALUE;
            h.last = 0;
        }
    }

    private static boolean pending() {
        for (String s : specs) if (!DONE.contains(s)) return true;
        return false;
    }

    static synchronized void tryInstall() {
        for (String spec : specs) {
            if (DONE.contains(spec)) continue;
            try {
                installOne(spec);
            } catch (Throwable t) {
                DONE.add(spec);
                TraceLog.log("HOOKFAIL", spec + " -> " + t, null);
            }
        }
    }

    private static void installOne(String spec) {
        String cn = spec, mn = "";
        int i = spec.indexOf('#');
        if (i >= 0) {
            cn = spec.substring(0, i).trim();
            mn = spec.substring(i + 1).trim();
        }
        Class<?> cls = null;
        for (WeakReference<ClassLoader> r : LOADERS) {
            ClassLoader cl = r.get();
            if (cl == null) continue;
            try {
                cls = Class.forName(cn, false, cl);
                break;
            } catch (Throwable ignored) {
            }
        }
        if (cls == null) return; // 还没加载，保持挂起，之后重试
        DONE.add(spec);
        boolean all = mn.isEmpty() || mn.equals("*");
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) || m.isBridge()) continue;
            if (!all && !mn.equals(m.getName())) continue;
            try {
                CallHook h = new CallHook(spec, label(cn, m.getName(), m.getParameterTypes()));
                HOOKS.add(h);
                XposedBridge.hookMethod(m, h);
                n++;
            } catch (Throwable ignored) {
            }
        }
        if (all || mn.equals("<init>")) {
            for (Constructor<?> c : cls.getDeclaredConstructors()) {
                try {
                    CallHook h = new CallHook(spec, label(cn, "<init>", c.getParameterTypes()));
                    HOOKS.add(h);
                    XposedBridge.hookMethod(c, h);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        TraceLog.log("HOOKED", spec + " -> " + n + " methods", null);
    }

    private static String label(String cn, String name, Class<?>[] pt) {
        StringBuilder sb = new StringBuilder(cn).append('.').append(name).append('(');
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pt[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static final class CallHook extends XC_MethodHook {
        /** 第一个数值参数的 last / min / max（拖到两端就能看出满量程），每隔 dumpEvery 次连同 this 的字段值记一份。 */
        private void dumpHook(MethodHookParam p, int n, TraceConfig c) {
            try {
                Object[] a = p.args;
                if (a != null && a.length > 0 && a[0] instanceof Number) {
                    long v = ((Number) a[0]).longValue();
                    last = v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
                if (n != 1 && n % Math.max(1, c.dumpEvery) != 0) return;
                Util.BUSY.set(Boolean.TRUE);
                try {
                    String head = label + " 第" + n + "次 arg0: last=" + last
                            + " min=" + (min == Long.MAX_VALUE ? "-" : String.valueOf(min))
                            + " max=" + (max == Long.MIN_VALUE ? "-" : String.valueOf(max));
                    String body = p.thisObject == null ? "" : Util.dumpFields(p.thisObject, 40);
                    TraceLog.log("FIELDS", head, body);
                } finally {
                    Util.BUSY.set(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }
        }

        final String spec, label;
        final AtomicInteger cnt = new AtomicInteger();
        volatile long min = Long.MAX_VALUE, max = Long.MIN_VALUE, last;

        CallHook(String spec, String label) {
            this.spec = spec;
            this.label = label;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) {
            try {
                if (!wanted.contains(spec)) return;
                TraceConfig c = Tracer.cfg;
                if (!c.enabled) return;
                if (Boolean.TRUE.equals(Util.BUSY.get())) return; // 防止 toString 又触发 Hook 造成递归
                int n = cnt.incrementAndGet();
                if (c.dumpFields) dumpHook(p, n, c); // 不受「每个方法最多记录次数」限制：满额之后仍然统计、仍然每隔 N 次记一份
                if (n > c.maxPerMethod) {
                    if (n == c.maxPerMethod + 1) TraceLog.log("CAP", label + " 已达上限 " + c.maxPerMethod + "，不再记录", null);
                    return;
                }
                Util.BUSY.set(Boolean.TRUE);
                try {
                    StringBuilder sb = new StringBuilder(label).append(" args=[");
                    Object[] a = p.args;
                    if (a != null) {
                        for (int i = 0; i < a.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(Util.str(a[i]));
                        }
                    }
                    sb.append(']');
                    if (p.hasThrowable()) sb.append(" threw ").append(Util.clean(String.valueOf(p.getThrowable()), 120));
                    else if (p.method instanceof Method && ((Method) p.method).getReturnType() != void.class)
                        sb.append(" => ").append(Util.str(p.getResult()));
                    String stack = c.classStack ? Util.format(Util.capture(true), c.stackDepth) : null;
                    TraceLog.log("CALL", sb.toString(), stack);
                } finally {
                    Util.BUSY.set(Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
