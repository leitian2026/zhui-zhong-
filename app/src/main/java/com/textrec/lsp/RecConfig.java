package com.textrec.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 配置：模块界面写出，目标 App 里的 Hook 读取。两边共用同一个类。
 * {@link #toText()} / {@link #fromText(String)} 是标准 Java Properties 格式（key=value，一行一个），
 * 字段名就是下面这些 public 字段的名字，完整列表和含义见仓库 README「采集配置」一节——
 * 首页顶栏「粘贴配置」接受的就是这段文本，直接 Properties.load() 解析，没写的字段用 new RecConfig() 的默认值。
 */
public final class RecConfig {
    public boolean enabled = true;
    /** false = 全量记录（默认）；true = 只记录命中关键字的文字 */
    public boolean keywordMode = false;
    public String keywords = "";
    public boolean regex = false;
    /** 已废弃：不再有任何作用（旧配置里的这个键会被忽略）。想只记方法追踪，把文字 / 图标 / 颜色 / 状态这几个记录开关都关掉即可。 */
    public boolean traceOnly = false;
    public boolean traceSet = true;
    public boolean traceDraw = true;
    /** 记录文字颜色：画笔颜色（所有文字）+ TextView 的 setTextColor 设置位置 */
    public boolean traceColor = true;
    /** 记录图标/按钮图片：ImageView 的 setImage* 与 View.setContentDescription */
    public boolean traceIcons = false;
    /** 自动观察 Compose 文字绘制（MultiParagraph.paint）的参数：颜色、描边/填充等 */
    public boolean autoParams = true;
    /** 记录“只被 setText、从未被绘制”的文字（控件 INVISIBLE/GONE/尺寸为 0/未挂窗口，如拖动才出现的时长） */
    public boolean traceSetOnly = false;
    /** 记录被跟踪 View 的 alpha / visibility / selected 变化（谁在改、改成什么） */
    public boolean traceState = false;
    /** 这类记录的上限（单独计，不占用 maxRecords 的大头） */
    public int maxSetOnly = 500;
    /** 方法追踪：每行 类#方法，记录每次调用的参数/返回值和调用栈（只读）。// 开头为注释 */
    public String traceMethods = "";
    // ---- 采集端过滤（默认全部不过滤；被挡掉的调用不抓栈、不建记录，只计数）----
    /** 触发窗口：最近一次触摸后多少毫秒内才记录；0 = 不限制。默认只管方法追踪。 */
    public int windowMs = 0;
    /** 触发窗口也用于文字 / 图标 / 颜色 / 状态 / 绘制记录 */
    public boolean windowAll = false;
    /** 排除名单（每行或逗号分隔）：类#方法、类#*（整个类）、#方法（任何类里的这个方法）、类名或包名前缀 */
    public String excludeList = "";
    /** 线程白名单：线程名包含任意一个词才记（如 main、LegoHandler）；空 = 不限 */
    public String threadFilter = "";
    /** 页面白名单：页面标签包含任意一个词才记（不区分大小写）；空 = 不限 */
    public String pageFilter = "";
    /** 控件白名单：View 类名或 id 包含任意一个词才记（文字 / 图标 / 状态 / 绘制）；空 = 不限 */
    public String viewFilter = "";
    /** 值匹配：方法追踪的参数或返回值里包含其中任意一个值才记；时长会自动展开成多种写法；空 = 不限 */
    public String valueFilter = "";
    /** 开关：排除通用基础设施（LiveData、Fragment 生命周期、字节的异步包装等，几乎所有功能都会经过的类） */
    public boolean excludeInfra = false;
    /** 线程：0 = 全部，1 = 只主线程，2 = 只后台线程 */
    public int threadMode = 0;
    /** 开关：方法追踪只记参数或返回值里带时间的调用（形如 00:00、1:37） */
    public boolean valueClock = false;
    /** 开关：方法追踪时，每隔 traceDumpEvery 次调用把 this 周围的字段值记一份（找进度 / 时长这类状态存在哪里），同时统计第一个数值参数的 last / min / max */
    public boolean traceDump = false;
    public int traceDumpEvery = 25;
    /** 链路追踪：以方法追踪的方法为锚点，串起上游（异步来源）和下游（被调用的方法、界面效果）。总开关 + 三个子开关 */
    /** 时间线：用户操作 / 页面 / 各位置的记录样本，按毫秒排成一条；标记和基线从记录页发来 */
    public boolean tlOn = true;
    public boolean tlOps = true;
    public boolean tlSamples = true;
    public boolean chainOn = false;
    public boolean chainAsync = true;
    public boolean chainEffects = true;
    public boolean chainDown = true;
    /** 前 5 次调用每次都建链，之后每隔 chainEvery 次建一次 */
    public int chainEvery = 20;
    /** 一次链里最多列多少个被调用的方法 */
    public int chainMaxDown = 120;
    /** 持续型自动降频：同一位置调用超过这个次数、且持续超过 quietSpanMs，之后只计数不抓栈；0 = 关 */
    public int quietCount = 0;
    public int quietSpanMs = 3000;

    /** 每条记录里保存的调用栈最大帧数 */
    public int stackDepth = 12;
    /** 同一个“位置”前几次要抓栈 */
    public int perKeyStacks = 3;
    /** 之后每隔多少次再抽样抓一次（发现同一控件的其他调用路径） */
    public int sampleEvery = 200;
    /** 全局每秒最多抓多少次栈（兜底） */
    public int stacksPerSec = 1000;
    public int maxRecords = 2000;
    /** 落盘节流（秒） */
    public int flushSec = 3;
    /** 变了就清空已有记录重新开始 */
    public long epoch = 0;

    /** keywords 的拆分：正则模式只按换行拆（正则里可能有逗号），普通模式按逗号/换行拆 */
    static List<String> split(String s, boolean regexMode) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(regexMode ? "[\\n\\r]+" : "[,\\n\\r]+")) {
            String t = regexMode ? p : p.trim();
            if (!t.trim().isEmpty()) out.add(t);
        }
        return out;
    }

    /** 方法追踪目标：只按换行拆，去掉参数括号和 // 注释。 */
    static List<String> splitTargets(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[\\n\\r]+")) {
            String t = p.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int at = optStart(t);
            if (at > 0) t = t.substring(0, at).trim(); // 去掉行尾的 @选项
            int i = t.indexOf('(');
            if (i > 0) t = t.substring(0, i).trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** 方法追踪每一行可以带的选项。 */
    static final class TraceOpt {
        /** 只计数：不记参数变体、不抓栈 */
        boolean countOnly;
        /** 只记参数 / 返回值变体：不抓栈 */
        boolean argsOnly;
        /** 这一行单独的栈深度，0 = 用全局的 */
        int depth;
        /** 这一行单独的抽样间隔，0 = 用全局的 */
        int every;
        /** @tag=N：第 N 个参数（从 0 数）当作“当前标识”（如视频 id）；@tag=ret 用返回值 */
        int tagArg = -2;
    }

    /** 行里第一个“空白 + @”的空白位置；没有返回 -1。 */
    private static int optStart(String t) {
        for (int i = 1; i < t.length(); i++) {
            if (t.charAt(i) == '@' && Character.isWhitespace(t.charAt(i - 1))) return i - 1;
        }
        return -1;
    }

    /**
     * 方法追踪行尾的选项：「类#方法 @count」「类#方法 @args」「类#方法 @stack=4 @every=10」，
     * 选项之间用空格、逗号或 @ 分隔。key 是去掉选项和参数括号后的「类#方法」。
     */
    static Map<String, TraceOpt> traceOpts(String s) {
        HashMap<String, TraceOpt> out = new HashMap<>();
        if (s == null) return out;
        for (String p : s.split("[\\n\\r]+")) {
            String t = p.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int at = optStart(t);
            if (at < 0) continue;
            String opts = t.substring(at).trim();
            String target = t.substring(0, at).trim();
            int i = target.indexOf('(');
            if (i > 0) target = target.substring(0, i).trim();
            if (target.isEmpty()) continue;
            TraceOpt o = new TraceOpt();
            for (String w : opts.split("[@,\\s]+")) {
                if (w.isEmpty()) continue;
                if (w.equals("count")) {
                    o.countOnly = true;
                } else if (w.equals("args")) {
                    o.argsOnly = true;
                } else if (w.startsWith("stack=") || w.startsWith("depth=")) {
                    int v = intOf(w.substring(w.indexOf('=') + 1));
                    if (v > 0) o.depth = clamp(v, 1, 60);
                } else if (w.startsWith("tag=")) {
                    String tv = w.substring(4);
                    o.tagArg = tv.equals("ret") ? -1 : Math.max(0, intOf(tv));
                } else if (w.startsWith("every=")) {
                    int v = intOf(w.substring(w.indexOf('=') + 1));
                    if (v > 0) o.every = clamp(v, 1, 100000);
                }
            }
            out.put(target, o);
        }
        return out;
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    static List<String> splitLines(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[,\\n\\r]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public String summary() {
        return "enabled=" + enabled + " mode=" + (keywordMode ? "keyword" : "all")
                + " regex=" + regex + " set=" + traceSet + " draw=" + traceDraw
                + " color=" + traceColor + " icons=" + traceIcons + " auto=" + autoParams
                + " setOnly=" + traceSetOnly + "/" + maxSetOnly + " state=" + traceState
                + " traces=" + splitTargets(traceMethods).size()
                + " window=" + windowMs + (windowAll ? "/all" : "") + " quiet=" + quietCount + "/" + quietSpanMs
                + " infra=" + excludeInfra + " thrMode=" + threadMode + " clock=" + valueClock
                + " thr=" + splitLines(threadFilter).size() + " page=" + splitLines(pageFilter).size()
                + " view=" + splitLines(viewFilter).size() + " excl=" + splitLines(excludeList).size()
                + " val=" + splitLines(valueFilter).size()
                + " depth=" + stackDepth + " perKey=" + perKeyStacks + " sample=" + sampleEvery
                + " perSec=" + stacksPerSec + " max=" + maxRecords + " flush=" + flushSec;
    }

    public String toText() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("keywordMode", String.valueOf(keywordMode));
            p.setProperty("keywords", keywords);
            p.setProperty("regex", String.valueOf(regex));
            p.setProperty("traceOnly", String.valueOf(traceOnly));
            p.setProperty("traceSet", String.valueOf(traceSet));
            p.setProperty("traceDraw", String.valueOf(traceDraw));
            p.setProperty("traceColor", String.valueOf(traceColor));
            p.setProperty("traceIcons", String.valueOf(traceIcons));
            p.setProperty("autoParams", String.valueOf(autoParams));
            p.setProperty("traceSetOnly", String.valueOf(traceSetOnly));
            p.setProperty("traceState", String.valueOf(traceState));
            p.setProperty("maxSetOnly", String.valueOf(maxSetOnly));
            p.setProperty("traceMethods", traceMethods);
            p.setProperty("windowMs", String.valueOf(windowMs));
            p.setProperty("windowAll", String.valueOf(windowAll));
            p.setProperty("excludeInfra", String.valueOf(excludeInfra));
            p.setProperty("threadMode", String.valueOf(threadMode));
            p.setProperty("valueClock", String.valueOf(valueClock));
            p.setProperty("traceDump", String.valueOf(traceDump));
            p.setProperty("traceDumpEvery", String.valueOf(traceDumpEvery));
            p.setProperty("tlOn", String.valueOf(tlOn));
            p.setProperty("tlOps", String.valueOf(tlOps));
            p.setProperty("tlSamples", String.valueOf(tlSamples));
            p.setProperty("chainOn", String.valueOf(chainOn));
            p.setProperty("chainAsync", String.valueOf(chainAsync));
            p.setProperty("chainEffects", String.valueOf(chainEffects));
            p.setProperty("chainDown", String.valueOf(chainDown));
            p.setProperty("chainEvery", String.valueOf(chainEvery));
            p.setProperty("chainMaxDown", String.valueOf(chainMaxDown));
            p.setProperty("excludeList", excludeList);
            p.setProperty("threadFilter", threadFilter);
            p.setProperty("pageFilter", pageFilter);
            p.setProperty("viewFilter", viewFilter);
            p.setProperty("valueFilter", valueFilter);
            p.setProperty("quietCount", String.valueOf(quietCount));
            p.setProperty("quietSpanMs", String.valueOf(quietSpanMs));
            p.setProperty("stackDepth", String.valueOf(stackDepth));
            p.setProperty("perKeyStacks", String.valueOf(perKeyStacks));
            p.setProperty("sampleEvery", String.valueOf(sampleEvery));
            p.setProperty("stacksPerSec", String.valueOf(stacksPerSec));
            p.setProperty("maxRecords", String.valueOf(maxRecords));
            p.setProperty("flushSec", String.valueOf(flushSec));
            p.setProperty("epoch", String.valueOf(epoch));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            // Properties.store 输出纯 ASCII（非 ASCII 转成 \\uXXXX），换行也被转义，可安全放进 shell heredoc
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    public static RecConfig fromText(String text) {
        RecConfig c = new RecConfig();
        if (text == null || text.isEmpty()) return c;
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static RecConfig read(File f) {
        RecConfig c = new RecConfig();
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static void fill(RecConfig c, Properties p) {
        c.enabled = bool(p, "enabled", c.enabled);
        c.keywordMode = bool(p, "keywordMode", c.keywordMode);
        c.keywords = p.getProperty("keywords", c.keywords);
        c.regex = bool(p, "regex", c.regex);
        c.traceSet = bool(p, "traceSet", c.traceSet);
        c.traceDraw = bool(p, "traceDraw", c.traceDraw);
        c.traceColor = bool(p, "traceColor", c.traceColor);
        c.traceIcons = bool(p, "traceIcons", c.traceIcons);
        c.autoParams = bool(p, "autoParams", c.autoParams);
        c.traceSetOnly = bool(p, "traceSetOnly", c.traceSetOnly);
        c.traceState = bool(p, "traceState", c.traceState);
        c.traceOnly = bool(p, "traceOnly", c.traceOnly);
        c.maxSetOnly = clamp(num(p, "maxSetOnly", c.maxSetOnly), 10, 5000);
        c.traceMethods = p.getProperty("traceMethods", c.traceMethods);
        c.windowMs = clamp(num(p, "windowMs", c.windowMs), 0, 600000);
        c.windowAll = bool(p, "windowAll", c.windowAll);
        c.excludeInfra = bool(p, "excludeInfra", c.excludeInfra);
        c.threadMode = clamp(num(p, "threadMode", c.threadMode), 0, 2);
        c.valueClock = bool(p, "valueClock", c.valueClock);
        c.traceDump = bool(p, "traceDump", c.traceDump);
        c.traceDumpEvery = clamp(num(p, "traceDumpEvery", c.traceDumpEvery), 1, 1000);
        c.tlOn = bool(p, "tlOn", c.tlOn);
        c.tlOps = bool(p, "tlOps", c.tlOps);
        c.tlSamples = bool(p, "tlSamples", c.tlSamples);
        c.chainOn = bool(p, "chainOn", c.chainOn);
        c.chainAsync = bool(p, "chainAsync", c.chainAsync);
        c.chainEffects = bool(p, "chainEffects", c.chainEffects);
        c.chainDown = bool(p, "chainDown", c.chainDown);
        c.chainEvery = clamp(num(p, "chainEvery", c.chainEvery), 1, 100000);
        c.chainMaxDown = clamp(num(p, "chainMaxDown", c.chainMaxDown), 10, 1000);
        c.excludeList = p.getProperty("excludeList", c.excludeList);
        c.threadFilter = p.getProperty("threadFilter", c.threadFilter);
        c.pageFilter = p.getProperty("pageFilter", c.pageFilter);
        c.viewFilter = p.getProperty("viewFilter", c.viewFilter);
        c.valueFilter = p.getProperty("valueFilter", c.valueFilter);
        c.quietCount = clamp(num(p, "quietCount", c.quietCount), 0, 10000000);
        c.quietSpanMs = clamp(num(p, "quietSpanMs", c.quietSpanMs), 100, 3600000);
        c.stackDepth = clamp(num(p, "stackDepth", c.stackDepth), 1, 60);
        c.perKeyStacks = clamp(num(p, "perKeyStacks", c.perKeyStacks), 1, 50);
        c.sampleEvery = clamp(num(p, "sampleEvery", c.sampleEvery), 10, 100000);
        c.stacksPerSec = clamp(num(p, "stacksPerSec", c.stacksPerSec), 1, 5000);
        c.maxRecords = clamp(num(p, "maxRecords", c.maxRecords), 10, 20000);
        c.flushSec = clamp(num(p, "flushSec", c.flushSec), 1, 60);
        try {
            c.epoch = Long.parseLong(p.getProperty("epoch", "0").trim());
        } catch (Throwable ignored) {
        }
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }

    private static int num(Properties p, String k, int d) {
        try {
            return Integer.parseInt(p.getProperty(k, "").trim());
        } catch (Throwable t) {
            return d;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
