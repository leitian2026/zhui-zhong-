package com.textrec.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** 配置：模块界面写出，目标 App 里的 Hook 读取。两边共用同一个类。 */
public final class RecConfig {
    public boolean enabled = true;
    /** false = 全量记录（默认）；true = 只记录命中关键字的文字 */
    public boolean keywordMode = false;
    public String keywords = "";
    public boolean regex = false;
    public boolean traceSet = true;
    public boolean traceDraw = true;
    /** 每条记录里保存的调用栈最大帧数 */
    public int stackDepth = 30;
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
            p.setProperty("traceSet", String.valueOf(traceSet));
            p.setProperty("traceDraw", String.valueOf(traceDraw));
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
