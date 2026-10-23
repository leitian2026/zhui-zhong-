package com.textrec.lsp;

import android.app.Activity;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 数据树（1.31.0，目标进程里用，只读）：方法追踪命中时，把 this / 每个参数 / 返回值沿字段展开成一棵树，
 * 树上每个节点有一个固定写法的「路径」（如 arg0.mAweme.statistics.diggCount），整个流程围绕这个路径：
 * <ul>
 *   <li>记录每个节点在每个任务里出现过哪些值 → 导出里能看到「这个字段在任务 1 是 12、任务 2 是 13」，找出区分操作的状态字段；</li>
 *   <li>观察点：对任意路径（手写 watch=类#方法:路径，或自动挑选值变过的叶子）在每次被追踪方法进入 / 返回时重新读一遍，
 *       值变了就记下是哪个方法「执行期间」变的（进入前后值不同 = 这个方法或它调用的东西写的），或「两次调用之间」变的；</li>
 *   <li>路径写法固定（见 {@link #eval}），导出时可以直接翻成 XposedHelpers 取值代码。</li>
 * </ul>
 * 路径语法：根 this / argN / ret；.字段名；[下标]；{键}；(LiveData) 取 LiveData 当前值；(ref) 取 Reference / AtomicReference 里的值；(lazy) 取 Lazy 的值。
 * 全部带数量上限，在抓栈之前的热路径上只做计数判断，真正展开发生在每个方法前几次命中和之后每隔 treeEvery 次。
 */
final class DataTree {
    private DataTree() {}

    private static final int MAX_STR = 60, MAX_ELEMS = 6, MAX_VALS = 4, MAX_PATHS = 600, MAX_TREES = 24;

    /** 一个路径上的取值历史。 */
    static final class Hist {
        String kind, type, last;
        int changes, n, depth;
        final LinkedHashMap<Integer, LinkedHashSet<String>> bySeg = new LinkedHashMap<>();
        boolean watched;
    }

    static final class TreeRec {
        final String tkey;
        final LinkedHashMap<String, Hist> paths = new LinkedHashMap<>();
        int hits, captures, lastSeg = -1, lastPass = -1;
        long lastInput;
        boolean truncated;
        final WeakReference<?>[] roots = new WeakReference<?>[10]; // 0 = this, 1..8 = arg0..arg7, 9 = ret

        TreeRec(String tkey) {
            this.tkey = tkey;
        }
    }

    static final class WEvent {
        long t;
        int seg;
        String kind, old, neu, by, stack;
    }

    static final class Watch {
        final String tkey, path, root;
        final boolean auto;
        String last = null;
        final ArrayList<WEvent> events = new ArrayList<>();
        int dropped;

        Watch(String tkey, String path, boolean auto) {
            this.tkey = tkey;
            this.path = path;
            this.auto = auto;
            int d = path.indexOf('.');
            int b = path.indexOf('[');
            int cut = path.length();
            if (d >= 0) cut = Math.min(cut, d);
            if (b >= 0) cut = Math.min(cut, b);
            this.root = path.substring(0, cut);
        }
    }

    private static final ConcurrentHashMap<String, TreeRec> TREES = new ConcurrentHashMap<>();
    private static final ArrayList<Watch> WATCHES = new ArrayList<>();
    private static volatile Watch[] watchArr = new Watch[0];
    /** 有观察点时为 true：Recorder 的前后钩子据此决定要不要读观察点。 */
    static volatile boolean watching;
    private static final ThreadLocal<ArrayDeque<String[]>> BEFORE = new ThreadLocal<ArrayDeque<String[]>>() {
        @Override
        protected ArrayDeque<String[]> initialValue() {
            return new ArrayDeque<>();
        }
    };
    private static volatile long lastCap;
    private static volatile int capsThisSec, capSec;
    private static volatile String watchCfg = "";
    private static int autoCount;

    // ------------------------------------------------------------ 配置

    /** 读配置时调：解析 watch= 里手写的观察点（每行 类#方法:路径）。自动挑选的观察点不受影响。 */
    static void configure(RecConfig c) {
        try {
            String w = c.watch == null ? "" : c.watch;
            if (w.equals(watchCfg)) return;
            watchCfg = w;
            synchronized (WATCHES) {
                ArrayList<Watch> keep = new ArrayList<>();
                for (Watch x : WATCHES) if (x.auto) keep.add(x);
                WATCHES.clear();
                WATCHES.addAll(keep);
                for (String line : w.split("[\\r\\n]+")) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("//")) continue;
                    int i = t.indexOf(':');
                    if (i <= 0) continue;
                    String tk = t.substring(0, i).trim(), p = t.substring(i + 1).trim();
                    if (tk.indexOf('#') < 0 || p.isEmpty()) continue;
                    WATCHES.add(new Watch(tk, p, false));
                }
                publish();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void publish() {
        watchArr = WATCHES.toArray(new Watch[0]);
        watching = watchArr.length > 0;
    }

    // ------------------------------------------------------------ 展开

    private static final class Ctx {
        final LinkedHashMap<String, String[]> out = new LinkedHashMap<>(); // path → {kind, type, value}
        final IdentityHashMap<Object, String> seen = new IdentityHashMap<>();
        final int maxDepth, maxNodes;
        boolean truncated;

        Ctx(int maxDepth, int maxNodes) {
            this.maxDepth = maxDepth;
            this.maxNodes = maxNodes;
        }

        boolean full() {
            return out.size() >= maxNodes;
        }
    }

    private static String clip(String s, int n) {
        String t = s.replace("\r", "\\r").replace("\n", "\\n");
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    private static String tname(Object o) {
        return o == null ? "null" : Annot.nice(o.getClass().getName());
    }

    private static void put(Ctx cx, String path, String kind, String type, String val) {
        if (cx.full()) {
            cx.truncated = true;
            return;
        }
        cx.out.put(path, new String[]{kind, type, val});
    }

    private static boolean leafLike(Object v) {
        return v == null || v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof CharSequence
                || v instanceof Enum || v instanceof Class;
    }

    private static void leaf(Ctx cx, String path, Object v) {
        if (v == null) put(cx, path, "null", "", "null");
        else if (v instanceof Boolean) put(cx, path, "bool", "boolean", String.valueOf(v));
        else if (v instanceof Number) put(cx, path, "num", v.getClass().getSimpleName(), String.valueOf(v));
        else if (v instanceof Character) put(cx, path, "char", "char", "'" + v + "'");
        else if (v instanceof CharSequence) put(cx, path, "str", "String", "\"" + clip(v.toString(), MAX_STR) + "\"");
        else if (v instanceof Enum) put(cx, path, "enum", tname(v), ((Enum<?>) v).name());
        else put(cx, path, "class", "Class", ((Class<?>) v).getName());
    }

    /** 展开一个值：叶子直接记；容器记大小再展开前几个；业务对象记类名 + 对象 id 再展开字段。 */
    private static void walk(Object v, String path, int depth, Ctx cx) {
        if (cx.full()) {
            cx.truncated = true;
            return;
        }
        if (leafLike(v)) {
            leaf(cx, path, v);
            return;
        }
        try {
            if (v instanceof View) {
                View vw = (View) v;
                String id = "";
                try {
                    if (vw.getId() > 0) id = "#" + vw.getResources().getResourceEntryName(vw.getId());
                } catch (Throwable ignored) {
                }
                put(cx, path, "view", tname(v), tname(v) + id);
                return;
            }
            if (v instanceof Activity) {
                put(cx, path, "obj", tname(v), tname(v) + "（Activity）");
                return;
            }
            String prior = cx.seen.get(v);
            if (prior != null) {
                put(cx, path, "ref", tname(v), "↩ " + prior);
                return;
            }
            if (v instanceof Map) {
                cx.seen.put(v, path);
                Map<?, ?> m = (Map<?, ?>) v;
                put(cx, path, "map", tname(v), "size=" + m.size());
                if (depth >= cx.maxDepth) return;
                int i = 0;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (i++ >= MAX_ELEMS) break;
                    walk(e.getValue(), path + "{" + clip(String.valueOf(e.getKey()), 16) + "}", depth + 1, cx);
                }
                return;
            }
            if (v instanceof Collection) {
                cx.seen.put(v, path);
                Collection<?> col = (Collection<?>) v;
                put(cx, path, "list", tname(v), "size=" + col.size());
                if (depth >= cx.maxDepth) return;
                int i = 0;
                for (Object e : col) {
                    if (i >= MAX_ELEMS) break;
                    walk(e, path + "[" + i + "]", depth + 1, cx);
                    i++;
                }
                return;
            }
            if (v.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(v);
                put(cx, path, "array", tname(v), "length=" + len);
                if (depth >= cx.maxDepth) return;
                for (int i = 0; i < len && i < MAX_ELEMS; i++) walk(java.lang.reflect.Array.get(v, i), path + "[" + i + "]", depth + 1, cx);
                return;
            }
            if (v instanceof SparseArray) {
                SparseArray<?> sa = (SparseArray<?>) v;
                put(cx, path, "map", "SparseArray", "size=" + sa.size());
                if (depth >= cx.maxDepth) return;
                for (int i = 0; i < sa.size() && i < MAX_ELEMS; i++) walk(sa.valueAt(i), path + "{" + sa.keyAt(i) + "}", depth + 1, cx);
                return;
            }
            if (v instanceof Reference) {
                put(cx, path, "obj", tname(v), "引用包装");
                walk(((Reference<?>) v).get(), path + "(ref)", depth, cx);
                return;
            }
            if (v instanceof AtomicReference) {
                put(cx, path, "obj", tname(v), "引用包装");
                walk(((AtomicReference<?>) v).get(), path + "(ref)", depth, cx);
                return;
            }
            Class<?> k = v.getClass();
            String cn = k.getName();
            if (isLiveData(k)) {
                Object lv = invoke(v, "getValue");
                put(cx, path, "obj", tname(v), "LiveData");
                walk(lv, path + "(LiveData)", depth, cx);
                return;
            }
            if (cn.startsWith("kotlin.") && cn.contains("Lazy")) {
                put(cx, path, "obj", tname(v), "Lazy");
                walk(invoke(v, "getValue"), path + "(lazy)", depth, cx);
                return;
            }
            if (cn.startsWith("kotlin.jvm.internal.Ref$")) {
                put(cx, path, "obj", tname(v), "引用包装");
                walk(fieldValue(k, v, "element"), path + ".element", depth, cx);
                return;
            }
            if (!Annot.isApp(cn)) { // 系统 / androidx 对象：不展开，只有几种常见的值类型写 toString
                if (cn.equals("android.net.Uri") || cn.startsWith("java.util.Date") || cn.equals("java.net.URL") || cn.equals("java.net.URI")
                        || cn.startsWith("java.math.Big") || cn.startsWith("java.util.concurrent.atomic.Atomic")) {
                    put(cx, path, "str", tname(v), clip(String.valueOf(v), MAX_STR));
                } else {
                    put(cx, path, "obj", tname(v), tname(v));
                }
                return;
            }
            cx.seen.put(v, path);
            put(cx, path, "obj", tname(v), tname(v) + "@" + Integer.toHexString(System.identityHashCode(v)));
            if (depth >= cx.maxDepth) return;
            for (Class<?> c = k; c != null && c != Object.class && Annot.isApp(c.getName()); c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (cx.full()) {
                        cx.truncated = true;
                        return;
                    }
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        walk(f.get(v), path + "." + f.getName(), depth + 1, cx);
                    } catch (Throwable ignored) {
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

    private static Object invoke(Object o, String name) {
        try {
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        return m.invoke(o);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object fieldValue(Class<?> k, Object o, String name) {
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

    // ------------------------------------------------------------ 按路径取值

    /**
     * 从根对象出发按路径取值；路径写法见类说明。读不到（字段不存在 / 中间是 null / 越界）返回 null 并把原因写进 why[0]。
     * 返回的是对象本身，不是字符串；{@link #show} 把它变成和树里一致的文字。
     */
    static Object eval(Object root, String tail, String[] why) {
        Object cur = root;
        int i = 0, n = tail.length();
        try {
            while (i < n) {
                char ch = tail.charAt(i);
                if (cur == null) {
                    why[0] = "路径中间遇到 null（在 " + tail.substring(0, i) + " 之后）";
                    return null;
                }
                if (ch == '.') {
                    int j = i + 1;
                    while (j < n && ".[{(".indexOf(tail.charAt(j)) < 0) j++;
                    String name = tail.substring(i + 1, j);
                    Object nv = fieldValue(cur.getClass(), cur, name);
                    if (nv == null && !hasField(cur.getClass(), name)) {
                        why[0] = "类 " + tname(cur) + " 里没有字段 " + name;
                        return null;
                    }
                    cur = nv;
                    i = j;
                } else if (ch == '[') {
                    int j = tail.indexOf(']', i);
                    if (j < 0) {
                        why[0] = "[ 没有闭合";
                        return null;
                    }
                    int idx = Integer.parseInt(tail.substring(i + 1, j));
                    if (cur instanceof List) {
                        List<?> l = (List<?>) cur;
                        if (idx >= l.size()) {
                            why[0] = "下标 " + idx + " 越界（size=" + l.size() + "）";
                            return null;
                        }
                        cur = l.get(idx);
                    } else if (cur instanceof Collection) {
                        int c = 0;
                        Object pick = null;
                        for (Object e : (Collection<?>) cur) {
                            if (c++ == idx) {
                                pick = e;
                                break;
                            }
                        }
                        cur = pick;
                    } else if (cur.getClass().isArray()) {
                        cur = java.lang.reflect.Array.get(cur, idx);
                    } else if (cur instanceof SparseArray) {
                        cur = ((SparseArray<?>) cur).valueAt(idx);
                    } else {
                        why[0] = tname(cur) + " 不是列表 / 数组";
                        return null;
                    }
                    i = j + 1;
                } else if (ch == '{') {
                    int j = tail.indexOf('}', i);
                    if (j < 0) {
                        why[0] = "{ 没有闭合";
                        return null;
                    }
                    String key = tail.substring(i + 1, j);
                    Object pick = null;
                    if (cur instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) cur).entrySet()) {
                            if (clip(String.valueOf(e.getKey()), 16).equals(key)) {
                                pick = e.getValue();
                                break;
                            }
                        }
                    } else if (cur instanceof SparseArray) {
                        pick = ((SparseArray<?>) cur).get(Integer.parseInt(key));
                    }
                    cur = pick;
                    i = j + 1;
                } else if (ch == '(') {
                    int j = tail.indexOf(')', i);
                    if (j < 0) {
                        why[0] = "( 没有闭合";
                        return null;
                    }
                    String tok = tail.substring(i + 1, j);
                    if (tok.equals("LiveData")) cur = invoke(cur, "getValue");
                    else if (tok.equals("lazy")) cur = invoke(cur, "getValue");
                    else if (tok.equals("ref")) cur = cur instanceof Reference ? ((Reference<?>) cur).get() : cur instanceof AtomicReference ? ((AtomicReference<?>) cur).get() : null;
                    else {
                        why[0] = "不认识的取值符号 (" + tok + ")";
                        return null;
                    }
                    i = j + 1;
                } else {
                    why[0] = "路径第 " + (i + 1) + " 个字符 " + ch + " 不合法";
                    return null;
                }
            }
        } catch (Throwable t) {
            why[0] = t.getClass().getSimpleName();
            return null;
        }
        return cur;
    }

    private static boolean hasField(Class<?> k, String name) {
        for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return false;
    }

    /** 把取到的值变成和树里一致的短文字（叶子写值，对象写类名，容器写大小）。 */
    static String show(Object v) {
        if (v == null) return "null";
        Ctx cx = new Ctx(0, 1);
        walk(v, "x", 0, cx);
        String[] r = cx.out.get("x");
        return r == null ? tname(v) : r[2];
    }

    // ------------------------------------------------------------ 抓取（每个被追踪方法命中时调）

    private static int rootSlot(String root) {
        if (root.equals("this")) return 0;
        if (root.equals("ret")) return 9;
        if (root.startsWith("arg")) {
            try {
                int i = Integer.parseInt(root.substring(3));
                if (i >= 0 && i < 8) return i + 1;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /**
     * 方法追踪命中（after 钩子里，已设 BUSY）：更新这个方法的根对象引用；前几次命中和之后每隔 treeEvery 次
     * （或进入了新的任务 / 新的一遍 / 有新的用户输入）展开 this、参数、返回值并更新每个路径的取值历史。
     */
    static void capture(String tkey, Object self, Object[] args, Object ret, boolean hasRet, RecConfig c) {
        try {
            if (c == null || !c.treeOn) return;
            TreeRec tr = TREES.get(tkey);
            if (tr == null) {
                if (TREES.size() >= MAX_TREES) return;
                TREES.putIfAbsent(tkey, new TreeRec(tkey));
                tr = TREES.get(tkey);
            }
            synchronized (tr) {
                tr.hits++;
                boolean rootsNeeded = false;
                for (Watch w : watchArr) if (w.tkey.equals(tkey)) rootsNeeded = true;
                if (rootsNeeded || tr.hits <= 3) {
                    tr.roots[0] = self == null ? null : new WeakReference<Object>(self);
                    for (int i = 0; i < 8; i++) tr.roots[i + 1] = args != null && i < args.length && args[i] != null ? new WeakReference<Object>(args[i]) : null;
                    tr.roots[9] = hasRet && ret != null ? new WeakReference<Object>(ret) : null;
                }
                int seg = Timeline.curTask, pass = Timeline.curPass;
                long li = Timeline.lastInputAt;
                boolean fresh = seg != tr.lastSeg || pass != tr.lastPass || li != tr.lastInput;
                if (!(tr.hits <= 3 || tr.hits % Math.max(1, c.treeEvery) == 0 || fresh)) return;
                long now = SystemClock.uptimeMillis();
                int sec = (int) (now / 1000);
                if (sec != capSec) {
                    capSec = sec;
                    capsThisSec = 0;
                }
                if (++capsThisSec > 30) return; // 全局限流：每秒最多展开 30 次
                tr.lastSeg = seg;
                tr.lastPass = pass;
                tr.lastInput = li;
                tr.captures++;
                Ctx cx = new Ctx(Math.max(1, c.treeDepth), Math.max(20, c.treeNodes));
                if (self != null && !(self instanceof View) && Annot.isApp(self.getClass().getName())) walk(self, "this", 0, cx);
                if (args != null) for (int i = 0; i < args.length && i < 8; i++) walk(args[i], "arg" + i, 0, cx);
                if (hasRet) walk(ret, "ret", 0, cx);
                if (cx.truncated) tr.truncated = true;
                applyHist(tr, tkey, cx, seg, c, true);
            }
            store();
        } catch (Throwable ignored) {
        }
    }

    /** 把一次展开的结果记进每个路径的取值历史（seg = 记在哪个任务名下）。 */
    private static void applyHist(TreeRec tr, String tkey, Ctx cx, int seg, RecConfig c, boolean auto) {
        {
            {
                for (Map.Entry<String, String[]> e : cx.out.entrySet()) {
                    String p = e.getKey();
                    Hist h = tr.paths.get(p);
                    if (h == null) {
                        if (tr.paths.size() >= MAX_PATHS) {
                            tr.truncated = true;
                            continue;
                        }
                        h = new Hist();
                        h.depth = depthOf(p);
                        tr.paths.put(p, h);
                    }
                    String[] r = e.getValue();
                    h.kind = r[0];
                    h.type = r[1];
                    String val = r[2];
                    if (h.last != null && !h.last.equals(val)) h.changes++;
                    h.last = val;
                    h.n++;
                    LinkedHashSet<String> set = h.bySeg.get(seg);
                    if (set == null) {
                        set = new LinkedHashSet<>();
                        h.bySeg.put(seg, set);
                    }
                    if (set.size() < MAX_VALS) set.add(clip(val, 40));
                    if (auto && c.watchAuto && h.changes > 0 && !h.watched && isLeafKind(h.kind) && h.depth <= 5) autoWatch(tkey, p, h);
                }
            }
        }
    }

    /**
     * 不经过方法追踪、直接展开一个对象（任务开始 / 结束时扫到的数据实体，文字 / 图标记录所在的 ViewHolder / 自定义控件）。
     * 同样记每个路径在每个任务里的值；根节点名由调用方给（entity / holder / view / tag）。这类树没有观察点（没有可反复读取的根）。
     */
    static void captureExtra(String tkey, String[] names, Object[] roots, RecConfig c, int seg) {
        try {
            if (c == null || !c.treeOn) return;
            TreeRec tr = TREES.get(tkey);
            if (tr == null) {
                if (TREES.size() >= MAX_TREES) return;
                TREES.putIfAbsent(tkey, new TreeRec(tkey));
                tr = TREES.get(tkey);
            }
            synchronized (tr) {
                tr.hits++;
                tr.captures++;
                Ctx cx = new Ctx(Math.max(1, c.treeDepth), Math.max(20, c.treeNodes));
                for (int i = 0; i < names.length && i < roots.length; i++) if (roots[i] != null) walk(roots[i], names[i], 0, cx);
                if (cx.truncated) tr.truncated = true;
                applyHist(tr, tkey, cx, seg, c, false);
            }
            store();
        } catch (Throwable ignored) {
        }
    }

    private static void store() {
        try {
            Recorder.store.markDirty();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLeafKind(String k) {
        return k.equals("bool") || k.equals("num") || k.equals("str") || k.equals("enum") || k.equals("null") || k.equals("char");
    }

    /** 路径的层数（根算 0）：数 . [ { ( 这几种连接符的个数。 */
    static int depthOf(String p) {
        int d = 0;
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (ch == '.' || ch == '[' || ch == '{' || ch == '(') d++;
        }
        return d;
    }

    /** 值变过的叶子自动加成观察点（最多 24 个），之后每次被追踪方法进入 / 返回都会读一遍，找出是谁改的。 */
    private static void autoWatch(String tkey, String path, Hist h) {
        synchronized (WATCHES) {
            if (autoCount >= 24) return;
            if (rootSlot(new Watch(tkey, path, true).root) < 0) return;
            for (Watch w : WATCHES) if (w.tkey.equals(tkey) && w.path.equals(path)) {
                h.watched = true;
                return;
            }
            WATCHES.add(new Watch(tkey, path, true));
            autoCount++;
            h.watched = true;
            publish();
        }
    }

    // ------------------------------------------------------------ 观察点：每次被追踪方法进入 / 返回时读一遍

    private static String read(Watch w) {
        TreeRec tr = TREES.get(w.tkey);
        if (tr == null) return null;
        int slot = rootSlot(w.root);
        if (slot < 0) return null;
        WeakReference<?> ref;
        synchronized (tr) {
            ref = tr.roots[slot];
        }
        Object root = ref == null ? null : ref.get();
        if (root == null) return null;
        String tail = w.path.substring(w.root.length());
        String[] why = new String[1];
        Object v = eval(root, tail, why);
        if (v == null && why[0] != null) return null;
        return show(v);
    }

    private static void note(Watch w, String kind, String old, String neu, String by, boolean withStack) {
        WEvent e = new WEvent();
        e.t = SystemClock.uptimeMillis() - Timeline.START;
        e.seg = Timeline.curTask;
        e.kind = kind;
        e.old = old;
        e.neu = neu;
        e.by = by;
        if (withStack) {
            try {
                Stacks.Stk d = Stacks.build(Stacks.capture(true), 14, false);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d.lines.length && i < 8; i++) sb.append(i > 0 ? " ← " : "").append(d.lines[i]);
                e.stack = sb.toString();
            } catch (Throwable ignored) {
            }
        }
        synchronized (w.events) {
            if (w.events.size() < 40) w.events.add(e);
            else w.dropped++;
        }
        store();
    }

    /** 被追踪方法进入前（before 钩子，已设 BUSY）：读所有观察点；和上次读到的不同 = 两次调用之间变的；并把读数留给返回时对比。 */
    static void before(String tkey, boolean active) {
        try {
            Watch[] ws = watchArr;
            if (ws.length == 0) return;
            String[] vals = new String[ws.length];
            if (!active) { // 任务外 / 暂停 / 递归调用：不读，但照样入栈，保证和返回时的出栈一一对应
                pushBefore(vals);
                return;
            }
            for (int i = 0; i < ws.length; i++) {
                Watch w = ws[i];
                String v = read(w);
                vals[i] = v;
                if (v == null) continue;
                if (w.last != null && !w.last.equals(v)) note(w, "between", w.last, v, tkey + " 进入前", false);
                w.last = v;
            }
            pushBefore(vals);
        } catch (Throwable ignored) {
        }
    }

    private static void pushBefore(String[] vals) {
        ArrayDeque<String[]> st = BEFORE.get();
        if (st.size() > 64) st.clear(); // 钩子没成对触发时防止越积越多
        st.push(vals);
    }

    /** 被追踪方法返回后（after 钩子里先调，已设 BUSY）：和进入前的读数比，不同 = 这个方法执行期间（含它调用的）改的。 */
    static void after(String tkey) {
        try {
            Watch[] ws = watchArr;
            ArrayDeque<String[]> st = BEFORE.get();
            if (st.isEmpty()) return;
            String[] before = st.pop();
            for (int i = 0; i < ws.length && i < before.length; i++) {
                Watch w = ws[i];
                String v = read(w);
                if (v == null || before[i] == null) continue;
                if (!before[i].equals(v)) {
                    note(w, "inside", before[i], v, tkey + " 执行期间", true);
                }
                w.last = v;
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 导出

    static JSONArray treesJson() {
        JSONArray arr = new JSONArray();
        try {
            ArrayList<TreeRec> trs = new ArrayList<>(TREES.values());
            for (TreeRec tr : trs) {
                JSONObject o = new JSONObject();
                synchronized (tr) {
                    if (tr.paths.isEmpty()) continue;
                    o.put("tkey", tr.tkey);
                    o.put("hits", tr.hits);
                    o.put("caps", tr.captures);
                    o.put("trunc", tr.truncated);
                    JSONArray ns = new JSONArray();
                    for (Map.Entry<String, Hist> e : tr.paths.entrySet()) {
                        Hist h = e.getValue();
                        JSONObject n = new JSONObject();
                        n.put("p", e.getKey());
                        n.put("k", h.kind);
                        n.put("t", h.type);
                        n.put("v", h.last == null ? "" : clip(h.last, 80));
                        if (h.changes > 0) n.put("c", h.changes);
                        if (h.watched) n.put("w", true);
                        JSONObject sg = new JSONObject();
                        for (Map.Entry<Integer, LinkedHashSet<String>> s : h.bySeg.entrySet()) {
                            sg.put(String.valueOf(s.getKey()), new JSONArray(new ArrayList<>(s.getValue())));
                        }
                        n.put("s", sg);
                        ns.put(n);
                    }
                    o.put("nodes", ns);
                }
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }

    static JSONArray watchesJson() {
        JSONArray arr = new JSONArray();
        try {
            for (Watch w : watchArr) {
                JSONObject o = new JSONObject();
                o.put("tkey", w.tkey);
                o.put("path", w.path);
                o.put("auto", w.auto);
                o.put("last", w.last == null ? "" : w.last);
                o.put("dropped", w.dropped);
                JSONArray ev = new JSONArray();
                synchronized (w.events) {
                    for (WEvent e : w.events) {
                        JSONObject j = new JSONObject();
                        j.put("t", e.t);
                        j.put("seg", e.seg);
                        j.put("kind", e.kind);
                        j.put("old", clip(e.old == null ? "" : e.old, 60));
                        j.put("new", clip(e.neu == null ? "" : e.neu, 60));
                        j.put("by", e.by);
                        if (e.stack != null) j.put("stack", e.stack);
                        ev.put(j);
                    }
                }
                o.put("events", ev);
                arr.put(o);
            }
        } catch (Throwable ignored) {
        }
        return arr;
    }
}
