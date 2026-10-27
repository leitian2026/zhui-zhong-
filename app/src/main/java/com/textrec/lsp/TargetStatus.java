package com.textrec.lsp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 目标 App 回写的状态（纯 Java，不依赖 Android）：目标进程每次重载配置 / 方法追踪安装结果变化时，写一个 status-进程名.txt：
 * <pre>
 * #textrec-status 1
 * proc=com.xxx
 * epoch=1730000000000
 * time=1730000001234
 * [trace]
 * OK 类#方法
 * ERR 类#方法 | 原因
 * WAIT 类#方法 | 原因（类还没加载，会自动重试）
 * [effective]
 * Properties 文本（目标 App 里实际用的配置，已经过范围钳制和「方法追踪独占」）
 * </pre>
 * 模块首页用 root 读回来（{@link RootShell#pullStatus}），和推送出去的配置逐项对比。
 * 离线的 ConfigCheck 只能查「写得对不对」，查不了「类存不存在、方法在不在」，这一步补上最后一环。
 */
final class TargetStatus {
    private TargetStatus() {}

    static final class Trace {
        String state, target, msg;
    }

    static final class Proc {
        String file = "", proc = "";
        long epoch, time;
        final ArrayList<Trace> traces = new ArrayList<>();
        String effText = "";
        RecConfig eff;
    }

    /** raw = RootShell.pullStatus 的输出：每个文件前有一行「__FILE__ 路径」。 */
    static List<Proc> parse(String raw) {
        ArrayList<Proc> out = new ArrayList<>();
        if (raw == null) return out;
        Proc cur = null;
        String section = "";
        StringBuilder eff = null;
        for (String line : raw.split("\\r?\\n")) {
            if (line.startsWith("__FILE__ ")) {
                if (cur != null) finish(cur, eff);
                cur = new Proc();
                cur.file = line.substring(9).trim();
                out.add(cur);
                section = "";
                eff = new StringBuilder();
                continue;
            }
            if (cur == null) continue;
            if (section.equals("effective")) {
                eff.append(line).append('\n');
                continue;
            }
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.equals("[trace]")) {
                section = "trace";
                continue;
            }
            if (t.equals("[effective]")) {
                section = "effective";
                continue;
            }
            if (section.isEmpty()) {
                int i = t.indexOf('=');
                if (i <= 0) continue;
                String k = t.substring(0, i), v = t.substring(i + 1).trim();
                if (k.equals("proc")) cur.proc = v;
                else if (k.equals("epoch")) cur.epoch = parseLong(v);
                else if (k.equals("time")) cur.time = parseLong(v);
            } else if (section.equals("trace")) {
                int sp = t.indexOf(' ');
                if (sp <= 0) continue;
                Trace tr = new Trace();
                tr.state = t.substring(0, sp);
                String rest = t.substring(sp + 1).trim();
                int bar = rest.indexOf(" | ");
                tr.target = bar >= 0 ? rest.substring(0, bar) : rest;
                tr.msg = bar >= 0 ? rest.substring(bar + 3) : "";
                cur.traces.add(tr);
            }
        }
        if (cur != null) finish(cur, eff);
        return out;
    }

    private static void finish(Proc p, StringBuilder eff) {
        p.effText = eff == null ? "" : eff.toString();
        p.eff = p.effText.trim().isEmpty() ? null : RecConfig.fromText(p.effText);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 推送出去的配置和目标 App 实际生效的配置逐项对比，返回 {key, 推送的值, 实际的值}。epoch 不比。 */
    static List<String[]> diff(RecConfig pushed, RecConfig eff) {
        ArrayList<String[]> out = new ArrayList<>();
        if (pushed == null || eff == null) return out;
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.key.equals("epoch")) continue;
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                String a = String.valueOf(fl.get(pushed)), b = String.valueOf(fl.get(eff));
                if (!a.equals(b)) out.add(new String[]{f.key, a, b});
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String why(String key, RecConfig pushed) {
        switch (key) {
            case "traceSet":
            case "traceDraw":
            case "traceColor":
            case "traceIcons":
            case "autoParams":
            case "traceSetOnly":
            case "traceState":
                return "方法追踪独占：写了 traceMethods，其他记录开关在目标 App 里被自动关闭（要一起记就把 traceExclusive 设成 false）";
            default:
                return "目标 App 读配置时改了这个值";
        }
    }

    /** 一份给人看的报告：是否已读到新配置、和推送的差在哪、每个钩点装没装上。 */
    static String describe(List<Proc> procs, RecConfig pushed, long nowMs) {
        StringBuilder sb = new StringBuilder();
        if (procs == null || procs.isEmpty()) {
            return "目标 App 还没有写过状态：App 还没启动 / 这个包不在 LSPosed 作用域里 / 还没有「应用设置」过 / 模块版本太旧。"
                    + "先点「应用并重新记录」，强杀目标 App 再打开，再读一次。";
        }
        for (Proc p : procs) {
            String name = p.proc.isEmpty() ? p.file : p.proc;
            sb.append("▸ 进程 ").append(name);
            if (p.time > 0 && nowMs >= p.time) sb.append("（").append(ago(nowMs - p.time)).append("更新）");
            sb.append('\n');
            if (pushed != null) {
                if (p.epoch != pushed.epoch) {
                    sb.append("  ✗ 目标 App 里还是旧配置（版本标记不一致）：它还没读到你刚推送的设置。强杀目标 App 重开，或在里面操作一下再读。\n");
                } else {
                    sb.append("  ✓ 已读到你推送的配置\n");
                }
                List<String[]> d = p.epoch == pushed.epoch ? diff(pushed, p.eff) : new ArrayList<String[]>();
                if (!d.isEmpty()) {
                    sb.append("  和你推送的不同的项（").append(d.size()).append("）：\n");
                    int shown = 0;
                    for (String[] x : d) {
                        if (shown++ >= 12) {
                            sb.append("    …还有 ").append(d.size() - 12).append(" 项\n");
                            break;
                        }
                        sb.append("    ").append(x[0]).append("：你写 ").append(clip(x[1])).append(" → 实际 ").append(clip(x[2])).append("；").append(why(x[0], pushed)).append('\n');
                    }
                } else if (p.epoch == pushed.epoch && p.eff != null) {
                    sb.append("  ✓ 实际生效的配置和你推送的完全一致\n");
                }
            }
            int ok = 0, wait = 0, err = 0;
            for (Trace t : p.traces) {
                if (t.state.equals("OK")) ok++;
                else if (t.state.equals("WAIT")) wait++;
                else err++;
            }
            if (p.traces.isEmpty()) {
                sb.append("  方法追踪：没有要追踪的方法\n");
            } else {
                sb.append("  方法追踪：").append(ok).append(" 个已装上");
                if (wait > 0) sb.append("，").append(wait).append(" 个还在等（类没加载）");
                if (err > 0) sb.append("，").append(err).append(" 个有问题");
                sb.append('\n');
                for (Trace t : p.traces) {
                    if (t.state.equals("OK")) continue;
                    sb.append("    ").append(t.state.equals("WAIT") ? "… " : "✗ ").append(t.target).append(" — ").append(t.msg).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        String t = s.replace('\n', ' ');
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }

    static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 90) return s + " 秒前";
        if (s < 5400) return (s / 60) + " 分钟前";
        return String.format(Locale.ROOT, "%.1f 小时前", s / 3600.0);
    }

    /** 方法追踪里有没有装失败（ERR）或还在等（WAIT）的，给首页汇总用。 */
    static int problems(List<Proc> procs) {
        int n = 0;
        for (Proc p : procs) for (Trace t : p.traces) if (!t.state.equals("OK")) n++;
        return n;
    }

}
