package com.textrec.lsp;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「已知类名」表（纯 Java 部分）：从目标 App 的快照 / 导出文本里提取出现过的类全名，
 * 方法追踪清单里的类如果从没出现过，检查器会提醒「可能是编造的」。类名查表的数据来源，只是提醒，不拦截。
 */
final class KnownNames {
    private KnownNames() {}

    private static final Pattern CLS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*){2,}");
    static final int MAX = 60000;

    static Set<String> extract(String text) {
        HashSet<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = CLS.matcher(text);
        while (m.find() && out.size() < MAX) {
            String s = m.group();
            // 去掉明显是文件名 / 域名的结尾
            if (s.endsWith(".java") || s.endsWith(".kt") || s.endsWith(".json") || s.endsWith(".png")) continue;
            out.add(s);
        }
        return out;
    }

    /** 从文件读（一行一个类名）；文件不存在返回空集合。 */
    static Set<String> load(java.io.File f) {
        HashSet<String> out = new HashSet<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null && out.size() < MAX) if (!l.trim().isEmpty()) out.add(l.trim());
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 合并后写回文件（总数封顶，超过就保留新学到的）。 */
    static Set<String> learn(java.io.File f, Set<String> fresh) {
        HashSet<String> all = new HashSet<>(fresh);
        if (all.size() < MAX) {
            for (String s : load(f)) {
                if (all.size() >= MAX) break;
                all.add(s);
            }
        }
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8")) {
            for (String s : all) w.write(s + "\n");
        } catch (Throwable ignored) {
        }
        return all;
    }
}
