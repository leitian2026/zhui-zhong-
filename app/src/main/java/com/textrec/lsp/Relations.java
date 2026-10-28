package com.textrec.lsp;

import java.util.function.Predicate;

/**
 * 配置项之间「谁要先开着，谁才起作用」的关系（纯 Java，不依赖 Android）。
 * 关系写在 tools/field_meta.txt 的 parent 列（单个 / a&amp;b 都要 / a/b 任一个），生成到 {@link ConfigSchema.F}；
 * 这里负责求值：配置检查器（{@link CheckCross}）和首页（变灰）用的是同一套，不会各判各的。
 * 关系是递归的：一个开关自己的前提不满足时，它也算「没开」，依赖它的子项同样不起作用。
 */
final class Relations {
    private Relations() {}

    /** own：这个开关自己「开着」吗（布尔 true / 整数非 0 / 文字非空）——只看它自己的值，不看它的前提。 */
    static boolean live(String key, Predicate<String> own) {
        return live(key, own, 0);
    }

    private static boolean live(String key, Predicate<String> own, int depth) {
        if (depth > 8) return false;
        if (!own.test(key)) return false;
        ConfigSchema.F f = ConfigSchema.get(key);
        if (f == null || f.terms.length == 0) return true;
        return satisfied(f, own, depth + 1);
    }

    private static boolean termOk(ConfigSchema.F f, int i, Predicate<String> own, int depth) {
        boolean on = live(f.terms[i], own, depth);
        return f.inverse[i] ? !on : on;
    }

    private static boolean satisfied(ConfigSchema.F f, Predicate<String> own, int depth) {
        if (f.terms.length == 0) return true;
        if (f.any) {
            for (int i = 0; i < f.terms.length; i++) if (termOk(f, i, own, depth)) return true;
            return false;
        }
        for (int i = 0; i < f.terms.length; i++) if (!termOk(f, i, own, depth)) return false;
        return true;
    }

    /** 一个字段的值算不算「开着」：布尔 true；整数非 0；文字非空。 */
    static boolean isOn(ConfigSchema.F f, String v) {
        String s = v == null ? "" : v.trim();
        if (f == null) return !s.isEmpty();
        if (f.type.equals("bool")) return s.equalsIgnoreCase("true");
        if (f.type.equals("string")) return !s.isEmpty();
        try {
            return Long.parseLong(s) != 0;
        } catch (NumberFormatException e) {
            try {
                return Long.parseLong(f.def) != 0;
            } catch (NumberFormatException e2) {
                return false;
            }
        }
    }

    /** 这个字段的前提不满足（它写了也不起作用）？没有前提的字段永远是 false。 */
    static boolean dead(ConfigSchema.F f, Predicate<String> own) {
        return !satisfied(f, own, 0);
    }

    /** 第一个不满足的条件项的下标；都满足返回 -1。 */
    static int firstUnmet(ConfigSchema.F f, Predicate<String> own) {
        for (int i = 0; i < f.terms.length; i++) if (!termOk(f, i, own, 0)) return i;
        return -1;
    }

    /**
     * 一个条件项为什么不满足：返回「根因」开关——沿着前提往上找，第一个自己没开的开关；
     * 它自己开着、但前提不满足时，返回让它失效的那个祖先。找不到返回 key 本身。
     */
    static String rootCause(String key, Predicate<String> own) {
        String k = key;
        for (int guard = 0; guard < 8; guard++) {
            if (!own.test(k)) return k;
            ConfigSchema.F f = ConfigSchema.get(k);
            if (f == null || f.terms.length == 0) return k;
            int i = firstUnmet(f, own);
            if (i < 0) return k;
            if (f.inverse[i]) return f.terms[i];
            k = f.terms[i];
        }
        return k;
    }
}
