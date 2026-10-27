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

/** 任务清单的检查，以及旧格式里「被当成任务的行」的判断。（纯 Java） */
final class CheckTasks {
    private CheckTasks() {}


    /** 不是「key=value」格式、因而被当成任务清单的行：看它是不是其实想写配置。 */
    static void checkTaskBoundLine(List<Issue> out, int no, String t, String lastListKey, int lastCfgLine, boolean forced) {
        if (t.startsWith("//")) return;
        if (t.startsWith("/") && !t.startsWith("//")) {
            out.add(new Issue(no, true, "注释要用两个斜杠 " + CheckText.q("//") + " 开头，这一行只有一个 " + CheckText.q("/") + "，会被当成任务名"));
            return;
        }
        if (t.contains("＝") || t.contains("：")) {
            String f = CheckText.fullwidthIn(t);
            out.add(new Issue(no, true, "这一行被当成任务清单，因为它不是「英文key=值」的格式：" + (f == null ? "" : f + "。") + "配置项的等号必须是半角 " + CheckText.q("=")));
            return;
        }
        java.util.regex.Matcher fm = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)").matcher(t);
        if (fm.find() && !t.contains("|") && !t.contains("｜")) {
            String w = fm.group(1);
            String rest = t.substring(fm.end());
            String k = CheckFields.ALL_KEYS.contains(w) ? w : (w.length() >= 4 ? CheckText.nearest(w, CheckFields.ALL_KEYS) : null);
            if (k != null) {
                String val = rest.trim();
                if (val.startsWith("-") || val.startsWith("=")) val = val.substring(1).trim();
                String guess = val.isEmpty() ? k + "=…" : k + "=" + val;
                String why = rest.isEmpty() ? "key 后面没有等号和值" : (rest.matches("^\\s+.*") ? "key 和值之间用空格代替了等号" : "key 后面不是 " + CheckText.q("=") + " 或 " + CheckText.q(":") + "，而是 " + CheckText.q(CheckText.clip(rest.trim(), 6)));
                out.add(new Issue(no, true, "这一行被当成任务清单，不会当配置读：" + why + (k.equals(w) ? "" : "，而且 " + CheckText.q(w) + " 不是配置项名（可能少 / 多 / 错了字母）")
                        + "。你是不是想写 " + CheckText.q(CheckText.clip(guess, 60)) + "？配置项必须是 " + CheckText.q("key=值") + "，key 紧挨着 " + CheckText.q("=")));
                return;
            }
        }
        if (t.contains("=") && !t.contains("|")) {
            String first = t.substring(0, t.indexOf('='));
            out.add(new Issue(no, true, "这一行含 " + CheckText.q("=") + " 但开头 " + CheckText.q(CheckText.clip(first, 20)) + " 不是合法的配置项名（只能是英文字母 / 数字 / 下划线，且不能以数字开头，前面不能有 - * • ` 引号 空格以外的符号），所以被当成了任务名"));
            return;
        }
        if (!t.contains("|") && !t.contains("｜") && lastListKey != null && no - lastCfgLine <= 3) {
            out.add(new Issue(no, true, "这一行紧跟在 " + CheckText.q(lastListKey + "=") + " 后面，但没有写 " + CheckText.q("key=") + "，所以被当成了任务名，不是 " + lastListKey + " 的第二项。"
                    + "同一个配置项的多个值要么用逗号隔开写在同一行，要么行尾加反斜杠 " + CheckText.q("\\") + " 续行，要么用 " + CheckText.q("\\n") + " 隔开"));
            return;
        }
        if (t.matches("^[A-Za-z_$][\\w$.]*[.$][\\w$.]*#[^|]*$")) {
            out.add(new Issue(no, true, "这一行看起来是方法追踪的 " + CheckText.q("类#方法") + "，但它没有写在 " + CheckText.q("traceMethods=") + " 里，会被当成任务名。写成 " + CheckText.q("traceMethods=" + CheckText.clip(t, 40))));
        }
    }

    // ------------------------------------------------------------ 任务清单

    static void checkTasks(List<Issue> out, List<String> raws, List<Integer> nos) {
        Set<String> names = new HashSet<>();
        boolean first = true;
        for (int ii = 0; ii < raws.size(); ii++) {
            String raw = raws.get(ii);
            String t = raw.trim();
            int no = nos.get(ii);
            if (t.startsWith("```") || (t.startsWith("[") && t.endsWith("]") && t.length() <= 12 && !t.contains("|"))) continue; // 上面已经报过
            if (t.contains("｜")) {
                out.add(new Issue(no, true, "任务清单的分隔符要用半角竖线 " + CheckText.q("|") + "，这一行用了全角的 " + CheckText.q("｜") + "，整行会被当成一个任务名（后面的提示 / 秒数都读不到）"));
                continue;
            }
            String[] p = t.split("\\|", -1);
            String name = p[0].trim();
            boolean noWait = name.startsWith("!");
            if (noWait) {
                name = name.substring(1).trim();
                if (!first) out.add(new Issue(no, false, "名称前的 " + CheckText.q("!") + " 只对清单第一件事有意义（冷启动时一打开浮窗就开始），这一行不是第一件，" + CheckText.q("!") + " 不起作用"));
            }
            boolean isFirstTask = first;
            first = false;
            boolean base = name.startsWith("@基线");
            if (base && !isFirstTask) out.add(new Issue(no, false, "基线 " + CheckText.q("@基线") + " 最好放在清单第一件：它记的是「什么都不做」时的背景噪声，放在后面，前面的任务就没法拿它当对照"));
            if (name.startsWith("@") && !base) {
                String k = name.startsWith("@baseline") || name.toLowerCase(Locale.ROOT).startsWith("@base") ? "；基线要写成中文 " + CheckText.q("@基线") : (CheckText.dist(name.length() > 3 ? name.substring(0, 3) : name, "@基线") <= 1 ? "；是不是想写 " + CheckText.q("@基线") + "？" : "");
                out.add(new Issue(no, true, "任务名以 " + CheckText.q("@") + " 开头只能是 " + CheckText.q("@基线") + "（安静基线），你写的是 " + CheckText.q(CheckText.clip(name, 12)) + "，会被当成一个普通任务名" + k));
            }
            if (name.isEmpty()) {
                out.add(new Issue(no, true, "任务名是空的（第一段 " + CheckText.q("|") + " 前面没有内容）：" + CheckText.q(CheckText.clip(t, 30))));
                continue;
            }
            if (name.contains("=")) out.add(new Issue(no, false, "任务名里有 " + CheckText.q("=") + "（" + CheckText.q(CheckText.clip(name, 20)) + "）；如果它其实是配置项，请检查是不是 key 写错了"));
            if (!base && !names.add(name)) out.add(new Issue(no, false, "任务名 " + CheckText.q(CheckText.clip(name, 20)) + " 和前面的重复了，导出里两件事会难分清"));
            if (p.length > 4) out.add(new Issue(no, false, "任务清单每行最多 4 段（名称|提示|秒数|空），这一行有 " + p.length + " 段，第 5 段起会被忽略；提示里如果有 " + CheckText.q("|") + " 请换成别的符号"));
            if (p.length > 2) {
                String s = p[2].trim();
                if (!s.isEmpty()) {
                    try {
                        int secs = Integer.parseInt(s);
                        if (secs < 0) out.add(new Issue(no, true, "第 3 段（秒数） " + CheckText.q(s) + " 是负数"));
                        else if (secs > 600) out.add(new Issue(no, false, "第 3 段（秒数）是 " + secs + " 秒，超过 10 分钟，是不是多写了一个 0？"));
                        else if (base && secs == 0) out.add(new Issue(no, false, "基线秒数是 0，会改用默认 10 秒"));
                    } catch (NumberFormatException ex) {
                        String why = s.matches("\\d+\\s*[sS秒]") ? "不要带单位，只写数字" : (s.matches("-?\\d+\\.\\d+") ? "是小数，只认整数" : "不是整数");
                        out.add(new Issue(no, true, "第 3 段（秒数）" + CheckText.q(CheckText.clip(s, 12)) + " " + why + "，会被当成 0（不自动结束）"));
                    }
                }
            }
            if (p.length > 3) {
                String f = p[3].trim();
                int repsSeen = 0;
                for (String tk : f.split("[\\s,，]+")) {
                    if (tk.isEmpty()) continue;
                    if (tk.equals("空") || tk.equals("0") || tk.equals("可空") || tk.equals("对照")) continue;
                    if (tk.matches("^[xX×*]\\d+$")) {
                        repsSeen++;
                        int n = Integer.parseInt(tk.substring(1));
                        if (n < 1) out.add(new Issue(no, true, "第 4 段 " + CheckText.q(tk) + " 的遍数必须 ≥ 1"));
                        else if (n > 10) out.add(new Issue(no, false, "第 4 段 " + CheckText.q(tk) + " 超过上限 10 遍，会被改成 10"));
                        if (base) out.add(new Issue(no, false, "基线任务不能做多遍，" + CheckText.q(tk) + " 会被忽略"));
                        continue;
                    }
                    String k = tk.equalsIgnoreCase("empty") || tk.equalsIgnoreCase("null") || tk.equalsIgnoreCase("none") ? "；对照任务要写中文 " + CheckText.q("空") : "";
                    if (tk.matches("^[xX×*]\\D.*") || tk.matches("^\\d+[xX×*]$")) k = "；做多遍的写法是 " + CheckText.q("x3") + "（x 在前，后面接遍数）";
                    out.add(new Issue(no, true, "第 4 段只能写 " + CheckText.q("空") + " / " + CheckText.q("可空") + " / " + CheckText.q("对照") + " / " + CheckText.q("0") + "（对照任务，允许 0 条记录）或 "
                            + CheckText.q("x3") + "（同一件事连做 3 遍），你写的是 " + CheckText.q(CheckText.clip(tk, 12)) + k));
                }
                if (repsSeen > 1) out.add(new Issue(no, false, "第 4 段写了不止一个 " + CheckText.q("xN") + "，只有最后一个生效"));
            }
            if (p.length == 1 && t.length() > 25) {
                out.add(new Issue(no, false, "这一行没有任何 " + CheckText.q("|") + "，整行 " + CheckText.q(CheckText.clip(t, 24)) + " 都会被当成任务名（提示 / 秒数都没有）。如果想写提示，格式是 " + CheckText.q("名称|提示|秒数")));
            }
        }
    }
}
