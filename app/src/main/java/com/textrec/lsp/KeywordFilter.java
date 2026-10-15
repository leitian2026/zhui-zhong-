package com.textrec.lsp;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;

/** 关键字过滤：全量模式直接放行；关键字模式支持普通子串和正则（预编译，非法正则退回子串并给出提示）。 */
final class KeywordFilter {
    final boolean keywordMode;
    final String warn;
    private final Pattern[] pats;
    private final String[] subs;
    /** 直接映射的小缓存：同一段文字反复绘制时不重复跑匹配。高 32 位存文字哈希，低 8 位存结果。 */
    private final AtomicLongArray cache = new AtomicLongArray(512);

    KeywordFilter(RecConfig c) {
        keywordMode = c.keywordMode;
        ArrayList<Pattern> ps = new ArrayList<>();
        ArrayList<String> ss = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        if (keywordMode) {
            for (String k : RecConfig.split(c.keywords, c.regex)) {
                if (c.regex) {
                    try {
                        ps.add(Pattern.compile(k));
                    } catch (Throwable t) {
                        ss.add(k);
                        w.append("正则无效，已按普通文字匹配：").append(k).append('\n');
                    }
                } else {
                    ss.add(k);
                }
            }
            if (ps.isEmpty() && ss.isEmpty()) w.append("关键字为空：关键字模式下不会记录任何文字\n");
        }
        pats = ps.toArray(new Pattern[0]);
        subs = ss.toArray(new String[0]);
        warn = w.toString();
    }

    boolean accept(String text) {
        if (!keywordMode) return true;
        int h = text.hashCode();
        int idx = (h ^ (h >>> 9)) & 511;
        long e = cache.get(idx);
        if (e != 0 && (int) (e >>> 8) == h) return (e & 1) != 0;
        boolean r = match(text);
        cache.set(idx, ((long) h << 8) | (r ? 3 : 2));
        return r;
    }

    private boolean match(String text) {
        for (Pattern p : pats) if (p.matcher(text).find()) return true;
        for (String s : subs) if (text.contains(s)) return true;
        return false;
    }
}
