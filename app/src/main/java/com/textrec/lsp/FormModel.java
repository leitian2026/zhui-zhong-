package com.textrec.lsp;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 首页表单和配置之间的转换（纯 Java，不依赖 Android，有单元测试）。
 * 首页只负责把控件里的字符串收集成 Map、把 Map 写回控件；「哪个控件对应哪个字段、怎么钳制、没有控件的字段怎么保留、
 * 哪些项算「已改」、哪些子项要变灰」都在这里，保证读出来再写回去是同一份配置。
 */
final class FormModel {
    private FormModel() {}

    /**
     * 从控件的值读出一份配置。
     * base：没有控件的字段（不在 values 里）保持它的值，不会被悄悄重置成默认值。
     * values：key → 控件里的文字（开关写 "true"/"false"，数字和文字照原样）。
     */
    static RecConfig read(RecConfig base, Map<String, String> values, boolean keywordMode, int threadMode, long epoch) {
        RecConfig c = RecConfig.fromText(base.toText());
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            String v = values.get(f.key);
            if (v == null) continue;
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                if (f.type.equals("bool")) {
                    fl.setBoolean(c, v.trim().equalsIgnoreCase("true"));
                } else if (f.type.equals("int")) {
                    int n = parseInt(v, fl.getInt(c));
                    if (f.hasRange) n = RecConfig.clamp(n, (int) f.min, (int) f.max);
                    fl.setInt(c, n);
                } else if (f.type.equals("string")) {
                    fl.set(c, v);
                }
            } catch (Throwable ignored) {
            }
        }
        if (c.viewDumpKey.trim().isEmpty()) c.viewDumpKey = new RecConfig().viewDumpKey;
        c.keywordMode = keywordMode;
        c.threadMode = Math.max(0, Math.min(2, threadMode));
        c.epoch = epoch;
        return c;
    }

    /** 把配置写成控件的值（只含 widgetKeys 里有控件的字段）。 */
    static Map<String, String> toValues(RecConfig c, Set<String> widgetKeys) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (!widgetKeys.contains(f.key)) continue;
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                out.put(f.key, String.valueOf(fl.get(c)));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int parseInt(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return d;
        }
    }

    /** 字段属于首页哪个分组：content / range / trace / filter / view / limits。 */
    static String uiGroup(ConfigSchema.F f) {
        switch (f.group) {
            case "content":
            case "compose":
                return "content";
            case "range":
                return "range";
            case "chain":
                return "trace";
            case "view":
            case "timeline":
                return "view";
            case "limits":
                return "limits";
            case "filter":
                return f.key.equals("windowMs") || f.key.equals("windowAll") || f.key.equals("threadMode") || f.key.equals("threadFilter")
                        || f.key.equals("pageFilter") || f.key.equals("viewFilter") ? "filter" : "trace";
            default:
                return f.group;
        }
    }

    /** 每个分组里和默认值不同的字段数。 */
    static Map<String, Integer> changedCounts(RecConfig c) {
        RecConfig d = new RecConfig();
        HashMap<String, Integer> n = new HashMap<>();
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.group.equals("internal") || f.group.equals("target")) continue;
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                if (!String.valueOf(fl.get(c)).equals(String.valueOf(fl.get(d)))) {
                    String g = uiGroup(f);
                    Integer old = n.get(g);
                    n.put(g, old == null ? 1 : old + 1);
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 子项是否该变灰：它的前提（含祖先的前提）不满足。own 判断某个开关自己开着没有。 */
    static boolean dead(ConfigSchema.F f, java.util.function.Predicate<String> own) {
        return Relations.dead(f, own);
    }
}
