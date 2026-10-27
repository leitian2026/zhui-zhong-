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

/** 方法追踪清单和观察点（watch）的检查：类#方法、@选项、示例类名、类名查表。（纯 Java） */
final class CheckTrace {
    private CheckTrace() {}


    /** 只能写在方法追踪清单里的选项。 */
    static final String[] TRACE_OPTS = {"count", "args", "stack", "depth", "every", "tag", "seq"};
    /** 文档里的示例类名（AI 容易原样抄进配置）：一定不是真实类，判错。 */
    static final String[] EXAMPLE_CLASSES = {"com.a.B", "com.a.b", "com.demo", "com.xxx", "你的"};
    /** 常见的示例包名：真实 App 偶尔也用，只警告。 */
    static final String[] WEAK_EXAMPLE_CLASSES = {"com.example", "com.test.", "org.example", "com.foo"};

    // ------------------------------------------------------------ 方法追踪清单

    static final Pattern CLASS_OK = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$");
    static final Pattern METHOD_OK = Pattern.compile("^[A-Za-z_$<][A-Za-z0-9_$<>\\-]*$");

    static int optStart(String t) {
        for (int i = 1; i < t.length(); i++) if (t.charAt(i) == '@' && Character.isWhitespace(t.charAt(i - 1))) return i - 1;
        return -1;
    }

    /** 校验方法追踪清单的每一条。line = 用来报告的行号（粘贴框里是配置项所在行，界面输入框里是 0）。 */
    static void checkTraceMethods(List<Issue> out, String value, int line, String what, boolean uiBox) {
        checkTraceMethods(out, value, line, what, uiBox, null);
    }

    /** itemLines：块格式里每一项所在的真实行号（和 value 按行一一对应），null 时所有项都报 line。 */
    static void checkTraceMethods(List<Issue> out, String value, int line, String what, boolean uiBox, int[] itemLines) {
        if (value == null) return;
        String[] items = value.split("\\r?\\n|\\r");
        HashMap<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < items.length; i++) {
            String t = items[i].trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int ln = itemLines != null && i < itemLines.length ? itemLines[i] : line;
            String pre = (uiBox ? "方法追踪第 " + (i + 1) + " 行" : what + " 第 " + (i + 1) + " 条") + " " + CheckText.q(CheckText.clip(t, 50)) + "：";
            ArrayList<String> errs = new ArrayList<>(), warns = new ArrayList<>();
            checkTraceLine(t, errs, warns);
            for (String m : errs) out.add(new Issue(ln, true, pre + m));
            for (String m : warns) out.add(new Issue(ln, false, pre + m));
            int at = optStart(t);
            String target = (at < 0 ? t : t.substring(0, at)).trim();
            int pp = target.indexOf('(');
            if (pp > 0) target = target.substring(0, pp).trim();
            Integer prev = seen.get(target);
            if (prev != null) out.add(new Issue(ln, false, pre + "和第 " + prev + " 条是同一个钩点 " + CheckText.q(target) + "，重复了（后面的选项覆盖前面的）"));
            else seen.put(target, i + 1);
        }
    }

    static boolean isExampleClass(String cls) {
        for (String p : EXAMPLE_CLASSES) {
            if (p.endsWith(".") ? cls.startsWith(p) : (cls.equals(p) || cls.startsWith(p + ".") || (!p.contains(".") && cls.startsWith(p)))) return true;
        }
        return false;
    }

    static boolean isWeakExampleClass(String cls) {
        for (String p : WEAK_EXAMPLE_CLASSES) {
            if (p.endsWith(".") ? cls.startsWith(p) : (cls.equals(p) || cls.startsWith(p + "."))) return true;
        }
        return false;
    }

    static boolean isSystemClass(String cls) {
        return cls.startsWith("android.") || cls.startsWith("java.") || cls.startsWith("javax.") || cls.startsWith("androidx.")
                || cls.startsWith("kotlin.") || cls.startsWith("kotlinx.") || cls.startsWith("dalvik.") || cls.startsWith("com.google.android.");
    }

    static void checkTraceLine(String t, List<String> errs, List<String> warns) {
        if (t.startsWith("/") && !t.startsWith("//")) {
            errs.add("注释要用两个斜杠 " + CheckText.q("//") + "，这里只有一个 " + CheckText.q("/") + "，会被当成要追踪的类");
            return;
        }
        String fw = CheckText.fullwidthIn(t);
        if (fw != null) errs.add(fw);
        if (t.startsWith("`") || t.endsWith("`") || t.startsWith("\"") || t.startsWith("'") || t.startsWith("- ") || t.startsWith("* ")) {
            errs.add("行首 / 行尾有多余的符号 " + CheckText.q(t.substring(0, 1)) + "（引号、反引号、列表符号都不能带）");
        }
        int at = optStart(t);
        String target = (at < 0 ? t : t.substring(0, at)).trim();
        String opts = at < 0 ? "" : t.substring(at).trim();
        if (at < 0) {
            int a = t.indexOf('@');
            if (a > 0) {
                errs.add("第 " + (a + 1) + " 个字符的 " + CheckText.q("@") + " 前面没有空格：它会变成方法名的一部分，选项不生效。应写成 " + CheckText.q(t.substring(0, a) + " " + t.substring(a)));
                target = t.substring(0, a).trim();
            }
        }
        int p = target.indexOf('(');
        if (p >= 0) {
            if (target.indexOf(')', p) < 0) errs.add("括号没有闭合：" + CheckText.q("(") + " 后面缺 " + CheckText.q(")") + "（括号里的参数类型会被忽略，所有同名重载都会追，可以直接不写括号）");
            target = target.substring(0, p).trim();
        } else if (target.contains(")")) {
            errs.add("有 " + CheckText.q(")") + " 却没有 " + CheckText.q("("));
        }
        int sp = -1;
        for (int k = 0; k < target.length(); k++) if (Character.isWhitespace(target.charAt(k))) { sp = k; break; }
        if (sp > 0 && target.charAt(sp - 1) != '#') {
            int nx = sp;
            while (nx < target.length() && Character.isWhitespace(target.charAt(nx))) nx++;
            if (nx < target.length() && target.charAt(nx) != '#') {
                errs.add("「类#方法」后面有多余的文字 " + CheckText.q(CheckText.clip(target.substring(sp).trim(), 30)) + "：注释必须单独占一行（以 " + CheckText.q("//") + " 开头），行尾选项必须以 " + CheckText.q("@") + " 开头，一行只能写一个钩点");
                target = target.substring(0, sp).trim();
            }
        }
        if (target.indexOf(',') >= 0) {
            errs.add("一行只能写一个钩点，" + CheckText.q(CheckText.clip(target, 40)) + " 里有逗号；多个钩点要分行写（块格式写在 " + CheckText.q("[traceMethods]") + " 段里，一行一个）");
            return;
        }
        int h = target.indexOf('#');
        if (h < 0) {
            String hint = "";
            int ld = target.lastIndexOf('.');
            if (ld > 0 && !target.contains(" ")) hint = "；如果最后一段 " + CheckText.q(target.substring(ld + 1)) + " 是方法名，应写成 " + CheckText.q(target.substring(0, ld) + "#" + target.substring(ld + 1));
            errs.add("缺少 " + CheckText.q("#") + "：写法是 " + CheckText.q("类全名#方法名") + "（如 android.view.View#setAlpha），追踪整个类写 " + CheckText.q("类全名#*") + hint);
            return;
        }
        if (target.indexOf('#', h + 1) >= 0) {
            errs.add("出现了不止一个 " + CheckText.q("#") + "，一行只能写一个「类#方法」");
            return;
        }
        String cls = target.substring(0, h), m = target.substring(h + 1);
        if (!cls.equals(cls.trim()) || !m.equals(m.trim())) errs.add(CheckText.q("#") + " 两边不能有空格（现在是 " + CheckText.q(CheckText.clip(target, 40)) + "）");
        cls = cls.trim();
        m = m.trim();
        if (cls.isEmpty()) {
            errs.add(CheckText.q("#") + " 前面没有类名");
        } else {
            String bad = badClass(cls);
            if (bad != null) errs.add(bad);
            else if (!ConfigCheck.opt().allowExampleClasses && isExampleClass(cls)) errs.add("类名 " + CheckText.q(cls) + " 是文档 / 示例里的占位类名，目标 App 里不存在。类名只能从导出的记录或钩点签名里逐字复制，不能自己编");
            else if (!ConfigCheck.opt().allowExampleClasses && isWeakExampleClass(cls))
                warns.add("类 " + CheckText.q(cls) + " 的包名像是示例包名（com.example 之类）。如果目标 App 真的是这个包，可以忽略；否则类名要从导出的记录里逐字复制，不能自己编");
            else if (cls.indexOf('.') < 0) warns.add("类名 " + CheckText.q(cls) + " 没有包名；除非目标类真的在默认包，否则要写全名（包名.类名）");
            else {
                Set<String> kn = ConfigCheck.opt().knownClasses;
                if (kn != null && !kn.isEmpty() && !kn.contains(cls) && !isSystemClass(cls))
                    warns.add("类 " + CheckText.q(cls) + " 没有在已有记录 / 钩点签名里出现过。类名要逐字从导出里复制，不能凭印象写（混淆名每个版本都不一样）；如果是刚找到的新类，可以忽略这条，应用后看首页「实际生效」里它有没有装上");
            }
        }
        if (m.isEmpty()) {
            errs.add(CheckText.q("#") + " 后面没有方法名（追踪整个类写 " + CheckText.q("*") + "，构造方法写 " + CheckText.q("<init>") + "）");
        } else if (!m.equals("*") && !METHOD_OK.matcher(m).matches()) {
            int bi = 0;
            for (; bi < m.length(); bi++) if (!METHOD_OK.matcher(m.substring(bi, bi + 1)).matches() && "<>-".indexOf(m.charAt(bi)) < 0 && !Character.isLetterOrDigit(m.charAt(bi)) && m.charAt(bi) != '_' && m.charAt(bi) != '$') break;
            errs.add("方法名 " + CheckText.q(m) + " 第 " + (Math.min(bi, m.length() - 1) + 1) + " 个字符 " + CheckText.q(String.valueOf(m.charAt(Math.min(bi, m.length() - 1)))) + " 不合法（方法名只能有字母 / 数字 / _ / $，构造方法是 " + CheckText.q("<init>") + "，Compose 的方法名可带 " + CheckText.q("-") + "）");
        } else if (m.equalsIgnoreCase("init") && !m.equals("init")) {
            warns.add("构造方法要写 " + CheckText.q("<init>") + "（带尖括号）");
        } else if (m.equals("constructor") || m.equals("new")) {
            warns.add("构造方法要写 " + CheckText.q("<init>") + "，" + CheckText.q(m) + " 只会去找名字叫 " + CheckText.q(m) + " 的方法");
        }
        checkOpts(opts, errs, warns);
    }

    static String badClass(String cls) {
        if (CLASS_OK.matcher(cls).matches()) {
            if (cls.endsWith(".class") || cls.endsWith(".java") || cls.endsWith(".kt")) return "类名不要带后缀 " + CheckText.q(cls.substring(cls.lastIndexOf('.'))) + "（现在它会被当成包名的一层）";
            return null;
        }
        if (cls.contains("/")) return "类名里用了 " + CheckText.q("/") + "，要用点 " + CheckText.q(".") + " 分隔：" + CheckText.q(cls.replace('/', '.'));
        if (cls.startsWith("L") && cls.endsWith(";")) return "这是 JVM 签名写法（L…;），要写成普通类全名：" + CheckText.q(cls.substring(1, cls.length() - 1).replace('/', '.'));
        if (cls.contains(" ")) return "类名里有空格（第 " + (cls.indexOf(' ') + 1) + " 个字符），类名中间不能断开";
        if (cls.startsWith(".") || cls.endsWith(".") || cls.contains("..")) return "类名里的点 " + CheckText.q(".") + " 位置不对（开头 / 结尾 / 连续两个点）：" + CheckText.q(cls);
        for (int i = 0; i < cls.length(); i++) {
            char c = cls.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.')) return "类名第 " + (i + 1) + " 个字符 " + CheckText.q(String.valueOf(c)) + " 不合法";
        }
        if (Character.isDigit(cls.charAt(0))) return "类名不能以数字开头";
        return "类名写法不合法：" + CheckText.q(cls);
    }

    static void checkOpts(String opts, List<String> errs, List<String> warns) {
        if (opts.isEmpty()) return;
        boolean count = false, args = false, seq = false;
        for (String w : opts.split("[@,\\s]+")) {
            if (w.isEmpty()) continue;
            String name = w.contains("=") ? w.substring(0, w.indexOf('=')) : w;
            String val = w.contains("=") ? w.substring(w.indexOf('=') + 1) : null;
            boolean known = false;
            for (String o : TRACE_OPTS) if (o.equals(name)) known = true;
            if (!known) {
                String k = CheckText.nearest(name, java.util.Arrays.asList(TRACE_OPTS));
                errs.add("未知选项 " + CheckText.q("@" + name) + "，会被忽略" + (k != null ? "。你是不是想写 " + CheckText.q("@" + k + (k.equals("count") || k.equals("args") || k.equals("seq") ? "" : "=…")) + "？" : "。可用选项：@count @args @stack=N @every=N @tag=N / @tag=ret @seq / @seq=N"));
                continue;
            }
            if (name.equals("count")) {
                count = true;
                if (val != null) errs.add(CheckText.q("@count") + " 不带值，不能写 " + CheckText.q("=" + val));
            } else if (name.equals("args")) {
                args = true;
                if (val != null) errs.add(CheckText.q("@args") + " 不带值，不能写 " + CheckText.q("=" + val));
            } else if (name.equals("seq")) {
                seq = true;
                if (val != null) {
                    if (!val.matches("\\d+")) errs.add(CheckText.q("@seq=" + val) + " 的值 " + CheckText.q(val) + " 不是正整数，会被忽略（按默认 200 条）");
                    else if (Long.parseLong(val) < 1) errs.add(CheckText.q("@seq=" + val) + " 必须 ≥ 1，会被忽略（按默认 200 条）");
                    else if (Long.parseLong(val) > 1000) warns.add(CheckText.q("@seq=" + val) + " 超出上限 1000，会被改成 1000");
                }
            } else if (name.equals("tag")) {
                if (val == null) errs.add(CheckText.q("@tag") + " 后面要写 " + CheckText.q("=数字") + "（第几个参数，从 0 数）或 " + CheckText.q("=ret") + "（返回值）");
                else if (!val.equals("ret") && !val.matches("\\d+")) errs.add(CheckText.q("@tag=" + val) + " 的值只能是非负整数或 " + CheckText.q("ret") + (CheckText.dist(val, "ret") <= 1 ? "；是不是想写 " + CheckText.q("@tag=ret") + "？" : ""));
            } else {
                if (val == null) {
                    errs.add(CheckText.q("@" + name) + " 后面要写 " + CheckText.q("=数字") + "，如 " + CheckText.q("@" + name + "=4"));
                } else if (!val.matches("\\d+")) {
                    errs.add(CheckText.q("@" + name + "=" + val) + " 的值 " + CheckText.q(val) + " 不是正整数，会被忽略");
                } else {
                    long n = Long.parseLong(val);
                    long hi = name.equals("every") ? 100000 : 60;
                    if (n < 1) errs.add(CheckText.q("@" + name + "=" + val) + " 必须 ≥ 1，会被忽略");
                    else if (n > hi) warns.add(CheckText.q("@" + name + "=" + val) + " 超出上限 " + hi + "，会被改成 " + hi);
                }
            }
        }
        if (seq && count) warns.add("同时写了 " + CheckText.q("@seq") + " 和 " + CheckText.q("@count") + "：序列事件照记，但 " + CheckText.q("@count") + " 的记录里不会有调用栈");
        if (count && args) warns.add("同时写了 " + CheckText.q("@count") + " 和 " + CheckText.q("@args") + "，只有 " + CheckText.q("@count") + " 生效（只计数，不记参数）");
    }

    // ------------------------------------------------------------ 观察点（watch）

    static final Pattern WATCH_FIELD = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    /** 校验观察点路径：根 this / ret / argN，后面是 .字段 / [下标] / {键} / (LiveData) (ref) (lazy)。返回错误原因，没问题返回 null。 */
    static String badPath(String p) {
        if (p.isEmpty()) return "路径是空的";
        if (p.indexOf(' ') >= 0 || p.indexOf('\t') >= 0) return "路径里不能有空格（第 " + (p.indexOf(' ') + 1) + " 个字符）";
        String fw = CheckText.fullwidthIn(p);
        if (fw != null) return fw;
        int cut = p.length();
        for (char ch : new char[]{'.', '[', '{', '('}) {
            int i = p.indexOf(ch);
            if (i >= 0) cut = Math.min(cut, i);
        }
        String root = p.substring(0, cut);
        if (!(root.equals("this") || root.equals("ret") || root.matches("arg\\d+"))) {
            String hint = "";
            if (root.startsWith("args") || root.startsWith("param")) hint = "；参数写成 arg0、arg1（从 0 数），不是 " + CheckText.q(root);
            else if (root.equals("result") || root.equals("return")) hint = "；返回值写成 " + CheckText.q("ret");
            else if (root.equals("self") || root.equals("thisObject")) hint = "；调用对象写成 " + CheckText.q("this");
            else {
                String k = CheckText.nearest(root, java.util.Arrays.asList("this", "ret", "arg0", "arg1", "arg2"));
                if (k != null) hint = "；是不是想写 " + CheckText.q(k) + "？";
            }
            return "路径必须以 this、ret 或 argN 开头，你写的是 " + CheckText.q(root) + hint;
        }
        int i = cut;
        while (i < p.length()) {
            char ch = p.charAt(i);
            int j;
            if (ch == '.') {
                j = i + 1;
                while (j < p.length() && ".[{(".indexOf(p.charAt(j)) < 0) j++;
                String name = p.substring(i + 1, j);
                if (name.isEmpty()) return "第 " + (i + 1) + " 个字符的 " + CheckText.q(".") + " 后面没有字段名（多写了点，或点在结尾）";
                if (!WATCH_FIELD.matcher(name).matches()) return "字段名 " + CheckText.q(name) + " 不合法（只能是字母 / 数字 / _ / $，不能以数字开头）";
            } else if (ch == '[') {
                j = p.indexOf(']', i);
                if (j < 0) return "第 " + (i + 1) + " 个字符的 " + CheckText.q("[") + " 没有闭合的 " + CheckText.q("]");
                if (!p.substring(i + 1, j).matches("\\d+")) return "方括号里要是整数下标，你写的是 " + CheckText.q("[" + p.substring(i + 1, j) + "]");
                j++;
            } else if (ch == '{') {
                j = p.indexOf('}', i);
                if (j < 0) return "第 " + (i + 1) + " 个字符的 " + CheckText.q("{") + " 没有闭合的 " + CheckText.q("}");
                j++;
            } else if (ch == '(') {
                j = p.indexOf(')', i);
                if (j < 0) return "第 " + (i + 1) + " 个字符的 " + CheckText.q("(") + " 没有闭合的 " + CheckText.q(")");
                String tok = p.substring(i + 1, j);
                if (!(tok.equals("LiveData") || tok.equals("ref") || tok.equals("lazy"))) {
                    String k = CheckText.nearest(tok, java.util.Arrays.asList("LiveData", "ref", "lazy"));
                    return "括号里只能是 LiveData / ref / lazy，你写的是 " + CheckText.q("(" + tok + ")") + (k != null ? "；是不是想写 " + CheckText.q("(" + k + ")") + "？" : "");
                }
                j++;
            } else {
                return "第 " + (i + 1) + " 个字符 " + CheckText.q(String.valueOf(ch)) + " 不合法：路径里字段之间用点 " + CheckText.q(".") + "，列表元素用 " + CheckText.q("[0]") + "，Map 元素用 " + CheckText.q("{键}");
            }
            i = j;
        }
        return null;
    }

    /** 校验 watch=：每行「类#方法:路径」。uiBox = 界面输入框里的（按行号报），否则是粘贴框里的（按条报）。 */
    static void checkWatch(List<Issue> out, String value, int line, boolean uiBox) {
        checkWatch(out, value, line, uiBox, null);
    }

    static void checkWatch(List<Issue> out, String value, int line, boolean uiBox, int[] itemLines) {
        if (value == null) return;
        String[] items = value.split("\\r?\\n|\\r");
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < items.length; i++) {
            String t = items[i].trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int ln = itemLines != null && i < itemLines.length ? itemLines[i] : line;
            String pre = (uiBox ? "观察点第 " + (i + 1) + " 行 " : "watch 第 " + (i + 1) + " 条 ") + CheckText.q(CheckText.clip(t, 50)) + "：";
            int c = t.indexOf(':');
            if (c < 0) {
                out.add(new Issue(ln, true, pre + "缺少冒号：写法是 " + CheckText.q("类#方法:路径") + "，如 " + CheckText.q("com.a.B#foo:arg0.mAweme.userDigg")));
                continue;
            }
            String tk = t.substring(0, c), path = t.substring(c + 1);
            if (!tk.equals(tk.trim()) || !path.equals(path.trim())) out.add(new Issue(ln, true, pre + "冒号两边不能有空格"));
            tk = tk.trim();
            path = path.trim();
            ArrayList<String> errs = new ArrayList<>(), warns = new ArrayList<>();
            int h = tk.indexOf('#');
            if (h < 0) {
                errs.add("冒号前面要是 " + CheckText.q("类#方法") + "（缺少 " + CheckText.q("#") + "），而且这个方法必须同时写在方法追踪里，不然读不到根对象");
            } else {
                String cls = tk.substring(0, h), m = tk.substring(h + 1);
                String bad = cls.isEmpty() ? CheckText.q("#") + " 前面没有类名" : badClass(cls);
                if (bad != null) errs.add(bad);
                if (m.isEmpty() || m.equals("*") || !METHOD_OK.matcher(m).matches()) errs.add("方法名 " + CheckText.q(m) + " 不合法（观察点要指定到具体的方法，不能用 " + CheckText.q("*") + "）");
            }
            String bp = badPath(path);
            if (bp != null) errs.add(bp);
            for (String m : errs) out.add(new Issue(ln, true, pre + m));
            for (String m : warns) out.add(new Issue(ln, false, pre + m));
            if (!seen.add(t)) out.add(new Issue(ln, false, pre + "重复了"));
        }
    }

    /** 观察点引用的方法必须在方法追踪清单里（或整类追踪），否则永远读不到根对象。 */
    static void watchVsTrace(List<Issue> out, String w, String tm, int line) {
        if (w == null || w.trim().isEmpty()) return;
        HashSet<String> traced = new HashSet<>();
        if (tm != null) {
            for (String s : tm.split("\\r?\\n|\\r")) {
                String t = s.trim();
                if (t.isEmpty() || t.startsWith("//")) continue;
                int at = optStart(t);
                String target = (at < 0 ? t : t.substring(0, at)).trim();
                int p = target.indexOf('(');
                if (p > 0) target = target.substring(0, p).trim();
                traced.add(target);
            }
        }
        for (String s : w.split("\\r?\\n|\\r")) {
            String t = s.trim();
            int c = t.indexOf(':');
            if (t.isEmpty() || t.startsWith("//") || c <= 0) continue;
            String tk = t.substring(0, c).trim();
            int h = tk.indexOf('#');
            if (h <= 0) continue;
            if (!traced.contains(tk) && !traced.contains(tk.substring(0, h) + "#*")) {
                out.add(new Issue(line, true, "watch 里的 " + CheckText.q(tk) + " 没有写在 traceMethods 里：没被追踪的方法拿不到根对象（this / 参数 / 返回值），这个观察点永远读不到值。把 " + CheckText.q(tk) + " 也加进 traceMethods"));
            }
        }
    }
}
