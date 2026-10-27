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

/** 检查器的文字小工具：编辑距离、最像的名字、引用、全角符号、占位符。（纯 Java） */
final class CheckText {
    private CheckText() {}


    // ------------------------------------------------------------ 小工具

    static int dist(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** 在 cands 里找最像 w 的；差得太远返回 null。大小写不同算差 0（要提示大小写）。 */
    static String nearest(String w, Iterable<String> cands) {
        String best = null;
        int bd = Integer.MAX_VALUE;
        String wl = w.toLowerCase(Locale.ROOT);
        for (String c : cands) {
            int d = dist(wl, c.toLowerCase(Locale.ROOT));
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        int lim = w.length() >= 9 ? 3 : (w.length() >= 5 ? 2 : 1);
        return best != null && bd <= lim ? best : null;
    }

    static String q(String s) {
        return "`" + s + "`";
    }

    static String clip(String s, int n) {
        String t = s.replace("\n", "\\n").replace("\r", "");
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    static final String FULLWIDTH = "＝：，；｜＠＃（）【】";

    /** 第一个全角标点的说明；没有返回 null。 */
    static String fullwidthIn(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (FULLWIDTH.indexOf(ch) >= 0) {
                String half;
                switch (ch) {
                    case '＝': half = "="; break;
                    case '：': half = ":"; break;
                    case '，': half = ","; break;
                    case '；': half = ";"; break;
                    case '｜': half = "|"; break;
                    case '＠': half = "@"; break;
                    case '＃': half = "#"; break;
                    case '（': half = "("; break;
                    case '）': half = ")"; break;
                    case '【': half = "["; break;
                    default: half = "]"; break;
                }
                return "第 " + (i + 1) + " 个字符是全角的 " + q(String.valueOf(ch)) + "，要换成半角的 " + q(half);
            }
        }
        return null;
    }

    static boolean endsWithOddBackslash(String s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    static String diffHint(String w, String k) {
        if (w.length() == k.length() - 1) {
            for (int i = 0; i < k.length(); i++) {
                String t = k.substring(0, i) + k.substring(i + 1);
                if (t.equals(w)) return "少了第 " + (i + 1) + " 个字母 " + q(String.valueOf(k.charAt(i)));
            }
        } else if (w.length() == k.length() + 1) {
            for (int i = 0; i < w.length(); i++) {
                String t = w.substring(0, i) + w.substring(i + 1);
                if (t.equals(k)) return "多了第 " + (i + 1) + " 个字母 " + q(String.valueOf(w.charAt(i)));
            }
        } else if (w.length() == k.length()) {
            for (int i = 0; i < w.length(); i++) {
                if (w.charAt(i) != k.charAt(i)) return "第 " + (i + 1) + " 个字母应是 " + q(String.valueOf(k.charAt(i))) + "，写成了 " + q(String.valueOf(w.charAt(i)));
            }
        }
        return "拼写有出入";
    }

    static String placeholderIn(String v) {
        String[] pats = {"你的", "xxx", "XXX", "<", "待填", "TODO", "todo", "示例", "占位", "这里填", "填这里", "类名", "方法名"};
        for (String p : pats) {
            if (v.contains(p)) {
                if (p.equals("<") && (v.contains("<init>") || v.contains("<clinit>"))) continue;
                int i = v.indexOf(p);
                int e = Math.min(v.length(), i + 10);
                return v.substring(Math.max(0, i - 2), e).replace("\n", " ");
            }
        }
        return null;
    }
}
