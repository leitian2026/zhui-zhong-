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
import com.textrec.lsp.ConfigCheck.Entry;
import com.textrec.lsp.ConfigCheck.Issue;

/** 单个配置项 / 一行的检查：拼写、类型、范围、续行、手写过滤列表。（纯 Java，入口见 {@link ConfigCheck}） */
final class CheckFields {
    private CheckFields() {}


    static final Set<String> BOOLS = new HashSet<>();
    static final Set<String> STRS = new HashSet<>();
    /** key → {key, 最小, 最大, 默认} */
    static final Map<String, String[]> INTS = new HashMap<>();
    static final List<String> ALL_KEYS = new ArrayList<>();
    /** 逗号 / 换行分隔的列表型配置项。 */
    static final Set<String> LIST_KEYS = new HashSet<>(Arrays.asList(PasteBlocks.LIST_SECTIONS));

    static {
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            ALL_KEYS.add(f.key);
            if (f.type.equals("bool")) BOOLS.add(f.key);
            else if (f.type.equals("string")) STRS.add(f.key);
            else if (f.hasRange) INTS.put(f.key, new String[]{f.key, String.valueOf(f.min), String.valueOf(f.max), f.def});
        }
        ALL_KEYS.add("preset");
    }

    static void checkEntries(List<Issue> out, List<Entry> entries) {
        Map<String, Entry> seen = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.value == null) {
                // 旧格式：按 Properties 的转义规则解码
                try {
                    Properties p = new Properties();
                    p.load(new StringReader(e.raw));
                    e.value = p.getProperty(e.key);
                    if (e.value == null) {
                        // key 带了奇怪字符导致 Properties 解析出别的 key，退回手工取
                        java.util.Enumeration<?> names = p.propertyNames();
                        e.value = names.hasMoreElements() ? p.getProperty((String) names.nextElement()) : "";
                    }
                } catch (java.io.IOException ex) {
                    continue;
                } catch (IllegalArgumentException ex) {
                    out.add(new Issue(e.line, true, CheckText.q(e.key) + " 的值里有不完整的 " + CheckText.q("\\u") + " 转义：" + CheckText.q("\\u") + " 后面必须紧跟 4 位十六进制数字（如 " + CheckText.q("\\u4e2d") + "）。路径 / 反斜杠要写成 " + CheckText.q("\\\\")));
                    continue;
                }
                e.glued = checkGlue(out, e);
            }
            Entry prev = seen.get(e.key);
            if (prev != null) {
                boolean list = LIST_KEYS.contains(e.key);
                if (list) {
                    out.add(new Issue(e.line, true, CheckText.q(e.key) + " 写了两次（第 " + prev.line + " 行和第 " + e.line + " 行）。后面的会把前面的整个覆盖掉，第 " + prev.line
                            + " 行里的所有项都丢了，只剩第 " + e.line + " 行。" + (e.block || prev.block
                            ? "把它们合并：放进同一个 " + CheckText.q("[" + e.key + "]") + " 段，一行一项"
                            : "把它们合并成一个配置项：写在同一行用 " + CheckText.q("\\n") + " 隔开（或块格式的 " + CheckText.q("[" + e.key + "]") + " 段）")));
                } else {
                    out.add(new Issue(e.line, false, CheckText.q(e.key) + " 写了两次（第 " + prev.line + " 行和第 " + e.line + " 行），后面的覆盖前面的，只有第 " + e.line + " 行生效"));
                }
            }
            seen.put(e.key, e);
            checkOne(out, e);
        }
    }

    static boolean endsWithNewlineEscape(String s) {
        if (!s.endsWith("n")) return false;
        int n = 0;
        for (int i = s.length() - 2; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    static boolean startsWithNewlineEscape(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == '\\') n++;
        return n % 2 == 1 && n < s.length() && s.charAt(n) == 'n';
    }

    /**
     * 行尾反斜杠 = 续行：下一行直接接在这一行后面，中间不补换行也不补逗号。
     * 所以列表型配置项（traceMethods / keywords…）分行写时，行尾反斜杠前面必须自己写 \n（或逗号），否则两项会粘成一项。
     * 注意 {@code \\n}（两个反斜杠）在 Properties 里是字面的反斜杠加 n，不是换行，不能算分隔。
     */
    static boolean checkGlue(List<Issue> out, Entry e) {
        if (!LIST_KEYS.contains(e.key)) return false;
        String[] ls = e.raw.split("\n", -1);
        boolean any = false;
        for (int i = 0; i + 1 < ls.length; i++) {
            String cur = ls[i];
            if (!PasteBlocks.endsWithOddBackslash(cur)) continue;
            String head = cur.substring(0, cur.length() - 1);
            String next = ls[i + 1].replaceAll("^\\s+", "");
            if (head.trim().isEmpty() || next.isEmpty()) continue;
            boolean sepOk = endsWithNewlineEscape(head) || head.trim().endsWith(",") || next.startsWith(",") || startsWithNewlineEscape(next);
            if (sepOk) continue;
            String a = head.trim(), b = next;
            boolean doubled = head.endsWith("\\\\n");
            out.add(new Issue(e.line + i, true, CheckText.q(e.key) + " 第 " + (e.line + i) + " 行末尾的反斜杠 " + CheckText.q("\\") + " 只是把下一行直接接在后面，不会换行：" + CheckText.q(CheckText.clip(a.substring(Math.max(0, a.length() - 16)) + b.substring(0, Math.min(b.length(), 16)), 40))
                    + " 会粘成一项，" + (e.key.equals("traceMethods") ? "多条方法追踪就会变成一条错的" : "两个词会变成一个词")
                    + (doubled ? "。你写的是 " + CheckText.q("\\\\n") + "（两个反斜杠），在 Properties 里它是字面的反斜杠加 n，不是换行；换行只写一个反斜杠 " + CheckText.q("\\n")
                    : "。要分成两项，在行尾反斜杠前面加 " + CheckText.q("\\n") + "（写成 " + CheckText.q(CheckText.clip(a, 30) + "\\n\\") + "）" + (e.key.equals("traceMethods") ? "" : "，或加逗号"))
                    + "。更省事的办法：改用块格式，每个列表一个 " + CheckText.q("[" + e.key + "]") + " 段，一行一项，什么都不用转义"));
            any = true;
        }
        return any;
    }

    static void checkPreset(List<Issue> out, int ln, String v) {
        if (v.isEmpty()) {
            out.add(new Issue(ln, true, CheckText.q("preset") + " 的值是空的。可用预设：" + Presets.names() + "。不用预设就整行删掉"));
        } else if (Presets.get(v) == null) {
            String k = CheckText.nearest(v, Presets.nameList());
            out.add(new Issue(ln, true, "不认识的预设 " + CheckText.q(v) + "，会被忽略（预设写错就等于没写，它展开的那些开关一个都不会设）。"
                    + (k != null ? "你是不是想写 " + CheckText.q("preset=" + k) + "？" : "") + "可用预设：" + Presets.names()));
        }
    }

    static void checkOne(List<Issue> out, Entry e) {
        String key = e.key, v = e.value == null ? "" : e.value;
        int ln = e.line;
        String vt = v.trim();
        if (key.equals("preset")) {
            checkPreset(out, ln, vt);
            return;
        }
        if (key.equals("traceOnly")) {
            out.add(new Issue(ln, false, CheckText.q("traceOnly") + " 已废弃，写了也忽略。想只记方法追踪，用 " + CheckText.q("preset=trace") + "（或把 traceSet / traceDraw / traceColor / autoParams / traceIcons / traceSetOnly / traceState 都写成 false）"));
            return;
        }
        boolean isBool = BOOLS.contains(key), isInt = INTS.containsKey(key), isStr = STRS.contains(key), isEpoch = key.equals("epoch");
        if (!isBool && !isInt && !isStr && !isEpoch) {
            String k = CheckText.nearest(key, ALL_KEYS);
            if (k != null && !k.equals(key)) {
                boolean caseOnly = k.equalsIgnoreCase(key);
                out.add(new Issue(ln, true, "不认识的配置项 " + CheckText.q(key) + "，会被直接忽略。" + (caseOnly ? "配置项名区分大小写，应写成 " + CheckText.q(k) : "你是不是想写 " + CheckText.q(k) + "？（" + CheckText.diffHint(key, k) + "）")));
            } else {
                out.add(new Issue(ln, true, "不认识的配置项 " + CheckText.q(key) + "，会被直接忽略（没有哪个配置项和它像）。配置项名见《写配置手册》的字段表，不要自己造"));
            }
            return;
        }
        String fw = isStr ? null : CheckText.fullwidthIn(vt);
        if (fw != null) out.add(new Issue(ln, true, CheckText.q(key) + " 的值 " + CheckText.q(CheckText.clip(vt, 20)) + " 里有全角符号：" + fw));
        if (vt.startsWith("=") || vt.startsWith(":")) {
            out.add(new Issue(ln, true, CheckText.q(key) + " 的值以 " + CheckText.q(vt.substring(0, 1)) + " 开头（" + CheckText.q(CheckText.clip(vt, 20)) + "），多写了一个等号 / 冒号：应写成 " + CheckText.q(key + "=" + vt.substring(1).trim())));
            return;
        }
        if (isBool) {
            String lv = vt.toLowerCase(Locale.ROOT);
            if (lv.equals("true") || lv.equals("false")) return;
            String hint;
            if (lv.matches("^(true|false)\\s+(#|//|--).*"))
                hint = "后面跟了行内注释。配置里不支持行内注释（" + CheckText.q("#") + " 后面的内容会变成值的一部分，整个值就不是 true / false 了，会被当成 false）；注释要单独占一行";
            else if (lv.isEmpty()) hint = "值是空的，会被当成 false。要开写 " + CheckText.q(key + "=true") + "，要关写 " + CheckText.q(key + "=false");
            else if (lv.equals("yes") || lv.equals("on") || lv.equals("1") || lv.equals("y") || lv.equals("t") || lv.equals("开") || lv.equals("是"))
                hint = "只认英文 true / false，" + CheckText.q(vt) + " 会被当成 false（关）。要开写 " + CheckText.q("true");
            else if (lv.equals("no") || lv.equals("off") || lv.equals("0") || lv.equals("n") || lv.equals("f") || lv.equals("关") || lv.equals("否"))
                hint = "只认英文 true / false，" + CheckText.q(vt) + " 会被当成 false。要关请写 " + CheckText.q("false");
            else if (CheckText.dist(lv, "true") <= 2) hint = "拼写不对，会被当成 false（关）。你是不是想写 " + CheckText.q("true") + "？";
            else if (CheckText.dist(lv, "false") <= 2) hint = "拼写不对，会被当成 false。你是不是想写 " + CheckText.q("false") + "？";
            else hint = "不是 true 也不是 false，会被当成 false（关）";
            out.add(new Issue(ln, true, CheckText.q(key) + " 是开关，值 " + CheckText.q(CheckText.clip(vt, 20)) + "：" + hint));
            return;
        }
        if (isInt || isEpoch) {
            if (vt.isEmpty()) {
                out.add(new Issue(ln, true, CheckText.q(key) + " 的值是空的，会用默认值" + defText(key) + "。要么填整数，要么整行删掉"));
                return;
            }
            long n;
            try {
                n = Long.parseLong(vt);
            } catch (NumberFormatException ex) {
                String why;
                if (vt.matches("-?\\d+\\s+(#|//|--).*")) why = "后面跟了行内注释，配置里不支持行内注释，注释要单独占一行";
                else if (vt.matches("-?\\d+\\.\\d+")) why = "是小数，只认整数";
                else if (vt.matches("-?[\\dOo]*[Oo][\\dOo]*") && vt.matches(".*\\d.*")) why = "里有字母 " + CheckText.q(vt.replaceAll("[^Oo]", "").substring(0, 1)) + "，是不是把数字 0 写成了字母 o？";
                else if (vt.matches("-?[\\dlI]*[lI][\\dlI]*") && vt.matches(".*\\d.*")) why = "里有字母 " + CheckText.q(vt.replaceAll("[^lI]", "").substring(0, 1)) + "，是不是把数字 1 写成了字母？";
                else if (vt.matches("-?\\d+\\s*[A-Za-z\u4e00-\u9fa5]+")) why = "后面带了单位 " + CheckText.q(vt.replaceAll("^-?\\d+\\s*", "")) + "，只能写纯数字（单位已经在配置项名里：Ms = 毫秒，Sec = 秒）";
                else if (vt.matches("-?[\\d,]+") && vt.contains(",")) why = "数字里有逗号分隔符，要写成纯数字";
                else if (vt.matches(".*\\d.*") && vt.matches(".*[Oo].*")) why = "含有字母 O / o（是不是把数字 0 写成了字母 o？）";
                else if (vt.matches(".*\\d.*") && vt.matches(".*[lI].*")) why = "含有字母 l / I（是不是把数字 1 写成了字母？）";
                else why = "不是整数";
                out.add(new Issue(ln, true, CheckText.q(key) + " 要填整数，" + CheckText.q(CheckText.clip(vt, 20)) + " " + why + "；这个值会被忽略，改用默认值" + defText(key)));
                return;
            }
            String[] r = INTS.get(key);
            if (r != null) {
                long lo = Long.parseLong(r[1]), hi = Long.parseLong(r[2]);
                if (n < lo || n > hi) {
                    out.add(new Issue(ln, false, CheckText.q(key) + " = " + n + " 超出允许范围 " + lo + "～" + hi + "，会被改成 " + (n < lo ? lo : hi)));
                }
            }
            return;
        }
        // 字符串项
        String ph = CheckText.placeholderIn(v);
        if (ph != null) out.add(new Issue(ln, true, CheckText.q(key) + " 的值里留着没替换的占位文字 " + CheckText.q(ph) + "。不知道要填什么就整行删掉"));
        if (LIST_KEYS.contains(key) && !key.equals("traceMethods")) {
            String f2 = CheckText.fullwidthIn(v);
            if (f2 != null) out.add(new Issue(ln, true, CheckText.q(key) + " 是用逗号 / 换行分隔的列表，" + f2 + "（全角逗号不会被当成分隔符，整串会被当成一个词）"));
        }
        if (LIST_KEYS.contains(key) && !key.equals("keywords") && v.contains("\\n")) {
            out.add(new Issue(ln, true, CheckText.q(key) + " 的值里有字面的 " + CheckText.q("\\n") + "（反斜杠加 n 两个字符），它不是换行。"
                    + (e.block ? "块格式里一行只能写一项，要写多项就分成多行"
                    : "Properties 里 " + CheckText.q("\\\\n") + "（两个反斜杠）才会解出这两个字符；换行要写一个反斜杠 " + CheckText.q("\\n") + "。更省事的办法是改用块格式（" + CheckText.q("[" + key + "]") + " 段，一行一项）")));
        }
        if (key.equals("keywords") && e.block && !"config".equals(e.section)) {
            for (String s : v.split("\n")) {
                String t = s.trim();
                if (t.startsWith("//") || t.startsWith("#")) {
                    out.add(new Issue(ln, false, CheckText.q("[keywords]") + " 段里每一行都是一个关键字，没有注释语法：" + CheckText.q(CheckText.clip(t, 24)) + " 会被当成关键字。要删掉这行"));
                    break;
                }
            }
        }
        if (key.equals("traceMethods") && !e.glued) CheckTrace.checkTraceMethods(out, v, ln, "traceMethods", false, e.itemLines);
        if (key.equals("watch") && !e.glued) CheckTrace.checkWatch(out, v, ln, false, e.itemLines);
        if (key.equals("excludeList")) checkExcludeItems(out, ln, v);
        if (key.equals("dumpPrefixes")) checkPrefixItems(out, ln, v);
        if (key.equals("viewProbe")) checkClassItems(out, ln, key, v);
        if (key.equals("probeAfterMs")) checkProbeTimes(out, ln, v);
        if (key.equals("threadFilter") || key.equals("pageFilter") || key.equals("viewFilter")) checkWordItems(out, ln, key, v);
    }

    static String defText(String key) {
        String[] r = INTS.get(key);
        return r == null || "-1".equals(r[3]) ? "" : " " + r[3];
    }

    // ------------------------------------------------------------ 手写过滤列表

    static void checkExcludeItems(List<Issue> out, int ln, String value) {
        for (String raw : value.split("[,\\n\\r]+")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            String pre = CheckText.q("excludeList") + " 的 " + CheckText.q(CheckText.clip(t, 40)) + "：";
            String fw = CheckText.fullwidthIn(t);
            if (fw != null) {
                out.add(new Issue(ln, true, pre + fw));
                continue;
            }
            if (t.indexOf(' ') >= 0 || t.indexOf('\t') >= 0) {
                out.add(new Issue(ln, true, pre + "里面有空格。一项就是一个 " + CheckText.q("类#方法") + " / " + CheckText.q("类#*") + " / " + CheckText.q("#方法") + " / 类名或包名前缀，项与项之间用逗号或换行分隔"));
                continue;
            }
            if (t.indexOf('@') >= 0) {
                out.add(new Issue(ln, true, pre + "排除名单不支持 " + CheckText.q("@选项") + "（@count 之类只能写在 traceMethods 的行尾）"));
                continue;
            }
            if (t.indexOf('(') >= 0 || t.indexOf(')') >= 0) {
                out.add(new Issue(ln, true, pre + "不要带括号参数，排除的是方法名，所有同名重载一起排除"));
                continue;
            }
            int h = t.indexOf('#');
            if (h >= 0) {
                String c = t.substring(0, h), m = t.substring(h + 1);
                if (m.indexOf('#') >= 0) out.add(new Issue(ln, true, pre + "出现了不止一个 " + CheckText.q("#")));
                else if (m.isEmpty()) out.add(new Issue(ln, true, pre + CheckText.q("#") + " 后面没有方法名（排除整个类写 " + CheckText.q("类#*") + "）"));
                else if (!m.equals("*") && !CheckTrace.METHOD_OK.matcher(m).matches()) out.add(new Issue(ln, true, pre + "方法名 " + CheckText.q(m) + " 不合法"));
                else if (c.isEmpty() && m.equals("*")) out.add(new Issue(ln, true, pre + CheckText.q("#*") + " 会排除所有类的所有方法，等于什么都不记"));
                else if (!c.isEmpty()) {
                    String bad = CheckTrace.badClass(c);
                    if (bad != null) out.add(new Issue(ln, true, pre + bad));
                }
            } else if (!t.matches("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z0-9_$]*)*\\.?$")) {
                out.add(new Issue(ln, true, pre + "不是合法的类名 / 包名前缀（要排除某个方法写 " + CheckText.q("类#方法") + "，任何类里的某方法写 " + CheckText.q("#方法") + "）"));
            }
        }
    }

    /** 每行一个类全名的列表（viewProbe）：类名写法、示例类名、没见过的类名。 */
    static void checkClassItems(List<Issue> out, int ln, String key, String value) {
        for (String raw : value.split("[\\r\\n,]+")) {
            String cls = raw.trim();
            if (cls.isEmpty() || cls.startsWith("//")) continue;
            String pre = CheckText.q(key) + " 的 " + CheckText.q(CheckText.clip(cls, 50)) + "：";
            String bad = CheckTrace.badClass(cls);
            if (bad != null) {
                out.add(new Issue(ln, true, pre + bad));
            } else if (!ConfigCheck.opt().allowExampleClasses && CheckTrace.isExampleClass(cls)) {
                out.add(new Issue(ln, true, pre + "是文档 / 示例里的占位类名，目标 App 里不存在。类名只能从导出的记录或视图树里逐字复制，不能自己编"));
            } else if (!ConfigCheck.opt().allowExampleClasses && CheckTrace.isWeakExampleClass(cls)) {
                out.add(new Issue(ln, false, pre + "包名像是示例包名（com.example 之类）。如果目标 App 真的是这个包，可以忽略；否则类名要从导出里逐字复制"));
            } else if (cls.indexOf('.') < 0) {
                out.add(new Issue(ln, false, pre + "没有包名；除非目标类真的在默认包，否则要写全名（包名.类名）"));
            } else {
                java.util.Set<String> kn = ConfigCheck.opt().knownClasses;
                if (kn != null && !kn.isEmpty() && !kn.contains(cls) && !CheckTrace.isSystemClass(cls))
                    out.add(new Issue(ln, false, pre + "这个类没有在已有记录里出现过。类名要逐字从导出里复制，不能凭印象写；如果是刚找到的新类，可以忽略这条，拍快照时视图树里没有它会写「未找到实例」"));
            }
        }
    }

    /** probeAfterMs：逗号分隔的毫秒数，0～10000，最多 8 个。 */
    static void checkProbeTimes(List<Issue> out, int ln, String value) {
        String vt = value.trim();
        String k = CheckText.q("probeAfterMs");
        if (vt.isEmpty()) {
            out.add(new Issue(ln, false, k + " 是空的，会用默认 0,200,1000,2500。要么填毫秒数，要么整行删掉"));
            return;
        }
        String fw = CheckText.fullwidthIn(vt);
        if (fw != null) out.add(new Issue(ln, true, k + " 的值 " + CheckText.q(CheckText.clip(vt, 30)) + " 里有全角符号：" + fw));
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        int n = 0;
        for (String tok : vt.split(",", -1)) {
            String t = tok.trim();
            if (t.isEmpty()) {
                out.add(new Issue(ln, true, k + " 里有空项（连续两个逗号，或末尾多了逗号）"));
                continue;
            }
            int v;
            try {
                v = Integer.parseInt(t);
            } catch (NumberFormatException e) {
                String why = t.matches("\\d+(\\s+\\d+)+") ? "多个时间点要用逗号分隔，不是空格"
                        : t.matches("\\d+\\s*[A-Za-z]+") ? "只写毫秒数，不带单位" : "不是整数";
                out.add(new Issue(ln, true, k + " 的 " + CheckText.q(CheckText.clip(t, 20)) + " " + why));
                continue;
            }
            if (v < 0 || v > 10000) {
                out.add(new Issue(ln, true, k + " 的 " + v + " 超出允许范围 0～10000（毫秒）"));
                continue;
            }
            if (!seen.add(v)) out.add(new Issue(ln, false, k + " 里 " + v + " 写了两次，只会拍一张"));
            n++;
        }
        if (n > 8) out.add(new Issue(ln, false, k + " 写了 " + n + " 个时间点，最多取最小的 8 个，多出的会被忽略（每个时间点都是一次视图树遍历）"));
    }

    static void checkPrefixItems(List<Issue> out, int ln, String value) {
        for (String raw : value.split("[,\\n\\r]+")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            if (!t.matches("^[A-Za-z_$][A-Za-z0-9_$.]*$")) {
                out.add(new Issue(ln, true, CheckText.q("dumpPrefixes") + " 的 " + CheckText.q(CheckText.clip(t, 40)) + " 不是包名 / 类名前缀（只能是字母 / 数字 / _ / $ / 点，如 " + CheckText.q("androidx.compose.ui.text.") + "；不能带 # 、* 、空格）"));
            }
        }
    }

    static void checkWordItems(List<Issue> out, int ln, String key, String value) {
        for (String raw : value.split("[,\\n\\r]+")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.matches(".*(\\.\\*|\\\\|\\^|\\$|\\[|\\]|\\||\\(|\\)|\\+|\\?).*")) {
                out.add(new Issue(ln, false, CheckText.q(key) + " 的 " + CheckText.q(CheckText.clip(t, 30)) + " 里有正则符号。这个字段是「包含这个词」的子串匹配，不是正则，这些符号会被当成字面字符，很可能什么都匹配不上"));
            }
        }
    }
}
