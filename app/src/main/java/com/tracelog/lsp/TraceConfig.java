package com.tracelog.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 配置：模块界面写出，目标 App 里的 Hook 读取。两边共用同一个类。 */
public final class TraceConfig {
    public boolean enabled = true;
    public boolean traceText = true;
    public boolean traceColor = false;
    public boolean classStack = false;
    public String keywords = "ZZTEST123";
    public String colorFilter = "";
    public String classes = "";
    public int stackDepth = 20;
    public int maxPerMethod = 100;
    /** 类追踪时，每隔 dumpEvery 次调用，把 this 对象周围的字段值（含 LiveData 里的值）记一份；同时统计第一个数值参数的最小 / 最大值。 */
    public boolean dumpFields = false;
    public int dumpEvery = 25;

    private volatile List<String> kwCache, cfCache, clsCache;

    public List<String> keywordList() {
        if (kwCache == null) kwCache = split(keywords);
        return kwCache;
    }

    public List<String> colorFilterList() {
        if (cfCache == null) cfCache = split(colorFilter);
        return cfCache;
    }

    public List<String> classSpecs() {
        if (clsCache == null) clsCache = split(classes);
        return clsCache;
    }

    static List<String> split(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[,\\n\\r]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public String summary() {
        return "enabled=" + enabled + " text=" + traceText + " color=" + traceColor
                + " kw=" + keywordList() + " colorFilter=" + colorFilterList()
                + " classes=" + classSpecs().size() + " depth=" + stackDepth
                + " max=" + maxPerMethod + " classStack=" + classStack;
    }

    public String toText() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("traceText", String.valueOf(traceText));
            p.setProperty("traceColor", String.valueOf(traceColor));
            p.setProperty("classStack", String.valueOf(classStack));
            p.setProperty("keywords", keywords);
            p.setProperty("colorFilter", colorFilter);
            p.setProperty("classes", classes);
            p.setProperty("stackDepth", String.valueOf(stackDepth));
            p.setProperty("maxPerMethod", String.valueOf(maxPerMethod));
            p.setProperty("dumpFields", String.valueOf(dumpFields));
            p.setProperty("dumpEvery", String.valueOf(dumpEvery));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            // Properties.store 输出纯 ASCII（非 ASCII 会转成 \\uXXXX），换行也被转义，可安全放进 shell heredoc
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    public static TraceConfig fromText(String text) {
        TraceConfig c = new TraceConfig();
        if (text == null || text.isEmpty()) return c;
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static TraceConfig read(File f) {
        TraceConfig c = new TraceConfig();
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static void fill(TraceConfig c, Properties p) {
        c.enabled = bool(p, "enabled", c.enabled);
        c.traceText = bool(p, "traceText", c.traceText);
        c.traceColor = bool(p, "traceColor", c.traceColor);
        c.classStack = bool(p, "classStack", c.classStack);
        c.keywords = p.getProperty("keywords", c.keywords);
        c.colorFilter = p.getProperty("colorFilter", c.colorFilter);
        c.classes = p.getProperty("classes", c.classes);
        c.stackDepth = clamp(num(p, "stackDepth", c.stackDepth), 1, 60);
        c.maxPerMethod = clamp(num(p, "maxPerMethod", c.maxPerMethod), 1, 5000);
        c.dumpFields = bool(p, "dumpFields", c.dumpFields);
        c.dumpEvery = clamp(num(p, "dumpEvery", c.dumpEvery), 1, 1000);
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
