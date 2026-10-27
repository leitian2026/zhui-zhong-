package com.textrec.lsp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 配置校验（纯 Java，不依赖 Android）：粘贴配置 / 任务清单、方法追踪清单写漏、写错、写得不规范时，
 * 给出准确的错误原因（第几行、写了什么、哪里不对、应该怎么写），而不是静默忽略。
 *
 * 为什么需要：RecConfig 读配置时，认不出的 key 直接忽略、认不出的数字用默认值、认不出的布尔值当 false；
 * 拆分粘贴文本时，不是「key=value」格式的行一律当任务清单。所以漏一个字母、漏一个等号、多一个空格都不会报错，只是悄悄不生效。
 * 这里把这些「悄悄不生效」的情况一条条列出来。error = 一定不会按你想的生效；warn = 能生效但很可能不是你想要的。
 *
 * 检查分三层：
 *  1. 写法：拼写、全角符号、数字 / 布尔格式、@选项、方法追踪行、任务清单行（单个字段 / 单行就能判断的）；
 *  2. 关系：父子开关（子项开着、父开关关着）、只对方法追踪有意义的项没写方法追踪、方法追踪被排除名单排除、任务清单和配置不配套；
 *  3. 现实：类名是不是文档里的示例、是不是没在已有记录里出现过（类是否存在离线查不了，真正的结果在应用后由目标 App 回读，见首页「实际生效」）。
 * 字段表来自自动生成的 {@link ConfigSchema}（RecConfig.java + tools/field_meta.txt），不会和源码漂移；CI 里还会把文档示例逐个跑一遍。
 */
final class ConfigCheck {
    private ConfigCheck() {}

    static final class Issue {
        final int line; // 粘贴文本里的行号（从 1 数）；0 = 不针对某一行
        final boolean error;
        final String msg;

        Issue(int line, boolean error, String msg) {
            this.line = line;
            this.error = error;
            this.msg = msg;
        }
    }

    /** 一次检查的外部上下文（不放全局变量；不传就是默认值）。 */
    static final class Options {
        /** 已有记录 / 钩点签名里出现过的类名（首页读取记录时学到的）；null 或空 = 不做类名查表。 */
        Set<String> knownClasses;
        /** 粘贴文本里没带任务清单，但别处已经有（已保存的清单，或浮窗会用的默认清单）：不再报「没有任务清单」。 */
        boolean tasksElsewhere;
        /** 仅测试：文档里的示例用 com.demo.* 这类示例类名，校验文档示例时放行。 */
        boolean allowExampleClasses;

        Options known(Set<String> k) {
            knownClasses = k;
            return this;
        }

        Options tasksElsewhere(boolean b) {
            tasksElsewhere = b;
            return this;
        }
    }

    static final Options DEFAULT_OPTIONS = new Options();
    static final ThreadLocal<Options> CUR = new ThreadLocal<>();

    static Options opt() {
        Options o = CUR.get();
        return o != null ? o : DEFAULT_OPTIONS;
    }

    static final Pattern KEY_LINE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\s*[=:].*");
    static final Pattern KEY_ONLY = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*[=:](.*)$");

    static final class Entry {
        final int line;
        final String key;
        final String raw;      // 旧格式：物理行（含续行）原文；块格式为 null
        String value;          // 解码后的值（旧格式按 Properties 规则解码；块格式本来就是字面值）
        boolean glued;         // 旧格式：续行把两项直接粘在了一起
        boolean block;         // 来自块格式
        String section;        // 块格式：config 或列表段名
        int[] itemLines;       // 块格式列表段：每一项所在的行号

        Entry(int line, String key, String raw) {
            this.line = line;
            this.key = key;
            this.raw = raw;
        }
    }

    // ------------------------------------------------------------ 粘贴框（配置 + 任务清单）

    /**
     * 校验粘贴框里的整段文本。拆分规则和 {@link PasteBlocks#split(String)} 完全一致：
     * 有 [config] / [traceMethods] 这类段名的是块格式；否则是旧格式（以「英文标识符 + = 或 :」开头的是配置行，# 开头是注释，
     * [tasks] / [任务清单] 之后全是任务，其余都是任务清单）。
     */
    static List<Issue> checkPaste(String text) {
        return checkPaste(text, null);
    }

    static List<Issue> checkPaste(String text, Options o) {
        if (text == null) return new ArrayList<>();
        Options prev = CUR.get();
        CUR.set(o != null ? o : DEFAULT_OPTIONS);
        try {
            return PasteBlocks.isBlock(text) ? checkBlockText(text) : checkOldText(text);
        } finally {
            CUR.set(prev);
        }
    }

    static List<Issue> checkBlockText(String text) {
        ArrayList<Issue> out = new ArrayList<>();
        PasteBlocks.Parsed p = PasteBlocks.parse(text);
        for (PasteBlocks.Msg m : p.msgs) out.add(new Issue(m.line, m.error, m.text));
        ArrayList<Entry> entries = new ArrayList<>();
        for (PasteBlocks.Item it : p.items) {
            Entry e = new Entry(it.line, it.key, null);
            e.value = it.value;
            e.block = true;
            e.section = it.section;
            e.itemLines = it.itemLines;
            entries.add(e);
        }
        CheckFields.checkEntries(out, entries);
        ArrayList<String> tr = new ArrayList<>();
        ArrayList<Integer> tn = new ArrayList<>();
        for (int i = 0; i < p.taskLines.size(); i++) {
            if (p.taskLines.get(i).trim().startsWith("//")) continue;
            tr.add(p.taskLines.get(i));
            tn.add(p.taskLineNos.get(i));
        }
        CheckTasks.checkTasks(out, tr, tn);
        CheckCross.crossChecks(out, entries, tr);
        sortIssues(out);
        return out;
    }

    static List<Issue> checkOldText(String text) {
        ArrayList<Issue> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n", -1);
        ArrayList<Entry> entries = new ArrayList<>();
        ArrayList<Integer> taskIdx = new ArrayList<>();
        boolean forceTasks = false;
        String lastListKey = null;
        int lastCfgLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            int no = i + 1;
            if (t.isEmpty()) continue;
            String tl = t.toLowerCase(Locale.ROOT);
            if (tl.equals("[tasks]") || t.equals("[任务清单]")) {
                forceTasks = true;
                continue;
            }
            if (t.startsWith("[") && t.endsWith("]") && t.length() <= 12 && !t.contains("|")) {
                out.add(new Issue(no, true, "方括号标记 " + CheckText.q(t) + " 不认识。分隔任务清单只认 " + CheckText.q("[tasks]") + " 或 " + CheckText.q("[任务清单]") + "（写错了，这一行会被当成任务名）"));
                continue;
            }
            if (t.startsWith("```")) {
                out.add(new Issue(no, true, "这一行是 Markdown 代码围栏「```」，复制 AI 回复时把它也带进来了；不删掉会被当成一个叫「" + CheckText.clip(t, 12) + "」的任务。只复制代码块里面的内容"));
                continue;
            }
            if (!forceTasks && KEY_LINE.matcher(t).matches() && !t.startsWith("//")) {
                StringBuilder raw = new StringBuilder(line);
                int start = no;
                boolean cont = PasteBlocks.endsWithOddBackslash(line);
                while (cont && i + 1 < lines.length) {
                    i++;
                    raw.append('\n').append(lines[i]);
                    String nt = lines[i].trim();
                    cont = PasteBlocks.endsWithOddBackslash(lines[i]);
                    if (nt.isEmpty()) {
                        out.add(new Issue(i + 1, true, "上一行（第 " + (i) + " 行）末尾的反斜杠 " + CheckText.q("\\") + " 表示「下一行接着写」，但这一行是空的。要么删掉行尾的反斜杠，要么把后面的内容接上"));
                        break;
                    }
                    if (KEY_LINE.matcher(nt).matches()) {
                        out.add(new Issue(i + 1, true, "第 " + (i) + " 行末尾有反斜杠 " + CheckText.q("\\") + "（续行），所以这一行 " + CheckText.q(CheckText.clip(nt, 30)) + " 被并进了上一个配置项的值里，而不是一个新的配置项。去掉上一行末尾多余的反斜杠"));
                    }
                }
                java.util.regex.Matcher m = KEY_ONLY.matcher(t);
                String key = m.matches() ? m.group(1) : t;
                entries.add(new Entry(start, key, raw.toString()));
                lastListKey = CheckFields.LIST_KEYS.contains(key) ? key : null;
                lastCfgLine = i + 1;
            } else if (t.startsWith("#") && !forceTasks) {
                // 注释
            } else {
                CheckTasks.checkTaskBoundLine(out, no, t, lastListKey, lastCfgLine, forceTasks);
                taskIdx.add(i);
            }
        }
        CheckFields.checkEntries(out, entries);
        ArrayList<String> tr = new ArrayList<>();
        ArrayList<Integer> tn = new ArrayList<>();
        for (int ti : taskIdx) {
            if (lines[ti].trim().startsWith("//")) continue;
            tr.add(lines[ti]);
            tn.add(ti + 1);
        }
        CheckTasks.checkTasks(out, tr, tn);
        CheckCross.crossChecks(out, entries, tr);
        sortIssues(out);
        return out;
    }

    static void sortIssues(List<Issue> out) {
        java.util.Collections.sort(out, (a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Boolean.compare(b.error, a.error));
    }

    // ------------------------------------------------------------ 输出

    static boolean hasError(List<Issue> l) {
        for (Issue i : l) if (i.error) return true;
        return false;
    }

    static int count(List<Issue> l, boolean error) {
        int n = 0;
        for (Issue i : l) if (i.error == error) n++;
        return n;
    }

    /** 给人看的文本：先错误后警告，每条带行号。 */
    static String format(List<Issue> l) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean err = pass == 0;
            int c = count(l, err);
            if (c == 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(err ? "✗ 错误 " : "⚠ 警告 ").append(c).append(" 条").append(err ? "（这些一定不会按你想的生效）" : "（能生效，但很可能不是你想要的）").append('\n');
            for (Issue i : l) {
                if (i.error != err) continue;
                n++;
                sb.append(n).append(". ").append(i.line > 0 ? "第 " + i.line + " 行：" : "").append(i.msg).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 首页表单 / 给 AI 的报告

    /** 校验首页表单里的一份配置（带任务清单）：转成块格式文本再走同一套检查，问题不带行号。 */
    static List<Issue> checkForm(RecConfig c, String tasks, Options o) {
        ArrayList<Issue> out = new ArrayList<>();
        for (Issue i : checkPaste(PasteBlocks.export(c, tasks), o)) out.add(new Issue(0, i.error, i.msg));
        return out;
    }

    /** 「复制错误报告给 AI」：校验结果 + 带行号的原文 + 修改要求，粘给 AI 后它只改被点到的行。 */
    static String reportForAi(String text, List<Issue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("TextRec 校验了我粘贴的配置，结果在下面。请只修改被点到的行，其余内容保持原样；")
                .append("改完把完整的配置（配置 + 任务清单）整块重新发给我，仍然放在同一个代码块里。")
                .append("不要自己造类名和配置项名；类名只能从我给你的导出记录里逐字复制。\n\n");
        sb.append("===== 校验结果（行号对应下面的原文）=====\n").append(format(issues)).append('\n');
        sb.append("===== 我粘贴的原文 =====\n");
        String[] ls = (text == null ? "" : text).split("\\r?\\n", -1);
        for (int i = 0; i < ls.length; i++) sb.append(i + 1).append("| ").append(ls[i]).append('\n');
        return sb.toString();
    }
}
