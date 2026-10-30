package com.textrec.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 按任务统计「这件事记到的东西干不干净」（纯 Java，不依赖 Android，有单元测试）。导出开头的「洁净度概况」和浮窗每件做完后的提示共用这一套。
 *
 * 每条记录是一个「位置」，带：每个任务阶段各命中几次（key 0 = 任务之外）、基线标签（noise = 基线安静期间也在调用 / live = 基线期间没出现 / 空 = 没录基线）、
 * 同一件事做多遍时每遍有没有出现（按位）。对任务 n：
 *   命中   = 这件事期间命中过的记录；
 *   噪声   = 命中且基线标签是 noise（周期性刷新，不是这次操作引起的）；
 *   独有   = 命中、不是噪声、而且别的任务里没命中过（最像「这次操作引起的」）；
 *   共有   = 命中、不是噪声、但别的任务里也命中过；
 *   没录基线 = 命中但没有基线标签（没录基线，噪声分不出来）。
 */
final class TaskStats {
    private TaskStats() {}

    /** 一条记录的统计用信息（由 TaskStatsJson 从快照 JSON 转出来）。 */
    static final class RecInfo {
        final Map<Integer, Integer> seg;     // 任务阶段 → 命中次数，0 = 任务之外
        final String baseline;               // "noise" / "live" / ""
        final Map<Integer, Integer> passes;  // 任务 → 遍数按位（bit0 = 第 1 遍）；只有做多遍的任务才有

        RecInfo(Map<Integer, Integer> seg, String baseline, Map<Integer, Integer> passes) {
            this.seg = seg == null ? new TreeMap<Integer, Integer>() : seg;
            this.baseline = baseline == null ? "" : baseline;
            this.passes = passes == null ? new TreeMap<Integer, Integer>() : passes;
        }
    }

    static final class Line {
        int task;
        String name = "";
        boolean baselineTask;
        int hit, noise, live, unknown, unique, shared;
        int passes;       // 这件事一共做了几遍（>= 2 才有意义），0 = 没做多遍
        int consistent;   // 每一遍都出现的记录数
    }

    static final class Summary {
        final List<Line> lines = new ArrayList<>();
        int totalRecs;          // 有任何阶段计数的记录数
        int withOutside;        // 其中含任务外命中的
        boolean anyBaseline;    // 有没有基线任务
    }

    private static boolean has(Map<Integer, Integer> m, int k) {
        Integer v = m.get(k);
        return v != null && v > 0;
    }

    /** 统计任务 n。baselineTask：这件事本身是基线（名字以 @基线 开头）。 */
    static Line compute(int n, String name, boolean baselineTask, List<RecInfo> recs) {
        Line l = new Line();
        l.task = n;
        l.name = name == null ? "" : name;
        l.baselineTask = baselineTask;
        for (RecInfo r : recs) {
            if (!has(r.seg, n)) continue;
            l.hit++;
            if (r.baseline.equals("noise")) {
                l.noise++;
                continue;
            }
            if (r.baseline.equals("live")) l.live++;
            else l.unknown++;
            boolean other = false;
            for (Map.Entry<Integer, Integer> e : r.seg.entrySet()) {
                if (e.getKey() != 0 && e.getKey() != n && e.getValue() > 0) {
                    other = true;
                    break;
                }
            }
            if (other) l.shared++;
            else l.unique++;
        }
        // 做多遍：这件事一共几遍 = 出现过的最高一遍；每遍都出现 = 低 P 位全是 1
        int p = 0;
        for (RecInfo r : recs) {
            Integer mk = r.passes.get(n);
            if (mk != null && mk != 0) p = Math.max(p, 32 - Integer.numberOfLeadingZeros(mk));
        }
        if (p >= 2) {
            l.passes = p;
            int full = (1 << p) - 1;
            for (RecInfo r : recs) {
                Integer mk = r.passes.get(n);
                if (mk != null && has(r.seg, n) && !r.baseline.equals("noise") && (mk & full) == full) l.consistent++;
            }
        }
        return l;
    }

    /** names：任务序号 → 名字（名字以 @基线 开头或含「基线」的算基线任务）。 */
    static Summary computeAll(List<RecInfo> recs, Map<Integer, String> names) {
        Summary s = new Summary();
        java.util.TreeSet<Integer> tasks = new java.util.TreeSet<>(names.keySet());
        for (RecInfo r : recs) {
            boolean any = false;
            for (Map.Entry<Integer, Integer> e : r.seg.entrySet()) {
                if (e.getValue() > 0) {
                    any = true;
                    if (e.getKey() > 0) tasks.add(e.getKey());
                }
            }
            if (any) s.totalRecs++;
            if (has(r.seg, 0)) s.withOutside++;
        }
        for (int n : tasks) {
            String nm = names.get(n);
            boolean base = nm != null && (nm.startsWith("@基线") || nm.contains("基线"));
            if (base) s.anyBaseline = true;
            s.lines.add(compute(n, nm, base, recs));
        }
        return s;
    }

    private static String label(Line l) {
        return "任务 " + l.task + (l.name.isEmpty() ? "" : "「" + l.name.replace("@基线", "基线").trim() + "」");
    }

    /** 一件事一行（浮窗提示用）。 */
    static String describe(Line l) {
        if (l.baselineTask) return "基线记到 " + l.hit + " 条（这些位置之后会被标成周期性噪声）";
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(l.hit).append(" 条");
        if (l.hit > 0) {
            sb.append(" = 噪声 ").append(l.noise).append(" + 独有 ").append(l.unique).append(" + 共有 ").append(l.shared);
            if (l.unknown > 0 && l.noise == 0 && l.live == 0) sb.append("（没录基线，噪声分不出来）");
        }
        if (l.passes >= 2) sb.append("；做了 ").append(l.passes).append(" 遍，每遍都出现 ").append(l.consistent).append(" 条");
        return sb.toString();
    }

    /** 这件事有没有值得提醒的问题（没有返回空串）。 */
    static String warn(Line l, boolean anyBaseline) {
        if (l.baselineTask) return "";
        if (l.hit == 0) return "这件事期间没有记录";
        if (l.noise == l.hit) return "命中的全是基线噪声：这件事很可能没触发到目标位置（也可能目标位置本来就在基线里被调用）";
        if (!anyBaseline && l.noise == 0 && l.live == 0) return "没录基线，周期性噪声没法分开（任务清单第一件写 @基线）";
        if (l.unique == 0 && l.shared > 0 && l.passes < 2) return "没有这件事独有的记录：命中的位置在别的任务里也出现";
        return "";
    }

    /** 导出开头的「洁净度概况」。capNote：记录上限被占满的说明（没有写空串）。 */
    static String report(Summary s, String capNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("洁净度概况（按任务）\n");
        sb.append("  噪声 = 基线安静期间也在周期性调用的位置；独有 = 只在这一件里出现、不是噪声；共有 = 在好几件里都出现、不是噪声\n");
        if (s.lines.isEmpty()) {
            sb.append("  没有按任务阶段计数的记录（没用任务浮窗，或这次记录里没有任务）\n");
        }
        for (Line l : s.lines) {
            String w = warn(l, s.anyBaseline);
            sb.append("  ").append(w.isEmpty() ? "" : "⚠ ").append(label(l)).append("：").append(describe(l)).append('\n');
            if (!w.isEmpty()) sb.append("      ").append(w).append('\n');
        }
        if (s.totalRecs > 0) {
            sb.append("  含任务外命中的记录 ").append(s.withOutside).append(" / ").append(s.totalRecs).append(" 条")
                    .append(s.withOutside > 0 ? "（任务外的命中会让总次数和频率分类偏高；浮窗就绪前和关闭后默认不记，见 flowGate）" : "（干净）").append('\n');
        }
        if (capNote != null && !capNote.isEmpty()) sb.append("  ⚠ ").append(capNote).append('\n');
        return sb.toString();
    }
}
