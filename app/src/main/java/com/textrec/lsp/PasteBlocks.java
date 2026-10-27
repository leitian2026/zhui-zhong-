package com.textrec.lsp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 粘贴框文本的解析（纯 Java，不依赖 Android）。支持两种写法，同一个入口 {@link #split(String)}：
 *
 * <p><b>块格式（推荐，给 AI 用）</b>：按段写，值里没有任何转义，不用 {@code \n}、不用行尾反斜杠续行、正则不用双反斜杠。
 * <pre>
 * [config]
 * preset = trace
 * windowMs = 1500
 *
 * [traceMethods]
 * com.ss.android.ugc.aweme.X.Y#foo @seq
 * com.ss.android.ugc.aweme.X.Z#bar
 *
 * [keywords]
 * 福利
 * 红包
 *
 * [tasks]
 * 暂停|点一下视频暂停|
 * </pre>
 * 段名：config / traceMethods / keywords / watch / excludeList / threadFilter / pageFilter / viewFilter / valueFilter /
 * viewDumpKey / dumpPrefixes / tasks（= 任务清单）。列表段里一行一项，{@code //} 开头是注释；config 段里一行一个 {@code key = value}，{@code #} 开头是注释。
 * 第一个段名之前的行算 config 段。
 *
 * <p><b>旧格式</b>（Properties：key=value，行尾反斜杠续行）仍然支持，规则见 {@link #splitOld(String)}。
 *
 * <p>两种写法里都可以写 {@code preset=名字}：先展开成 {@link Presets} 里那一组 key=value，再被你显式写的字段覆盖。
 */
final class PasteBlocks {
    private PasteBlocks() {}

    static final String[] LIST_SECTIONS = {"keywords", "traceMethods", "watch", "excludeList", "threadFilter", "pageFilter",
            "viewFilter", "valueFilter", "viewDumpKey", "dumpPrefixes"};

    static boolean isListKey(String k) {
        for (String s : LIST_SECTIONS) if (s.equals(k)) return true;
        return false;
    }

    /** 一条解析出来的配置（config 段里的一行，或一个列表段合成的一项）。 */
    static final class Item {
        int line;             // key 所在行 / 段名所在行（从 1 数）
        String key;
        String value;         // 已经是最终值，没有转义
        String section;       // "config" 或列表段名
        int[] itemLines;      // 列表段：每一项（含 // 注释行）所在的行号
    }

    static final class Msg {
        final int line;
        final boolean error;
        final String text;

        Msg(int line, boolean error, String text) {
            this.line = line;
            this.error = error;
            this.text = text;
        }
    }

    static final class Parsed {
        final ArrayList<Item> items = new ArrayList<>();
        final ArrayList<String> taskLines = new ArrayList<>();
        final ArrayList<Integer> taskLineNos = new ArrayList<>();
        final ArrayList<Msg> msgs = new ArrayList<>();
        String presetName;
        int presetLine;
    }

    private static final Pattern HEADER = Pattern.compile("^\\[([^\\[\\]]{1,40})\\]$");
    private static final Pattern KV = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*([=:])\\s*(.*)$");

    /** 段名 → 规范名；不认识返回 null。 */
    static String sectionOf(String name) {
        String n = name.trim();
        if (n.equalsIgnoreCase("config") || n.equals("配置")) return "config";
        if (n.equalsIgnoreCase("tasks") || n.equals("任务清单")) return "tasks";
        for (String s : LIST_SECTIONS) if (s.equalsIgnoreCase(n)) return s;
        return null;
    }

    static List<String> sectionNames() {
        ArrayList<String> l = new ArrayList<>();
        l.add("config");
        for (String s : LIST_SECTIONS) l.add(s);
        l.add("tasks");
        return l;
    }

    /** 文本里有没有块格式的段名（只有 [tasks] / [任务清单] 不算，那是旧格式里的任务清单分隔）。 */
    static boolean isBlock(String text) {
        if (text == null) return false;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            Matcher m = HEADER.matcher(t);
            String c = null;
            if (m.matches()) {
                c = sectionOf(m.group(1));
            } else if (t.startsWith("[") && t.indexOf(']') > 1) {
                c = sectionOf(t.substring(1, t.indexOf(']'))); // 段名后面多写了别的内容，也按块格式处理（parse 会报错）
            }
            if (c != null && !c.equals("tasks")) return true;
        }
        return false;
    }

    /** 解析块格式。结构性问题（不认识的段名、config 段里不是 key = value 的行……）放在 msgs 里，由 ConfigCheck 转成报告。 */
    static Parsed parse(String text) {
        Parsed out = new Parsed();
        String[] lines = (text == null ? "" : text).split("\\r?\\n", -1);
        String section = "config";
        boolean unknownSection = false;
        LinkedHashMap<String, Item> listItems = new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<String>> listLines = new LinkedHashMap<>();
        LinkedHashMap<String, ArrayList<Integer>> listNos = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            int no = i + 1;
            if (t.isEmpty()) continue;
            if (t.startsWith("```")) {
                out.msgs.add(new Msg(no, true, "这一行是 Markdown 代码围栏「```」，复制 AI 回复时把它也带进来了。只复制代码块里面的内容"));
                continue;
            }
            Matcher hm = HEADER.matcher(t);
            if (hm.matches()) {
                String canon = sectionOf(hm.group(1));
                if (canon == null) {
                    String near = CheckText.nearest(hm.group(1).trim(), sectionNames());
                    out.msgs.add(new Msg(no, true, "不认识的段名 `" + t + "`，这一段的内容全部被忽略。可用的段名：" + joinNames()
                            + (near != null ? "。你是不是想写 `[" + near + "]`？" : "")));
                    unknownSection = true;
                    continue;
                }
                unknownSection = false;
                section = canon;
                if (isListKey(canon)) {
                    if (listItems.containsKey(canon)) {
                        out.msgs.add(new Msg(no, false, "段 `[" + canon + "]` 出现了不止一次，内容会按先后顺序合并成一份"));
                    } else {
                        Item it = new Item();
                        it.line = no;
                        it.key = canon;
                        it.section = canon;
                        listItems.put(canon, it);
                        listLines.put(canon, new ArrayList<String>());
                        listNos.put(canon, new ArrayList<Integer>());
                        out.items.add(it);
                    }
                }
                continue;
            }
            if (t.startsWith("[") && t.indexOf(']') > 0) {
                String inner = t.substring(1, t.indexOf(']'));
                out.msgs.add(new Msg(no, true, "段名行 `" + clip(t) + "` 后面不能再写别的内容；段名要单独占一行，写成 `[" + inner.trim() + "]`"));
                String canon = sectionOf(inner);
                if (canon != null) {
                    unknownSection = false;
                    section = canon;
                    if (isListKey(canon) && !listItems.containsKey(canon)) {
                        Item it = new Item();
                        it.line = no;
                        it.key = canon;
                        it.section = canon;
                        listItems.put(canon, it);
                        listLines.put(canon, new ArrayList<String>());
                        listNos.put(canon, new ArrayList<Integer>());
                        out.items.add(it);
                    }
                } else {
                    unknownSection = true;
                }
                continue;
            }
            if (unknownSection) continue;
            if (section.equals("tasks")) {
                out.taskLines.add(line);
                out.taskLineNos.add(no);
            } else if (section.equals("config")) {
                if (t.startsWith("#")) continue;
                Matcher km = KV.matcher(t);
                if (!km.matches()) {
                    out.msgs.add(new Msg(no, true, "[config] 段里这一行不是 `key = 值` 的格式：`" + clip(t) + "`（配置项写在 [config] 里，一行一个；列表型的项"
                            + "（traceMethods、keywords…）请单独开一个 `[段名]`，一行一项；任务清单放 [tasks]）"));
                    continue;
                }
                if (km.group(2).equals(":")) {
                    out.msgs.add(new Msg(no, true, "[config] 段里 `" + km.group(1) + "` 后面用的是冒号，块格式只认等号：写成 `" + km.group(1) + " = " + km.group(3).trim() + "`"));
                }
                Item it = new Item();
                it.line = no;
                it.key = km.group(1);
                it.value = km.group(3).trim();
                it.section = "config";
                out.items.add(it);
                if (it.key.equals("preset")) {
                    out.presetName = it.value;
                    out.presetLine = no;
                }
            } else {
                ArrayList<String> ls = listLines.get(section);
                ArrayList<Integer> ns = listNos.get(section);
                if (ls == null) continue;
                ls.add(t);
                ns.add(no);
            }
        }
        for (Map.Entry<String, Item> e : listItems.entrySet()) {
            ArrayList<String> ls = listLines.get(e.getKey());
            ArrayList<Integer> ns = listNos.get(e.getKey());
            StringBuilder sb = new StringBuilder();
            int[] nos = new int[ls.size()];
            for (int i = 0; i < ls.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(ls.get(i));
                nos[i] = ns.get(i);
            }
            e.getValue().value = sb.toString();
            e.getValue().itemLines = nos;
        }
        return out;
    }

    private static String joinNames() {
        StringBuilder sb = new StringBuilder();
        for (String s : sectionNames()) sb.append(sb.length() > 0 ? " " : "").append("[").append(s).append("]");
        return sb.toString();
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    // ------------------------------------------------------------ 拆分：返回 {配置文本（Properties）, 任务清单文本}

    /**
     * 把粘贴文本拆成 {Properties 格式的配置文本, 任务清单文本}，供 {@link RecConfig#fromText(String)} 和任务清单解析使用。
     * 块格式 / 旧格式都走这里；{@code preset=} 在这里展开。配置文本里不会再有 preset 这一行。
     */
    static String[] split(String text) {
        if (text == null) text = "";
        if (isBlock(text)) {
            Parsed p = parse(text);
            StringBuilder cfg = new StringBuilder();
            appendPreset(cfg, p.presetName);
            for (Item it : p.items) {
                if (it.key.equals("preset")) continue;
                cfg.append(it.key).append('=').append(escapeValue(it.value)).append('\n');
            }
            StringBuilder tasks = new StringBuilder();
            for (String l : p.taskLines) tasks.append(l).append('\n');
            return new String[]{cfg.toString(), tasks.toString()};
        }
        String[] old = splitOld(text);
        String cfg = old[0];
        String preset = null;
        try {
            Properties pr = new Properties();
            pr.load(new StringReader(cfg));
            preset = pr.getProperty("preset");
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        appendPreset(sb, preset);
        sb.append(cfg);
        return new String[]{sb.toString(), old[1]};
    }

    private static void appendPreset(StringBuilder sb, String name) {
        for (String[] kv : Presets.pairs(name)) sb.append(kv[0]).append('=').append(escapeValue(kv[1])).append('\n');
    }

    /**
     * 旧格式的拆分规则（逐行判断，不需要分隔标记）：
     *  - 以「字母或下划线开头的标识符 =（或 :）」开头的行是配置行（Properties 的 key=value）；行尾是奇数个反斜杠表示下一行是它的续行，续行也归配置。
     *  - 其余的非空行、非 # 注释行是任务清单行，// 开头的任务注释原样保留给 GuideService 去处理。
     *  - 也可以显式写一行 [任务清单] / [tasks]，之后的所有行都算任务清单（任务名恰好像 key= 时用）。
     */
    static String[] splitOld(String text) {
        StringBuilder cfg = new StringBuilder(), tasks = new StringBuilder();
        boolean forceTasks = false, cont = false;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (cont) {
                cfg.append(line).append('\n');
                cont = endsWithOddBackslash(line);
                continue;
            }
            if (t.equalsIgnoreCase("[tasks]") || t.equals("[任务清单]")) {
                forceTasks = true;
                continue;
            }
            if (t.isEmpty()) continue;
            if (!forceTasks && t.matches("^[A-Za-z_][A-Za-z0-9_]*\\s*[=:].*") && !t.startsWith("//")) {
                cfg.append(line).append('\n');
                cont = endsWithOddBackslash(line);
            } else if (t.startsWith("#") && !forceTasks) {
                cfg.append(line).append('\n');
            } else {
                tasks.append(line).append('\n');
            }
        }
        return new String[]{cfg.toString(), tasks.toString()};
    }

    static boolean endsWithOddBackslash(String s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    /** 把一个值写成 Properties 的值（反斜杠、换行、制表符、非 ASCII 都转义），load 之后和原值完全一样。 */
    static String escapeValue(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\') sb.append("\\\\");
            else if (ch == '\n') sb.append("\\n");
            else if (ch == '\r') sb.append("\\r");
            else if (ch == '\t') sb.append("\\t");
            else if (ch == '\f') sb.append("\\f");
            else if (ch == ' ' && i == 0) sb.append("\\ ");
            else if (ch < 32 || ch > 126) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 导出：当前配置 → 块格式（和默认值不同的字段才写）

    /** 把配置写成块格式文本（只写和默认值不同的字段；tasks 非空时附在最后的 [tasks] 段）。粘回粘贴框能得到同一份配置。 */
    static String export(RecConfig c, String tasks) {
        RecConfig d = new RecConfig();
        StringBuilder cfg = new StringBuilder("[config]\n");
        StringBuilder lists = new StringBuilder();
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.key.equals("epoch") || f.key.equals("traceOnly")) continue;
            Object cv, dv;
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                cv = fl.get(c);
                dv = fl.get(d);
            } catch (Throwable e) {
                continue;
            }
            if (cv == null || cv.equals(dv)) continue;
            if (isListKey(f.key)) {
                ArrayList<String> items = new ArrayList<>();
                String s = String.valueOf(cv);
                if (f.key.equals("keywords")) {
                    items.addAll(RecConfig.split(s, c.regex));
                } else if (f.key.equals("traceMethods") || f.key.equals("watch")) {
                    for (String p : s.split("\\r?\\n|\\r")) if (!p.trim().isEmpty()) items.add(p.trim());
                } else {
                    items.addAll(RecConfig.splitLines(s));
                }
                if (items.isEmpty()) {
                    cfg.append(f.key).append(" =\n");
                } else {
                    lists.append('\n').append('[').append(f.key).append("]\n");
                    for (String it : items) lists.append(it).append('\n');
                }
            } else {
                cfg.append(f.key).append(" = ").append(cv).append('\n');
            }
        }
        StringBuilder out = new StringBuilder(cfg).append(lists);
        if (tasks != null && !tasks.trim().isEmpty()) {
            out.append("\n[tasks]\n");
            for (String l : tasks.split("\\r?\\n")) if (!l.trim().isEmpty()) out.append(l.trim()).append('\n');
        }
        return out.toString();
    }
}
