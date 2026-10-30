package com.textrec.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 视图快照（viewProbe）里不依赖 Android 的部分（纯 Java，有单元测试）：配置解析、类名链匹配、一份快照的文字格式、前后两张快照的变化对比。
 * 读控件状态的部分在 {@link ViewProbe}。
 */
final class ProbeText {
    private ProbeText() {}

    static final int MAX_TIMES = 8;
    static final int MAX_MS = 10000;

    /** probeAfterMs：逗号分隔的毫秒数 → 升序去重的整数（0～10000，最多 8 个）；解析不出来的丢掉，一个都没有就用默认 0,200,1000,2500。 */
    static int[] parseTimes(String s) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        if (s != null) {
            for (String tok : s.split("[,，\\s]+")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                try {
                    int v = Integer.parseInt(t);
                    if (v >= 0 && v <= MAX_MS) set.add(v);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (set.isEmpty()) return new int[]{0, 200, 1000, 2500};
        int[] out = new int[Math.min(MAX_TIMES, set.size())];
        int i = 0;
        for (int v : set) {
            if (i >= out.length) break;
            out[i++] = v;
        }
        return out;
    }

    /** viewProbe：换行或逗号分隔的类全名（去掉空行和 // 注释行）。 */
    static List<String> splitClasses(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : s.split("[\\r\\n,]+")) {
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            seen.add(t);
        }
        out.addAll(seen);
        return out;
    }

    /** 控件的类名链（自己、父类、……）里第一个出现在 names 里的名字；都不在返回 null。 */
    static String matchChain(List<String> chain, java.util.Set<String> names) {
        for (String c : chain) if (names.contains(c)) return c;
        return null;
    }

    /** 两张快照里同一个控件的状态对比：只列变了的字段，如「alpha 1.00→0.00，imageAlpha 255→0」；没变返回空串。 */
    static String diff(Map<String, String> prev, Map<String, String> cur) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cur.entrySet()) {
            String k = e.getKey();
            String b = prev.get(k);
            if (b == null) b = "—";
            if (!b.equals(e.getValue())) sb.append(sb.length() > 0 ? "，" : "").append(k).append(' ').append(b).append('→').append(e.getValue());
        }
        for (String k : prev.keySet()) if (!cur.containsKey(k)) sb.append(sb.length() > 0 ? "，" : "").append(k).append(' ').append(prev.get(k)).append("→—");
        return sb.toString();
    }

    /** 主要字段排成一行（父级链 p1～p3 另起一行，见 {@link #parents}）。 */
    static String line(Map<String, String> st) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : st.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("p") && k.length() == 2 && Character.isDigit(k.charAt(1))) continue;
            if (sb.length() > 0) sb.append(' ');
            if (k.equals("cls") || k.equals("id")) sb.append(e.getValue());
            else sb.append(k).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** 父级链（p1～p3）一行：「↑ 父级：d1 … | d2 … | d3 …」；没有父级返回空串。 */
    static String parents(Map<String, String> st) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            String v = st.get("p" + i);
            if (v == null) break;
            sb.append(sb.length() > 0 ? " | " : "").append('d').append(i).append(' ').append(v);
        }
        return sb.length() == 0 ? "" : "↑ 父级：" + sb;
    }

    static LinkedHashMap<String, String> map() {
        return new LinkedHashMap<>();
    }
}
