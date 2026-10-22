package com.textrec.lsp;

import android.app.Activity;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标注（1.29.0，目标进程里用）：给记录加语义标签，让找钩子更快更准。全部只读，不改目标 App。
 * <ol>
 *   <li>类角色：Activity / Fragment / ViewHolder / Adapter / ViewModel / LiveData / View / Listener(…) / Lambda(…) / 数据实体 …</li>
 *   <li>钩点评级：a 覆写框架方法（最稳）/ b 稳定业务 / c 通用基础设施 / d 混淆 / x 未知。</li>
 *   <li>栈帧分层：框架 / 事件分发 / 调度 / 语言库 / 基础设施 / 通用控件 / 业务 / 数据，标 ★入口帧、◎推荐钩点、↯消息 / 线程入口。</li>
 *   <li>对象归属：方法追踪命中时，反查 this / 参数被谁持有（后台线程，有时间上限）；任务开始 / 结束时扫「容器 → 数据实体」。</li>
 * </ol>
 */
final class Annot {
    private Annot() {}

    // ------------------------------------------------------------ 公共小工具

    static String simple(String cn) {
        int i = cn.lastIndexOf('.');
        return i >= 0 ? cn.substring(i + 1) : cn;
    }

    /** 没混淆的类显示短名，混淆的类（X.abc、a.b.c）显示全名，免得 abc 满屏分不清。 */
    static String nice(String cn) {
        return Stacks.isObfuscated(cn, null) ? cn : simple(cn);
    }

    private static boolean isLangLib(String cn) {
        return cn.startsWith("kotlin.") || cn.startsWith("kotlinx.");
    }

    /** 业务类：不是系统 / 语言库 / androidx / 本模块。 */
    static boolean isApp(String cn) {
        return !Stacks.isFramework(cn) && !Stacks.isInfra(cn) && !isLangLib(cn)
                && !cn.startsWith("androidx.") && !cn.startsWith("org.json.") && !cn.startsWith("com.google.android.");
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ------------------------------------------------------------ 反射小工具（带缓存）

    private static final Method NO_METHOD;

    static {
        Method m = null;
        try {
            m = Object.class.getMethod("hashCode");
        } catch (Throwable ignored) {
        }
        NO_METHOD = m;
    }

    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();

    /** 沿父类链找一个无参方法（含非 public）；找不到返回 null。 */
    private static Method findMethod(Class<?> k, String name) {
        String key = k.getName() + "#" + name;
        Method m = METHODS.get(key);
        if (m != null) return m == NO_METHOD ? null : m;
        m = null;
        try {
            for (Class<?> c = k; c != null && c != Object.class && m == null; c = c.getSuperclass()) {
                for (Method d : c.getDeclaredMethods()) {
                    if (d.getName().equals(name) && d.getParameterTypes().length == 0) {
                        d.setAccessible(true);
                        m = d;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        METHODS.put(key, m == null ? NO_METHOD : m);
        return m;
    }

    /** 沿父类链读一个字段（含非 public），读不到返回 null。 */
    private static Object fieldAny(Class<?> k, Object o, String name) {
        try {
            for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 业务类链上的实例引用字段（不含基本类型），带缓存，最多 80 个。 */
    private static Field[] fieldsOf(Class<?> k) {
        Field[] f = FIELDS.get(k);
        if (f != null) return f;
        ArrayList<Field> l = new ArrayList<>();
        try {
            for (Class<?> c = k; c != null && c != Object.class && isApp(c.getName()); c = c.getSuperclass()) {
                for (Field fd : c.getDeclaredFields()) {
                    if (Modifier.isStatic(fd.getModifiers()) || fd.getType().isPrimitive()) continue;
                    try {
                        fd.setAccessible(true);
                        if (l.size() < 80) l.add(fd);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        f = l.toArray(new Field[0]);
        FIELDS.put(k, f);
        return f;
    }

    /** 父类 + 接口的全名（广度优先，最多 64 个）；值 = 是不是接口。 */
    private static LinkedHashMap<String, Boolean> supers(Class<?> k) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        try {
            ArrayList<Class<?>> q = new ArrayList<>();
            q.add(k);
            for (int i = 0; i < q.size() && out.size() < 64; i++) {
                Class<?> c = q.get(i);
                Class<?> s = c.getSuperclass();
                if (s != null && s != Object.class && !out.containsKey(s.getName())) {
                    out.put(s.getName(), false);
                    q.add(s);
                }
                for (Class<?> f : c.getInterfaces()) {
                    if (!out.containsKey(f.getName())) {
                        out.put(f.getName(), true);
                        q.add(f);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------ 1. 类角色

    private static final ConcurrentHashMap<Class<?>, String> ROLE_BY_CLASS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ROLE_BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> NOT_FOUND = new ConcurrentHashMap<>();
    private static final Pattern LISTENER_NAME = Pattern.compile(".*(Listener|Callback|Observer|Watcher|Delegate|Receiver|Interceptor|Consumer).*");
    private static final Pattern LOGIC_NAME = Pattern.compile("(Presenter|Repository|UseCase|Interactor|Controller|Manager|Helper|Utils?|Factory|Provider|Service|Dispatcher|Tracker|Handler|Processor|Engine)$");
    private static final Pattern ENTITY_NAME = Pattern.compile("(Aweme|Model|Bean|Entity|Info|Item|Video|User|Music|Comment|Response|Result|Config|Event|Params?|State|Data|Vo|Dto|Meta)s?$");

    static String roleOf(Class<?> k) {
        if (k == null) return "?";
        String r = ROLE_BY_CLASS.get(k);
        if (r != null) return r;
        try {
            r = computeRole(k);
        } catch (Throwable t) {
            r = "?";
        }
        ROLE_BY_CLASS.put(k, r);
        return r;
    }

    /** 按类名查角色（要把类加载出来，不初始化）；类加载不出来返回 "?"，最多重试 3 次。 */
    static String roleOfName(String cn) {
        String r = ROLE_BY_NAME.get(cn);
        if (r != null) return r;
        Integer tried = NOT_FOUND.get(cn);
        if (tried != null && tried >= 3) return "?";
        Class<?> k = SigResolver.findClass(cn, null);
        if (k == null) {
            NOT_FOUND.put(cn, tried == null ? 1 : tried + 1);
            return "?";
        }
        r = roleOf(k);
        ROLE_BY_NAME.put(cn, r);
        return r;
    }

    private static String computeRole(Class<?> k) {
        String cn = k.getName();
        if (cn.contains("$$ExternalSyntheticLambda") || cn.contains("$$Lambda")) {
            Class<?>[] is = k.getInterfaces();
            return "Lambda(" + (is.length > 0 ? nice(is[0].getName()) : "?") + ")";
        }
        LinkedHashMap<String, Boolean> sup = supers(k);
        if (sup.containsKey("android.app.Activity")) return "Activity";
        if (sup.containsKey("androidx.fragment.app.Fragment") || sup.containsKey("android.app.Fragment")) return "Fragment";
        if (sup.containsKey("androidx.recyclerview.widget.RecyclerView$ViewHolder")) return "ViewHolder";
        if (sup.containsKey("androidx.recyclerview.widget.RecyclerView$Adapter") || sup.containsKey("android.widget.BaseAdapter")
                || sup.containsKey("androidx.viewpager.widget.PagerAdapter") || sup.containsKey("androidx.viewpager2.adapter.FragmentStateAdapter")) {
            return "Adapter";
        }
        if (sup.containsKey("androidx.lifecycle.ViewModel")) return "ViewModel";
        if (sup.containsKey("androidx.lifecycle.LiveData")) return "LiveData";
        if (sup.containsKey("android.view.View")) return "View";
        if (sup.containsKey("android.os.Handler")) return "Handler";
        if (sup.containsKey("android.app.Service")) return "Service";
        String fw = null, app = null;
        for (Map.Entry<String, Boolean> e : sup.entrySet()) {
            if (!e.getValue()) continue;
            String s = simple(e.getKey());
            if (!LISTENER_NAME.matcher(s).matches()) continue;
            if (e.getKey().startsWith("android.") || e.getKey().startsWith("androidx.")) {
                if (fw == null) fw = s;
            } else if (app == null) {
                app = s;
            }
        }
        if (fw != null) return "Listener(" + fw + ")";
        if (app != null) return "Listener(" + app + ")";
        if (sup.containsKey("java.lang.Runnable")) return "Runnable";
        if (sup.containsKey("java.util.concurrent.Callable")) return "Callable";
        if (sup.containsKey("java.util.concurrent.Executor")) return "线程池";
        if (isApp(cn)) {
            String sn = simple(cn);
            int d = sn.indexOf('$');
            if (d > 0) sn = sn.substring(0, d);
            Matcher m = LOGIC_NAME.matcher(sn);
            if (m.find()) {
                String w = m.group(1);
                return w.startsWith("Util") ? "工具类" : w;
            }
            if (ENTITY_NAME.matcher(sn).find() || looksLikeData(k)) return "数据实体";
        }
        return "普通类";
    }

    /** 字段多、几乎没有逻辑方法（只有 getter / setter / equals…）= 数据实体；混淆后名字认不出时靠这个。 */
    private static boolean looksLikeData(Class<?> k) {
        try {
            int fields = 0;
            for (Class<?> c = k; c != null && c != Object.class && isApp(c.getName()); c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers())) fields++;
            }
            if (fields < 4) return false;
            int logic = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                String n = m.getName();
                int pc = m.getParameterTypes().length;
                boolean isVoid = m.getReturnType() == void.class;
                if (pc == 0 && !isVoid) continue; // getter（混淆后名字不可靠，按形状认）
                if (pc == 1 && isVoid) continue;  // setter
                if (n.equals("equals") || n.equals("hashCode") || n.equals("toString") || n.equals("describeContents")
                        || n.equals("writeToParcel") || n.equals("compareTo") || n.startsWith("copy") || n.startsWith("component")) continue;
                logic++;
            }
            return logic <= 3;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isContainerRole(String r) {
        return r.equals("Fragment") || r.equals("ViewHolder") || r.equals("ViewModel") || r.equals("Activity") || r.equals("Adapter");
    }

    // ------------------------------------------------------------ 2. 钩点评级

    private static final ConcurrentHashMap<String, String[]> GRADE = new ConcurrentHashMap<>();

    private static boolean declares(Class<?> c, String mn) {
        try {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(mn)) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 评级：{码, 说明}。码：a 覆写 / 实现框架方法（名字由框架定，换版本不变，最稳）；b 稳定业务（类名方法名都没混淆，或覆写了没混淆的父类方法）；
     * c 通用基础设施 / 通用控件 / 整类追踪；d 混淆（换版本会变）；x 类没加载出来。
     */
    static String[] grade(String cn, String mn) {
        String key = cn + "#" + mn;
        String[] g = GRADE.get(key);
        if (g != null) return g;
        try {
            g = computeGrade(cn, mn);
        } catch (Throwable t) {
            g = new String[]{"x", "评级失败：" + t.getClass().getSimpleName()};
        }
        if (!"x".equals(g[0])) GRADE.put(key, g);
        return g;
    }

    private static String[] computeGrade(String cn, String mn) {
        if ("*".equals(mn)) return new String[]{"c", "整类追踪：这一类自己声明的每个方法各一条记录，每条另看稳定度"};
        Class<?> k = SigResolver.findClass(cn, null);
        if (k == null) return new String[]{"x", "类还没加载出来，评级未知（进入相关页面后会重试）"};
        boolean obfC = Stacks.isObfuscated(cn, null);
        boolean obfM = Stacks.isObfuscated("com.x.Placeholder", mn);
        String declName = null;
        boolean declFw = false;
        if (!mn.equals("<init>") && !mn.equals("<clinit>")) {
            for (String sn : supers(k).keySet()) {
                Class<?> sc = SigResolver.findClass(sn, k.getClassLoader());
                if (sc == null || !declares(sc, mn)) continue;
                boolean fw = Stacks.isFramework(sn) || sn.startsWith("androidx.") || isLangLib(sn);
                if (fw || !Stacks.isObfuscated(sn, mn)) {
                    declName = sn;
                    declFw = fw;
                    break;
                }
            }
        }
        StringBuilder extra = new StringBuilder();
        try {
            int cnt = 0;
            boolean stat = false, fin = false, abs = false;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(mn)) continue;
                cnt++;
                int md = m.getModifiers();
                stat |= Modifier.isStatic(md);
                fin |= Modifier.isFinal(md);
                abs |= Modifier.isAbstract(md);
            }
            if (cnt > 1) extra.append("；同名重载 ").append(cnt).append(" 个（全挂）");
            if (stat) extra.append("；静态方法（没有 this，没法按对象过滤）");
            if (fin) extra.append("；final");
            if (abs) extra.append("；抽象（实际执行的类要看 this）");
        } catch (Throwable ignored) {
        }
        if (declName != null && declFw) {
            return new String[]{"a", "覆写 / 实现框架方法 " + declName + "#" + mn + "：名字由框架定，换版本不变；可改钩它，再按 this 的类过滤（现在这个类是 " + cn + (obfC ? "，类名会变" : "") + "）" + extra};
        }
        if (declName != null) {
            return new String[]{"b", "覆写了没混淆的父类 / 接口方法 " + declName + "#" + mn + "：可改钩父类方法并按 this 过滤，比钩子类稳" + extra};
        }
        if (!obfC && !obfM) {
            if (Stacks.isGenericInfra(cn)) return new String[]{"c", "通用基础设施（几乎所有功能都会经过），不建议当钩点" + extra};
            if (Stacks.isGenericWidget(cn)) return new String[]{"c", "通用控件，hook 后要按 View id / 类过滤" + extra};
            return new String[]{"b", "类名、方法名都没混淆，自己声明的方法：换版本大概率还在" + extra};
        }
        StringBuilder sb = new StringBuilder("混淆：");
        if (obfC) sb.append("类名疑似混淆");
        if (obfC && obfM) sb.append("、");
        if (obfM) sb.append("方法名疑似混淆");
        sb.append("；换版本会变。优先看调用栈里标 ◎ 的稳定帧，或改钩它覆写的框架方法");
        return new String[]{"d", sb.toString() + extra};
    }

    // ------------------------------------------------------------ 3. 栈帧分层

    static final String[] LAYER_NAME = {"框架", "事件分发", "调度", "语言库", "基础设施", "通用控件", "业务", "数据"};
    private static final Pattern DISPATCH = Pattern.compile(".*(dispatch|onTouch|onClick|performClick|onKey|onInterceptTouch|Input|deliver).*");
    private static final Pattern ASYNC_ENTRY = Pattern.compile("(run|handleMessage|dispatchMessage|handleCallback|execute|invokeSuspend|doFrame|onReceive|call|accept)");

    private static int layerOf(String cn, String mn) {
        if (Stacks.isInfra(cn)) return 0;
        if (cn.startsWith("kotlinx.coroutines")) return 2;
        if (isLangLib(cn)) return 3;
        if (Stacks.isFramework(cn)) {
            if (cn.startsWith("android.view.Choreographer")) return 2;
            if (cn.startsWith("android.view.")) return DISPATCH.matcher(mn).matches() ? 1 : 0;
            if (cn.startsWith("android.os.") || cn.startsWith("java.util.concurrent") || cn.equals("java.lang.Thread")
                    || cn.startsWith("com.android.internal.os") || cn.startsWith("android.app.ActivityThread")) return 2;
            return 0;
        }
        if (Stacks.isGenericInfra(cn)) return 4;
        if (Stacks.isGenericWidget(cn)) return 5;
        return 6;
    }

    private static boolean dataRole(String r) {
        return r.equals("ViewModel") || r.equals("LiveData") || r.equals("Repository") || r.equals("UseCase") || r.equals("Interactor") || r.equals("数据实体");
    }

    /** 一行栈帧的固定属性（和这一行在哪份栈里无关），按行文字缓存：每次刷新快照都要标全部栈，不能每次重算。 */
    private static final class FB {
        int layer = -1;
        String role, mn;
        boolean cnObf, mnObf;
    }

    private static final FB NO_FB = new FB();
    private static final ConcurrentHashMap<String, FB> BASE = new ConcurrentHashMap<>();

    private static FB baseOf(String l) {
        if (l == null || l.startsWith("[")) return NO_FB;
        FB b = BASE.get(l);
        if (b != null) return b;
        b = new FB();
        int p = l.indexOf('(');
        String head = p > 0 ? l.substring(0, p) : l;
        int d = head.lastIndexOf('.');
        if (d <= 0) return NO_FB;
        String cn = head.substring(0, d), mn = head.substring(d + 1);
        b.mn = mn;
        b.layer = layerOf(cn, mn);
        b.cnObf = Stacks.isObfuscated(cn, null);
        b.mnObf = Stacks.isObfuscated("com.x.Placeholder", mn);
        if (b.layer == 6) {
            b.role = roleOfName(cn);
            if (dataRole(b.role)) b.layer = 7;
        }
        // 业务帧的类还没加载出来（角色是 "?"）时先不缓存，下次刷新再试
        if (b.layer != 6 || !"?".equals(b.role)) {
            if (BASE.size() > 20000) BASE.clear();
            BASE.put(l, b);
        }
        return b;
    }

    /**
     * 给一份栈的每一行标注：〔分层·角色·稳 / 混〕，再加 ★入口帧（最外层的业务帧，业务代码从这里接手）、
     * ◎推荐钩点（业务帧里最稳的）、↯消息 / 线程入口（再往外是别处投递来的）。返回数组和 lines 等长，没标的是空串。
     */
    static String[] frames(String[] lines) {
        int n = lines == null ? 0 : lines.length;
        String[] out = new String[n];
        java.util.Arrays.fill(out, "");
        int m = Math.min(n, 60);
        if (m == 0) return out;
        int[] layer = new int[m];
        String[] role = new String[m], mns = new String[m];
        boolean[] cnObf = new boolean[m], mnObf = new boolean[m];
        for (int i = 0; i < m; i++) {
            FB b = baseOf(lines[i]);
            layer[i] = b.layer;
            role[i] = b.role;
            mns[i] = b.mn;
            cnObf[i] = b.cnObf;
            mnObf[i] = b.mnObf;
        }
        int entry = -1;
        for (int i = m - 1; i >= 0; i--) {
            if (layer[i] == 6 || layer[i] == 7) {
                entry = i;
                break;
            }
        }
        int best = -1, bestScore = -1;
        for (int i = 0; i < m; i++) {
            if (layer[i] != 6 && layer[i] != 7) continue;
            int sc = (!cnObf[i] && !mnObf[i]) ? 4 : (!mnObf[i] ? 2 : 0);
            String rl = role[i] == null ? "?" : role[i];
            if (rl.startsWith("Listener") || isContainerRole(rl) || rl.equals("Presenter")) sc += 2;
            else if (!rl.equals("普通类") && !rl.equals("?")) sc += 1;
            if (rl.startsWith("Lambda")) sc -= 2;
            String mn = mns[i] == null ? "" : mns[i];
            if (mn.contains("lambda$") || mn.contains("$default") || mn.equals("<init>") || mn.equals("<clinit>")) sc -= 3;
            if (sc > bestScore || (sc == bestScore && i > best)) {
                bestScore = sc;
                best = i;
            }
        }
        if (bestScore < 4) best = -1; // 全是混淆帧就不推荐
        int async = -1;
        for (int i = 0; i < m; i++) {
            if (layer[i] == 2 && mns[i] != null && ASYNC_ENTRY.matcher(mns[i]).matches()) {
                async = i;
                break;
            }
        }
        for (int i = 0; i < m; i++) {
            if (layer[i] < 0) continue;
            StringBuilder sb = new StringBuilder("〔").append(LAYER_NAME[layer[i]]);
            if (layer[i] == 6 || layer[i] == 7) {
                String rl = role[i];
                if (rl != null && !rl.equals("普通类") && !rl.equals("?")) sb.append('·').append(rl);
                if (rl == null || !rl.startsWith("Lambda")) sb.append(cnObf[i] || mnObf[i] ? "·混" : "·稳");
                else sb.append(cnObf[i] ? "·混" : "");
            } else if (layer[i] == 4 || layer[i] == 5) {
                if (cnObf[i] || mnObf[i]) sb.append("·混");
            }
            sb.append('〕');
            if (i == entry) sb.append(" ★入口帧");
            if (i == best) sb.append(" ◎推荐钩点");
            if (i == async) sb.append(" ↯消息/线程入口");
            out[i] = sb.toString();
        }
        return out;
    }

    // ------------------------------------------------------------ 4. 对象归属（反查持有者）

    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "textrec-annot");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<String, Integer> CALLS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LinkedHashMap<String, String>> OWN = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> TOP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Entity> ENT = new ConcurrentHashMap<>();
    private static volatile long lastScan, lastSweep;

    private static final int MAX_NODES = 2500, MAX_DEPTH = 4;

    /** 一类数据实体（如 Aweme）：被哪些容器持有、见过几个不同对象。 */
    private static final class Entity {
        final String cls, role;
        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        final LinkedHashSet<String> pages = new LinkedHashSet<>();
        final HashSet<Integer> ids = new HashSet<>();
        int idOver;

        Entity(String cls, String role) {
            this.cls = cls;
            this.role = role;
        }

        synchronized void note(Object o, String path) {
            int id = System.identityHashCode(o);
            if (!ids.contains(id)) {
                if (ids.size() < 32) ids.add(id);
                else idOver++;
            }
            if (path != null && paths.size() < 8) paths.add(path);
            String pg = Recorder.pageName;
            if (pg != null && !pg.isEmpty() && pages.size() < 4) pages.add(pg);
        }
    }

    private static final class Hit {
        final ArrayList<String> paths = new ArrayList<>(), plain = new ArrayList<>();
        String top;
    }

    private static final class Node {
        final Object o;
        final Node parent;
        final String edge;
        final int depth;

        Node(Object o, Node parent, String edge, int depth) {
            this.o = o;
            this.parent = parent;
            this.edge = edge;
            this.depth = depth;
        }
    }

    private static boolean isTarget(Object o) {
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character
                || o instanceof View || o instanceof Activity || o instanceof Class || o instanceof Enum || o.getClass().isArray()) return false;
        return isApp(o.getClass().getName());
    }

    private static void addTarget(ArrayList<String> labels, ArrayList<WeakReference<Object>> refs, String lb, Object o) {
        if (o != null && isTarget(o)) {
            labels.add(lb);
            refs.add(new WeakReference<Object>(o));
        }
    }

    /** 方法追踪命中时调：前几次命中、之后每 300 次，把 this 和前 3 个参数里的业务对象交给后台线程反查持有者。 */
    static void noteCall(final String tkey, Object self, Object[] args, RecConfig c) {
        try {
            if (c == null || !c.annotOwners) return;
            Integer prev = CALLS.get(tkey);
            int n = prev == null ? 1 : prev + 1;
            CALLS.put(tkey, n);
            if (n > c.annotPerKey && n % 300 != 0) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastScan < 120) return;
            final ArrayList<String> labels = new ArrayList<>();
            final ArrayList<WeakReference<Object>> refs = new ArrayList<>();
            addTarget(labels, refs, "this", self);
            if (args != null) for (int i = 0; i < args.length && i < 3; i++) addTarget(labels, refs, "arg" + i, args[i]);
            if (refs.isEmpty()) return;
            lastScan = now;
            final int budget = c.annotBudgetMs;
            EX.execute(new Runnable() {
                @Override
                public void run() {
                    scan(tkey, labels, refs, budget);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static void scan(String tkey, ArrayList<String> labels, ArrayList<WeakReference<Object>> refs, int budgetMs) {
        boolean[] busy = Recorder.busyFlag();
        boolean was = busy[0];
        busy[0] = true; // 反射读 LiveData 等可能又触发被追踪的方法，别递归记录
        try {
            IdentityHashMap<Object, String> targets = new IdentityHashMap<>();
            ArrayList<Object> objs = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++) {
                Object o = refs.get(i).get();
                if (o == null) continue;
                targets.put(o, labels.get(i));
                objs.add(o);
            }
            if (targets.isEmpty()) return;
            Walker w = new Walker(System.nanoTime() + budgetMs * 1000000L, targets, false);
            w.run();
            LinkedHashMap<String, String> lines = OWN.get(tkey);
            if (lines == null) {
                OWN.putIfAbsent(tkey, new LinkedHashMap<String, String>());
                lines = OWN.get(tkey);
            }
            String top = null;
            for (Object o : objs) {
                String lb = targets.get(o);
                String cls = nice(o.getClass().getName());
                Hit h = w.hits.get(o);
                if (h == null || h.paths.isEmpty()) {
                    addLine(lines, lb + "|" + cls + "|-", lb + " " + nodeText(o, true) + " ← （没在当前 Activity / Fragment / ViewModel / 视图树 " + MAX_DEPTH
                            + " 层内找到持有者；可能在静态单例或后台线程对象里" + (w.timedOut ? "；反查超时，没扫完" : "") + "）");
                    noteEntity(o, null);
                } else {
                    for (int i = 0; i < h.paths.size(); i++) {
                        addLine(lines, lb + "|" + cls + "|" + h.plain.get(i), lb + " " + nodeText(o, true) + " ← " + h.paths.get(i));
                    }
                    if (top == null) top = h.top;
                    noteEntity(o, h);
                }
            }
            if (top != null) TOP.put(tkey, top);
            Recorder.store.markDirty();
        } catch (Throwable ignored) {
        } finally {
            busy[0] = was;
        }
    }

    private static void addLine(LinkedHashMap<String, String> lines, String sig, String line) {
        synchronized (lines) {
            if (lines.size() < 10 && !lines.containsKey(sig)) lines.put(sig, line);
        }
    }

    private static void noteEntity(Object o, Hit h) {
        try {
            Class<?> k = o.getClass();
            Entity e = ENT.get(k.getName());
            if (e == null) {
                ENT.putIfAbsent(k.getName(), new Entity(k.getName(), roleOf(k)));
                e = ENT.get(k.getName());
            }
            if (h == null || h.plain.isEmpty()) e.note(o, null);
            else for (String p : h.plain) e.note(o, p);
        } catch (Throwable ignored) {
        }
    }

    /** 任务开始 / 结束 / 点标记时调：扫一遍当前页面里「容器 → 持有的数据实体」。 */
    static void sweep(String reason) {
        try {
            final RecConfig c = Recorder.cfg;
            if (c == null || !c.enabled || !c.annotSweep) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastSweep < 500) return;
            lastSweep = now;
            final int budget = Math.max(c.annotBudgetMs * 2, 60);
            EX.execute(new Runnable() {
                @Override
                public void run() {
                    boolean[] busy = Recorder.busyFlag();
                    boolean was = busy[0];
                    busy[0] = true;
                    try {
                        new Walker(System.nanoTime() + budget * 1000000L, new IdentityHashMap<Object, String>(), true).run();
                        Recorder.store.markDirty();
                    } catch (Throwable ignored) {
                    } finally {
                        busy[0] = was;
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ---- 节点显示

    private static String viewId(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID || id <= 0) return "";
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isViewHolderClass(Class<?> k) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("androidx.recyclerview.widget.RecyclerView$ViewHolder")) return true;
        }
        return false;
    }

    static String nodeText(Object o, boolean ids) {
        Class<?> k = o.getClass();
        StringBuilder sb = new StringBuilder(nice(k.getName()));
        if (o instanceof View) {
            String id = viewId((View) o);
            if (!id.isEmpty()) sb.append('#').append(id);
        }
        if (ids) sb.append('@').append(Integer.toHexString(System.identityHashCode(o)));
        String r = roleOf(k);
        if (!r.equals("普通类") && !r.equals("View") && !r.equals("?")) sb.append('⟨').append(r).append('⟩');
        if (r.equals("ViewHolder")) {
            Object iv = fieldAny(k, o, "itemView");
            if (iv instanceof View) {
                String id = viewId((View) iv);
                sb.append("{itemView=").append(nice(iv.getClass().getName())).append(id.isEmpty() ? "" : "#" + id).append('}');
            }
        }
        return sb.toString();
    }

    private static String render(Node nd, String lastEdge, Object target, boolean ids) {
        ArrayList<Node> chain = new ArrayList<>();
        for (Node n = nd; n != null; n = n.parent) chain.add(0, n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            Node n = chain.get(i);
            if (i > 0) sb.append(" ─").append(n.edge).append("→ ");
            sb.append(nodeText(n.o, ids));
        }
        sb.append(" ─").append(lastEdge).append("→ ").append(nodeText(target, ids));
        return sb.toString();
    }

    /** 路径里离目标最近的容器（ViewHolder / Fragment / ViewModel / Activity / Adapter）；没有就取直接持有者。 */
    private static String topOf(Node nd) {
        for (Node n = nd; n != null; n = n.parent) {
            String r = roleOf(n.o.getClass());
            if (isContainerRole(r)) return nice(n.o.getClass().getName()) + "⟨" + r + "⟩";
        }
        return nodeText(nd.o, false);
    }

    // ---- 遍历

    private static final class Walker {
        final long deadline;
        final IdentityHashMap<Object, String> targets;
        final boolean sweep;
        final IdentityHashMap<Object, Hit> hits = new IdentityHashMap<>();
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        final ArrayList<Node> queue = new ArrayList<>();
        int nodes;
        boolean timedOut;

        Walker(long deadline, IdentityHashMap<Object, String> targets, boolean sweep) {
            this.deadline = deadline;
            this.targets = targets;
            this.sweep = sweep;
        }

        void run() {
            seed();
            for (int qi = 0; qi < queue.size(); qi++) {
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    break;
                }
                Node nd = queue.get(qi);
                if (nd.depth == 0 && targets.containsKey(nd.o)) rootHit(nd);
                if (nd.depth >= MAX_DEPTH) continue;
                expand(nd);
            }
        }

        void addRoot(Object o) {
            if (o == null || nodes >= MAX_NODES) return;
            if (seen.put(o, Boolean.TRUE) == null) {
                queue.add(new Node(o, null, "", 0));
                nodes++;
            }
        }

        void seed() {
            Activity a = Recorder.topActivity();
            if (a == null) return;
            addRoot(a);
            for (Object f : fragmentsOf(a)) addRoot(f);
            try {
                View decor = a.getWindow().getDecorView();
                ArrayList<View> vs = new ArrayList<>();
                vs.add(decor);
                for (int i = 0; i < vs.size() && vs.size() < 600; i++) {
                    View v = vs.get(i);
                    if (v instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) v;
                        int cc = g.getChildCount();
                        for (int k = 0; k < cc && vs.size() < 600; k++) {
                            View ch = g.getChildAt(k);
                            if (ch != null) vs.add(ch);
                        }
                    }
                }
                for (View v : vs) addRoot(v);
            } catch (Throwable ignored) {
            }
        }

        void rootHit(Node nd) {
            Hit h = hits.get(nd.o);
            if (h == null) {
                h = new Hit();
                hits.put(nd.o, h);
            }
            String s = nodeText(nd.o, true) + "（就是当前页面的根：Activity / Fragment / 视图）";
            String p = nodeText(nd.o, false) + "（就是当前页面的根）";
            if (h.plain.contains(p)) return;
            h.paths.add(s);
            h.plain.add(p);
            if (h.top == null) h.top = nodeText(nd.o, false);
        }

        void expand(Node nd) {
            Object o = nd.o;
            ArrayList<Object[]> out = new ArrayList<>();
            try {
                edgesOf(o, out);
            } catch (Throwable ignored) {
            }
            boolean host = sweep && isHost(o);
            for (Object[] e : out) {
                Object v = e[1];
                if (v == null) continue;
                String lb = (String) e[0];
                if (!targets.isEmpty() && targets.containsKey(v)) record(nd, lb, v);
                if (host && isEntityObj(v)) noteEntityAt(nd, lb, v);
                if (nodes < MAX_NODES && seen.put(v, Boolean.TRUE) == null) {
                    queue.add(new Node(v, nd, lb, nd.depth + 1));
                    nodes++;
                }
            }
        }

        void record(Node nd, String lb, Object target) {
            Hit h = hits.get(target);
            if (h == null) {
                h = new Hit();
                hits.put(target, h);
            }
            if (h.paths.size() >= 3) return;
            String plain = render(nd, lb, target, false);
            if (h.plain.contains(plain)) return;
            h.paths.add(render(nd, lb, target, true));
            h.plain.add(plain);
            if (h.top == null) h.top = topOf(nd);
        }

        boolean isHost(Object o) {
            String r = roleOf(o.getClass());
            if (isContainerRole(r)) return true;
            return o instanceof View && isApp(o.getClass().getName());
        }

        boolean isEntityObj(Object v) {
            if (v instanceof View || !isTarget(v)) return false;
            return roleOf(v.getClass()).equals("数据实体");
        }

        void noteEntityAt(Node nd, String lb, Object v) {
            try {
                Class<?> k = v.getClass();
                Entity e = ENT.get(k.getName());
                if (e == null) {
                    ENT.putIfAbsent(k.getName(), new Entity(k.getName(), roleOf(k)));
                    e = ENT.get(k.getName());
                }
                e.note(v, nodeText(nd.o, false) + " " + lb);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- 取一个对象往外指的所有引用

    private static List<Object> fragmentsOf(Activity a) {
        ArrayList<Object> out = new ArrayList<>();
        try {
            Method gm = findMethod(a.getClass(), "getSupportFragmentManager");
            if (gm != null) collectFragments(gm.invoke(a), out, 0);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void collectFragments(Object fm, ArrayList<Object> out, int depth) {
        if (fm == null || depth > 3 || out.size() > 60) return;
        try {
            Method gf = findMethod(fm.getClass(), "getFragments");
            if (gf == null) return;
            Object l = gf.invoke(fm);
            if (!(l instanceof List)) return;
            for (Object f : (List<?>) l) {
                if (f == null) continue;
                boolean dup = false;
                for (Object x : out) if (x == f) dup = true;
                if (dup) continue;
                out.add(f);
                Method gc = findMethod(f.getClass(), "getChildFragmentManager");
                if (gc != null) collectFragments(gc.invoke(f), out, depth + 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void edgesOf(Object o, ArrayList<Object[]> out) {
        Class<?> k = o.getClass();
        if (o instanceof View) viewEdges((View) o, out);
        String r = roleOf(k);
        if (r.equals("Activity") || r.equals("Fragment")) viewModelEdges(o, out);
        if (r.equals("ViewHolder")) {
            Object iv = fieldAny(k, o, "itemView");
            if (iv != null) out.add(new Object[]{".itemView", iv});
        }
        if (isApp(k.getName())) {
            for (Field f : fieldsOf(k)) {
                if (out.size() > 160) break;
                try {
                    Object v = f.get(o);
                    if (v != null) addValue(out, "." + f.getName(), v, 0);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void viewEdges(View v, ArrayList<Object[]> out) {
        try {
            Object tag = v.getTag();
            if (tag != null) addValue(out, ".tag", tag, 1);
        } catch (Throwable ignored) {
        }
        Object kt = fieldAny(View.class, v, "mKeyedTags");
        if (kt != null) addValue(out, ".keyedTag", kt, 1);
        try {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                Object vh = fieldAny(lp.getClass(), lp, "mViewHolder");
                if (vh != null) out.add(new Object[]{".holder", vh});
            }
        } catch (Throwable ignored) {
        }
        Object li = fieldAny(View.class, v, "mListenerInfo");
        if (li != null) {
            try {
                for (Field f : li.getClass().getDeclaredFields()) {
                    String n = f.getName();
                    if (!n.startsWith("mOn") || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object lv = f.get(li);
                    if (lv != null && isApp(lv.getClass().getName())) out.add(new Object[]{"." + n.substring(1), lv});
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            for (Class<?> c = v.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) {
                    Method ga = findMethod(c, "getAdapter");
                    if (ga != null) {
                        Object ad = ga.invoke(v);
                        if (ad != null) out.add(new Object[]{".adapter", ad});
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void viewModelEdges(Object o, ArrayList<Object[]> out) {
        try {
            Method gv = findMethod(o.getClass(), "getViewModelStore");
            if (gv == null) return;
            Object store = gv.invoke(o);
            if (store == null) return;
            for (Class<?> c = store.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object mv = f.get(store);
                    if (!(mv instanceof Map)) continue;
                    int i = 0;
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) mv).entrySet()) {
                        if (i++ >= 12) break;
                        if (e.getValue() != null) out.add(new Object[]{".viewModel[" + clip(String.valueOf(e.getKey()), 28) + "]", e.getValue()});
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLiveData(Class<?> k) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("androidx.lifecycle.") && c.getName().contains("LiveData")) return true;
        }
        return false;
    }

    /** 把一个字段值展开成若干条边：集合 / Map / 数组 / SparseArray 只看前 8 个；引用包装、LiveData、Lazy 取里面的值；业务对象和 View 直接成边。 */
    private static void addValue(ArrayList<Object[]> out, String label, Object v, int depth) {
        if (v == null || depth > 2 || out.size() > 160) return;
        if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Class) return;
        try {
            if (v instanceof View) {
                out.add(new Object[]{label, v});
                return;
            }
            if (v instanceof Map) {
                int i = 0;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (i++ >= 8) break;
                    addValue(out, label + "{" + clip(String.valueOf(e.getKey()), 16) + "}", e.getValue(), depth + 1);
                }
                return;
            }
            if (v instanceof Collection) {
                int i = 0;
                for (Object x : (Collection<?>) v) {
                    if (i >= 8) break;
                    addValue(out, label + "[" + i + "]", x, depth + 1);
                    i++;
                }
                return;
            }
            if (v instanceof Object[]) {
                Object[] a = (Object[]) v;
                for (int i = 0; i < a.length && i < 8; i++) addValue(out, label + "[" + i + "]", a[i], depth + 1);
                return;
            }
            if (v instanceof SparseArray) {
                SparseArray<?> sa = (SparseArray<?>) v;
                for (int i = 0; i < sa.size() && i < 8; i++) addValue(out, label + "[" + sa.keyAt(i) + "]", sa.valueAt(i), depth + 1);
                return;
            }
            if (v instanceof Reference) {
                addValue(out, label + ".get()", ((Reference<?>) v).get(), depth + 1);
                return;
            }
            if (v instanceof AtomicReference) {
                addValue(out, label + ".get()", ((AtomicReference<?>) v).get(), depth + 1);
                return;
            }
            Class<?> k = v.getClass();
            String vn = k.getName();
            if (isLiveData(k)) {
                Method gv = findMethod(k, "getValue");
                if (gv != null) addValue(out, label + "(LiveData).value", gv.invoke(v), depth + 1);
                return;
            }
            if (vn.startsWith("kotlin.jvm.internal.Ref$")) {
                addValue(out, label + ".element", fieldAny(k, v, "element"), depth + 1);
                return;
            }
            if (vn.startsWith("kotlin.") && vn.contains("Lazy")) {
                Method gv = findMethod(k, "getValue");
                if (gv != null) addValue(out, label + "(Lazy).value", gv.invoke(v), depth + 1);
                return;
            }
            if (isApp(vn)) out.add(new Object[]{label, v});
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 导出

    /** 写进一条记录的 JSON：方法追踪的钩点评级、类角色、对象归属。 */
    static void putRecord(JSONObject j, Rec r) {
        try {
            if (!Rec.TRACE.equals(r.kind)) return;
            String cn = r.viewClass, mn = r.viewId;
            String tk = cn + "#" + mn;
            j.put("role", roleOfName(cn));
            String[] g = grade(cn, mn);
            j.put("grade", g[0]);
            j.put("gradeText", g[1]);
            LinkedHashMap<String, String> lines = OWN.get(tk);
            if (lines != null) {
                JSONArray ja = new JSONArray();
                synchronized (lines) {
                    for (String s : lines.values()) ja.put(s);
                }
                if (ja.length() > 0) j.put("owners", ja);
            }
            String top = TOP.get(tk);
            if (top != null) j.put("ownerTop", top);
        } catch (Throwable ignored) {
        }
    }

    /** 整份快照里的「数据实体 → 容器」总表（每个进程一份）。 */
    static JSONArray toJson() {
        JSONArray arr = new JSONArray();
        try {
            ArrayList<Entity> es = new ArrayList<>(ENT.values());
            java.util.Collections.sort(es, new java.util.Comparator<Entity>() {
                @Override
                public int compare(Entity a, Entity b) {
                    int c = Integer.compare(b.paths.size(), a.paths.size());
                    return c != 0 ? c : Integer.compare(b.ids.size(), a.ids.size());
                }
            });
            for (Entity e : es) {
                if (arr.length() >= 40) break;
                JSONObject o = new JSONObject();
                synchronized (e) {
                    o.put("cls", e.cls);
                    o.put("role", e.role);
                    o.put("objs", e.ids.size());
                    o.put("objsMore", e.idOver);
                    o.put("paths", new JSONArray(new ArrayList<>(e.paths)));
                    o.put("pages", new JSONArray(new ArrayList<>(e.pages)));
                }
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }
}
