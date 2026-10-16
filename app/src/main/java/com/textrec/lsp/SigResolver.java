package com.textrec.lsp;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调用栈里只有“类名+方法名”，没有参数类型，而 hook 需要参数类型。
 * 这里在后台线程用反射查出栈顶几个 App 帧对应的方法签名：只有一个就是唯一签名，有重载就全部列出。
 */
final class SigResolver {
    private SigResolver() {}

    private static final CopyOnWriteArrayList<WeakReference<ClassLoader>> LOADERS = new CopyOnWriteArrayList<>();
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "textrec-sig");
        t.setDaemon(true);
        return t;
    });

    static void addLoader(ClassLoader cl) {
        if (cl == null) return;
        for (WeakReference<ClassLoader> r : LOADERS) {
            if (r.get() == cl) return;
        }
        LOADERS.add(new WeakReference<>(cl));
    }

    static void resolveSet(final SetStack s, final ClassLoader hint) {
        final String[][] top = s.stk.top;
        if (top.length == 0) return;
        EX.execute(() -> {
            try {
                s.sigs = resolve(top, hint);
                Recorder.store.markDirty();
            } catch (Throwable ignored) {
            }
        });
    }

    static void resolveRec(final Rec r, final ClassLoader hint) {
        final String[][] top = r.draw.top;
        if (top.length == 0) return;
        EX.execute(() -> {
            try {
                r.drawSigs = resolve(top, hint);
                Recorder.store.markDirty();
            } catch (Throwable ignored) {
            }
        });
    }

    private static String[] resolve(String[][] top, ClassLoader hint) {
        ArrayList<String> out = new ArrayList<>();
        for (String[] f : top) {
            String cn = f[0], mn = f[1];
            String tag = Stacks.tagFor(cn, mn);
            String layer = f.length > 2 ? "  @栈第" + f[2] + "层" : "";
            Class<?> c = load(cn, hint);
            if (c == null) {
                out.add(cn + "#" + mn + "  (类未能加载，无法查签名)" + layer + tag);
                continue;
            }
            int before = out.size();
            try {
                describe(c, cn, mn, out);
            } catch (Throwable t) {
                out.add(cn + "#" + mn + "  (反射失败: " + t.getClass().getSimpleName() + ")");
            }
            for (int i = before; i < out.size(); i++) out.set(i, out.get(i) + layer + tag);
        }
        // 去重（同一个方法被多个栈帧引用时会重复列出）
        return new java.util.ArrayList<>(new java.util.LinkedHashSet<>(out)).toArray(new String[0]);
    }

    static Class<?> findClass(String cn, ClassLoader hint) {
        return load(cn, hint);
    }

    private static Class<?> load(String cn, ClassLoader hint) {
        if (hint != null) {
            try {
                return Class.forName(cn, false, hint);
            } catch (Throwable ignored) {
            }
        }
        for (WeakReference<ClassLoader> r : LOADERS) {
            ClassLoader cl = r.get();
            if (cl == null) continue;
            try {
                return Class.forName(cn, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void describe(Class<?> c, String cn, String mn, ArrayList<String> out) {
        int before = out.size();
        if (mn.equals("<init>")) {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                out.add(cn + "#<init>(" + params(k.getParameterTypes()) + ")");
                if (out.size() - before >= 6) break;
            }
        } else if (mn.equals("<clinit>")) {
            out.add(cn + "#<clinit>");
        } else {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(mn)) continue;
                out.add(cn + "#" + mn + "(" + params(m.getParameterTypes()) + "):" + m.getReturnType().getTypeName());
                if (out.size() - before >= 6) break;
            }
        }
        if (out.size() == before) out.add(cn + "#" + mn + "  (类里没找到同名方法)");
    }

    private static String params(Class<?>[] pt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(pt[i].getTypeName());
        }
        return sb.toString();
    }
}
