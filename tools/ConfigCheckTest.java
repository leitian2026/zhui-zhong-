package com.textrec.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置检查器 / 粘贴解析 / 预设 / 文档示例的回归测试（纯 JVM，不需要 Android）。CI 里由 tools/run_config_tests.sh 运行。
 * 文档里的代码块：
 *   ```textrec        必须 0 错误 0 警告（给 AI 抄的正确示例，不能有任何问题）
 *   ```textrec-bad    第一行必须是「# expect: 子串」（或「# expect-warn: 子串」），检查器必须报出包含这段文字的错误（警告）
 * 这样文档里的示例是被机器验证过的，不会越写越错。
 */
public class ConfigCheckTest {
    static int fails = 0, checks = 0;

    static void ok(boolean c, String msg) {
        checks++;
        if (!c) {
            fails++;
            System.out.println("✗ " + msg);
        }
    }

    static List<ConfigCheck.Issue> run(String text) {
        return ConfigCheck.checkPaste(text);
    }

    static boolean has(List<ConfigCheck.Issue> l, boolean error, String sub) {
        for (ConfigCheck.Issue i : l) if (i.error == error && i.msg.contains(sub)) return true;
        return false;
    }

    static void expect(String name, String text, boolean error, String sub) {
        List<ConfigCheck.Issue> l = run(text);
        ok(has(l, error, sub), name + "：应该报" + (error ? "错误" : "警告") + "「" + sub + "」，实际：\n" + ConfigCheck.format(l));
    }

    static void clean(String name, String text) {
        List<ConfigCheck.Issue> l = run(text);
        ok(l.isEmpty(), name + "：应该没有任何问题，实际：\n" + ConfigCheck.format(l));
    }

    public static void main(String[] args) throws IOException {
        schemaTests();
        presetTests();
        oldFormatTests();
        blockFormatTests();
        roundTripTests();
        statusTests();
        regressionTests();
        relationTests();
        modeTests();
        taskStatsTests();
        flowGateTests();
        probeTextTests();
        probeCheckTests();
        formModelTests();
        boolean fix = false;
        for (String f : args) if (f.equals("--fix")) fix = true;
        for (String f : args) if (!f.equals("--fix")) docTests(f, fix);
        System.out.println((fails == 0 ? "全部通过：" : "有失败：") + checks + " 项检查，" + fails + " 项失败");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------ 字段表

    static void schemaTests() {
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                String t = fl.getType() == boolean.class ? "bool" : fl.getType() == int.class ? "int" : fl.getType() == long.class ? "long" : "string";
                ok(t.equals(f.type), "ConfigSchema 里 " + f.key + " 的类型 " + f.type + " 和 RecConfig 的 " + t + " 不一致");
                if (f.type.equals("int")) {
                    RecConfig c = new RecConfig();
                    ok(String.valueOf(fl.getInt(c)).equals(f.def), f.key + " 默认值 " + f.def + " 和 RecConfig 的 " + fl.getInt(c) + " 不一致");
                }
            } catch (Throwable e) {
                ok(false, "RecConfig 没有字段 " + f.key);
            }
        }
    }

    static void presetTests() {
        for (Presets.P p : Presets.ALL) {
            ok(p.kv.length > 0, "预设 " + p.name + " 是空的");
            for (String[] kv : Presets.pairs(p.name)) {
                ConfigSchema.F f = ConfigSchema.get(kv[0]);
                ok(f != null, "预设 " + p.name + " 里有未知字段 " + kv[0]);
                if (f != null && f.type.equals("bool")) ok(kv[1].equals("true") || kv[1].equals("false"), "预设 " + p.name + " 的 " + kv[0] + " 不是 true/false");
                if (f != null && f.type.equals("int")) {
                    try {
                        long v = Long.parseLong(kv[1]);
                        ok(!f.hasRange || (v >= f.min && v <= f.max), "预设 " + p.name + " 的 " + kv[0] + "=" + v + " 超出范围");
                    } catch (NumberFormatException e) {
                        ok(false, "预设 " + p.name + " 的 " + kv[0] + " 不是整数");
                    }
                }
            }
            RecConfig c = new RecConfig();
            Presets.applyTo(c, p.name);
            ok(Presets.matches(c, p.name), "预设 " + p.name + " 应用后 matches 应为 true");
            List<ConfigCheck.Issue> pl = run("preset=" + p.name + "\n");
            ok(!ConfigCheck.hasError(pl), "preset=" + p.name + " 不应有错误：\n" + ConfigCheck.format(pl));
        }
    }

    // ------------------------------------------------------------ 旧格式

    static void oldFormatTests() {
        expect("行内注释（布尔）", "traceSet=true # 备注\n", true, "行内注释");
        expect("行内注释（整数）", "windowMs=1500 # 1.5 秒\n", true, "行内注释");
        expect("\\\\n 两个反斜杠", "traceMethods=com.x.Y#foo\\\\n\\\ncom.x.Z#bar\n", true, "两个反斜杠");
        clean("\\n 加续行（正确写法）", "traceMethods=com.x.Y#foo\\n\\\ncom.x.Z#bar\n");
        expect("续行粘连", "traceMethods=com.x.Y#foo\\\ncom.x.Z#bar\n", true, "粘成一项");
        expect("keywords 没开 keywordMode", "keywords=福利\n", true, "keywordMode");
        expect("子开关没有父开关", "traceSet=false\nsetText=true\n", false, "子项");
        expect("预设拼错", "preset=txt\n", true, "text");
        expect("traceMethods 写两次", "traceMethods=com.x.Y#foo\ntraceMethods=com.x.Z#bar\n", true, "写了两次");
        expect("排除名单排除了追踪的方法", "traceMethods=com.x.Y#foo\nexcludeList=com.x.Y#*\n", false, "排除了");
        expect("示例类名", "traceMethods=com.a.B#foo\n", true, "占位类名");
        expect("windowAll 没有 windowMs", "windowAll=true\n", false, "windowMs");
        expect("chain 子项没有 chainOn", "chainAsync=true\n", false, "chainOn");
        expect("线程矛盾", "traceMethods=com.x.Y#foo\nthreadMode=1\nthreadFilter=LegoHandler\n", false, "矛盾");
        expect("@seq 没有任务", "traceMethods=com.x.Y#foo @seq\n", false, "任务清单");
        expect("tree 子项没有 treeOn", "treeOn=false\ntreeDepth=6\n", false, "treeOn");
        expect("正则没开 regex", "keywordMode=true\nkeywords=\\\\d\\\\d:\\\\d\\\\d\n", false, "regex");
        expect("方法追踪行后有注释", "traceMethods=com.x.Y#foo // 备注\n", true, "多余的文字");
        expect("traceMethods 里的逗号", "traceMethods=com.x.Y#foo, com.x.Z#bar\n", true, "逗号");
        expect("excludeList 非法项", "traceMethods=com.x.Y#foo\nexcludeList=com.x.Y#foo(int)\n", true, "括号");
        expect("dumpPrefixes 非法", "composeDump=true\ndumpPrefixes=androidx.*\n", true, "前缀");
        expect("拼写错的配置项", "traceDrow=true\n", true, "traceDraw");
        expect("布尔写成 yes", "traceSet=yes\n", true, "true");
        expect("数字带单位", "windowMs=1500ms\n", true, "单位");
        expect("enabled=false", "enabled=false\n", false, "enabled");
        clean("最小正确配置（旧格式）", "preset=trace\ntraceMethods=com.x.Y#foo\n\n暂停|点一下视频暂停|\n");
    }

    // ------------------------------------------------------------ 块格式

    static void blockFormatTests() {
        clean("块格式正确示例", "[config]\npreset = trace\nwindowMs = 1500\n\n[traceMethods]\ncom.x.Y#foo @seq\ncom.x.Z#bar\n\n[tasks]\n暂停|点一下视频暂停|\n继续|再点一下|\n");
        clean("块格式 keywords", "[config]\nkeywordMode = true\n\n[keywords]\n福利\n红包\n");
        clean("块格式正则", "[config]\nkeywordMode = true\nregex = true\n\n[keywords]\n\\d\\d:\\d\\d\n昨天\n");
        expect("段名拼错", "[traceMethod]\ncom.x.Y#foo\n", true, "traceMethods");
        expect("config 段里不是 key = value", "[config]\ntraceSet true\n", true, "key = 值");
        expect("段里写了注释在方法后", "[traceMethods]\ncom.x.Y#foo // 备注\n", true, "多余的文字");
        expect("段名后有多余内容", "[traceMethods] 追踪这些\ncom.x.Y#foo\n", true, "单独占一行");
        expect("冒号", "[config]\ntraceSet: true\n", true, "等号");
        expect("keywords 段里的注释", "[config]\nkeywordMode = true\n\n[keywords]\n// 备注\n福利\n", false, "关键字");
        expect("块格式缺 keywordMode", "[keywords]\n福利\n", true, "keywordMode");
        expect("段重复", "[traceMethods]\ncom.x.Y#foo\n[traceMethods]\ncom.x.Z#bar\n", false, "不止一次");
        expect("块格式行号", "[config]\npreset = trace\n\n[traceMethods]\ncom.x.Y#foo\ncom.x.Z\n", true, "缺少");
        List<ConfigCheck.Issue> l = run("[config]\npreset = trace\n\n[traceMethods]\ncom.x.Y#foo\ncom.x.Z\n");
        boolean line6 = false;
        for (ConfigCheck.Issue i : l) if (i.error && i.line == 6) line6 = true;
        ok(line6, "块格式里错误应指到第 6 行（真实行号）：\n" + ConfigCheck.format(l));
        // 拆分结果
        String[] parts = PasteBlocks.split("[config]\npreset = text\nstackDepth = 20\n\n[traceMethods]\ncom.x.Y#foo @seq\ncom.x.Z#bar\n\n[keywords]\n\\d+小时\n\n[tasks]\n暂停|点一下|\n");
        RecConfig c = RecConfig.fromText(parts[0]);
        ok(c.stackDepth == 20 && c.traceSet && c.traceDraw && !c.traceIcons, "预设展开后被显式字段覆盖：stackDepth=" + c.stackDepth);
        ok(c.traceMethods.equals("com.x.Y#foo @seq\ncom.x.Z#bar"), "traceMethods 应保留两行：" + c.traceMethods);
        ok(c.keywords.equals("\\d+小时"), "keywords 应保留反斜杠：" + c.keywords);
        ok(parts[1].trim().equals("暂停|点一下|"), "任务清单应原样拆出：" + parts[1]);
        // 旧格式 + preset
        String[] old = PasteBlocks.split("preset=hook\nwindowMs=3000\n\n暂停|点一下|\n");
        RecConfig oc = RecConfig.fromText(old[0]);
        ok(oc.windowMs == 3000 && oc.excludeInfra && oc.quietCount == 50, "旧格式 preset 展开：windowMs=" + oc.windowMs + " infra=" + oc.excludeInfra);
        ok(old[1].trim().equals("暂停|点一下|"), "旧格式任务清单拆分");
    }

    // ------------------------------------------------------------ 导出再粘贴 = 同一份配置

    static void roundTripTests() {
        RecConfig c = new RecConfig();
        c.keywordMode = true;
        c.keywords = "福利\n红包\n\\d+";
        c.traceMethods = "// 注释\ncom.x.Y#foo @seq\ncom.x.Z#bar";
        c.excludeList = "androidx.lifecycle.LiveData#considerNotify\n#run";
        c.windowMs = 2000;
        c.quietCount = 200;
        c.threadFilter = "main";
        c.dumpPrefixes = "a.b.\nc.d.";
        c.watch = "com.x.Y#foo:arg0.a";
        c.treeViews = true;
        String text = PasteBlocks.export(c, "暂停|点一下|\n");
        String[] parts = PasteBlocks.split(text);
        RecConfig d = RecConfig.fromText(parts[0]);
        ok(d.toText().replaceAll("(?m)^#.*\\n", "").equals(c.toText().replaceAll("(?m)^#.*\\n", "")), "导出再粘贴应得到同一份配置：\n" + text + "\n---\n" + c.toText() + "\n---\n" + d.toText());
        ok(parts[1].trim().equals("暂停|点一下|"), "导出带任务清单");
        List<ConfigCheck.Issue> l = ConfigCheck.checkPaste(text);
        ok(!ConfigCheck.hasError(l), "导出的块格式不应有错误：\n" + ConfigCheck.format(l));
        // 用转义写的值也能被 escapeValue 还原
        String v = " 前导空格\\反斜杠\n换行\t制表符中文";
        java.util.Properties p = new java.util.Properties();
        try {
            p.load(new java.io.StringReader("k=" + PasteBlocks.escapeValue(v) + "\n"));
        } catch (IOException e) {
            ok(false, "escapeValue 产物无法 load");
        }
        ok(v.equals(p.getProperty("k")), "escapeValue 应可逆");
    }

    // ------------------------------------------------------------ 目标 App 回读

    static void statusTests() {
        RecConfig pushed = new RecConfig();
        pushed.epoch = 5;
        pushed.traceMethods = "com.x.Y#foo\ncom.x.Z#bar";
        pushed.traceSet = true;
        RecConfig eff = RecConfig.fromText(pushed.toText());
        eff.traceSet = false;
        String raw = "__FILE__ /data/x/status-a.txt\n#textrec-status 1\nproc=a\nepoch=5\ntime=1000\n[trace]\nOK com.x.Y#foo\nERR com.x.Z#bar | 类未找到\n[effective]\n" + eff.toText() + "\n";
        List<TargetStatus.Proc> ps = TargetStatus.parse(raw);
        ok(ps.size() == 1 && ps.get(0).epoch == 5 && ps.get(0).traces.size() == 2, "状态文件解析");
        ok(ps.get(0).eff != null && !ps.get(0).eff.traceSet, "实际生效配置解析");
        List<String[]> d = TargetStatus.diff(pushed, ps.get(0).eff);
        ok(d.size() == 1 && d.get(0)[0].equals("traceSet"), "对比应只发现 traceSet：" + d.size());
        String desc = TargetStatus.describe(ps, pushed, 4000);
        ok(desc.contains("方法追踪独占") && desc.contains("类未找到") && desc.contains("1 个有问题") && desc.contains("已读到"), "报告内容：\n" + desc);
        pushed.epoch = 6;
        ok(TargetStatus.describe(ps, pushed, 4000).contains("旧配置"), "epoch 不一致应提示旧配置");
        ok(TargetStatus.describe(new ArrayList<TargetStatus.Proc>(), pushed, 0).contains("还没有写过状态"), "没有状态文件的提示");
        ok(TargetStatus.problems(ps) == 1, "problems 计数");
        java.util.Set<String> kn = KnownNames.extract("at com.ss.android.ugc.aweme.feed.X0.a.b(Foo.java:12) 以及 java.lang.String");
        ok(kn.contains("com.ss.android.ugc.aweme.feed.X0.a.b") || kn.contains("com.ss.android.ugc.aweme.feed.X0.a"), "类名提取：" + kn);
        // 类名查表：已知类集合非空时，没见过的类给警告；见过的不警告
        ConfigCheck.Options o = new ConfigCheck.Options().known(new java.util.HashSet<>(java.util.Arrays.asList("com.q.Real")));
        List<ConfigCheck.Issue> l1 = ConfigCheck.checkPaste("traceMethods=com.q.Made#foo\n", o);
        ok(has(l1, false, "没有在已有记录"), "没见过的类应警告：\n" + ConfigCheck.format(l1));
        List<ConfigCheck.Issue> l2 = ConfigCheck.checkPaste("traceMethods=com.q.Real#foo\n", o);
        ok(!has(l2, false, "没有在已有记录"), "见过的类不应警告");
        // 回归：调用栈里的帧是「类.方法(文件:行号)」，类名必须能从帧里提取出来
        java.util.Set<String> fromFrame = KnownNames.extract("at com.q.deep.Real.doIt(Real.java:42)\nat com.q.deep.Real$Inner.run(Real.java:50)");
        ok(fromFrame.contains("com.q.deep.Real") && fromFrame.contains("com.q.deep.Real$Inner"), "从栈帧提取类名：" + fromFrame);
        List<ConfigCheck.Issue> l3 = ConfigCheck.checkPaste("traceMethods=com.q.deep.Real#doIt\n", new ConfigCheck.Options().known(fromFrame));
        ok(!has(l3, false, "没有在已有记录"), "从栈帧学到的类不应被误报：\n" + ConfigCheck.format(l3));
    }

    // 关系：多条件、任一个、递归
    static void relationTests() {
        ok(!has(run("traceMethods=com.q.A#foo\ntraceDump=true\ndumpPrefixes=com.q.\n"), false, "不起作用"), "dumpPrefixes 配 traceDump 不应报不起作用");
        ok(!has(run("dumpPrefixes=com.q.\n"), false, "不起作用"), "composeDump 默认开着，dumpPrefixes 单独写是有效的");
        expect("dumpPrefixes 两个都没开", "composeDump=false\ndumpPrefixes=com.q.\n", false, "至少一个");
        expect("composeDump 缺 autoParams", "autoParams=false\ntraceCompose=true\n", false, "autoParams");
        expect("chainCoroutine 缺 chainAsync", "chainOn=true\nchainAsync=false\nchainCoroutine=true\n", false, "chainAsync");
        expect("递归：tlTouch 的祖先 tlOn 关了", "tlOn=false\ntlTouch=false\ntlOps=true\n", false, "tlOn");
        expect("递归根因", "tlOn=false\ntlTouch=true\n", false, "自己也不起作用");
        expect("traceSetOnly 缺 traceSet", "traceSet=false\ntraceSetOnly=true\n", false, "traceSet");
    }

    // 当前模式下没用：方法追踪独占时，关键字 / 控件过滤不起作用
    static void modeTests() {
        expect("trace 预设 + keywords", "preset=trace\nkeywordMode=true\nkeywords=福利\ntraceMethods=com.q.A#foo\n", false, "只会记方法追踪");
        expect("traceMethods + keywords（默认独占）", "keywordMode=true\nkeywords=福利\ntraceMethods=com.q.A#foo\n", false, "traceExclusive");
        expect("trace 预设 + viewFilter", "preset=trace\nviewFilter=DiggLayout\ntraceMethods=com.q.A#foo\n", false, "viewFilter");
        ok(!has(run("keywordMode=true\nkeywords=福利\ntraceMethods=com.q.A#foo\ntraceExclusive=false\n"), false, "不起作用"), "traceExclusive=false 时关键字照常有效");
        ok(!has(run("preset=text\nkeywordMode=true\nkeywords=福利\n"), false, "不起作用"), "没写方法追踪时关键字有效");
        expect("内容全关 + keywords", "preset=icons\ntraceIcons=false\nkeywordMode=true\nkeywords=福利\n", false, "只会记方法追踪");
        ok(!has(run("preset=trace\nthreadFilter=main\ntraceMethods=com.q.A#foo\n"), false, "不起作用"), "threadFilter 对方法追踪有效，不应警告");
    }

    // 按任务统计：噪声 / 独有 / 共有 / 多遍一致
    static TaskStats.RecInfo ri(String bl, int[][] seg, int[][] passes) {
        java.util.TreeMap<Integer, Integer> s = new java.util.TreeMap<>();
        for (int[] x : seg) s.put(x[0], x[1]);
        java.util.TreeMap<Integer, Integer> p = new java.util.TreeMap<>();
        if (passes != null) for (int[] x : passes) p.put(x[0], x[1]);
        return new TaskStats.RecInfo(s, bl, p);
    }

    static void taskStatsTests() {
        java.util.List<TaskStats.RecInfo> recs = new java.util.ArrayList<>();
        // 任务 1 = 基线；任务 2 = 划视频（做 3 遍）；任务 3 = 点赞
        for (int i = 0; i < 5; i++) recs.add(ri("noise", new int[][]{{1, 4}, {2, 9}, {3, 6}}, null));     // 周期性噪声：基线里也有
        recs.add(ri("live", new int[][]{{2, 3}}, new int[][]{{2, 7}}));                                  // 只在任务 2，三遍都出现
        recs.add(ri("live", new int[][]{{2, 2}}, new int[][]{{2, 3}}));                                  // 只在任务 2，只出现 2 遍
        recs.add(ri("live", new int[][]{{2, 1}, {3, 1}}, new int[][]{{2, 7}}));                           // 任务 2 和 3 共有
        recs.add(ri("live", new int[][]{{3, 5}}, null));                                                  // 只在任务 3
        recs.add(ri("live", new int[][]{{3, 2}, {0, 8}}, null));                                          // 任务 3 + 任务外命中
        java.util.Map<Integer, String> names = new java.util.TreeMap<>();
        names.put(1, "@基线 什么都不要操作");
        names.put(2, "划到下一个视频");
        names.put(3, "点赞");
        TaskStats.Summary s = TaskStats.computeAll(recs, names);
        TaskStats.Line l2 = s.lines.get(1), l3 = s.lines.get(2);
        ok(s.lines.get(0).baselineTask && s.lines.get(0).hit == 5, "任务 1 是基线，命中 5 条");
        ok(l2.hit == 8 && l2.noise == 5 && l2.unique == 2 && l2.shared == 1, "任务 2：噪声 5 + 独有 2 + 共有 1：" + TaskStats.describe(l2));
        ok(l2.passes == 3 && l2.consistent == 2, "任务 2 做了 3 遍，每遍都出现 2 条（独有 1 + 共有 1）：" + l2.passes + "/" + l2.consistent);
        ok(l3.hit == 8 && l3.noise == 5 && l3.unique == 2 && l3.shared == 1, "任务 3：" + TaskStats.describe(l3));
        ok(s.totalRecs == 10 && s.withOutside == 1, "含任务外命中的记录 1 / 10：" + s.withOutside + "/" + s.totalRecs);
        String rep = TaskStats.report(s, "");
        ok(rep.contains("任务 2「划到下一个视频」") && rep.contains("含任务外命中的记录 1 / 10"), "报告内容：\n" + rep);
        // 提醒
        java.util.List<TaskStats.RecInfo> allNoise = new java.util.ArrayList<>();
        allNoise.add(ri("noise", new int[][]{{1, 1}, {2, 3}}, null));
        TaskStats.Line ln = TaskStats.compute(2, "点赞", false, allNoise);
        ok(TaskStats.warn(ln, true).contains("全是基线噪声"), "命中全是噪声应提醒");
        ok(TaskStats.warn(TaskStats.compute(5, "x", false, allNoise), true).contains("没有记录"), "没有命中应提醒");
        java.util.List<TaskStats.RecInfo> noBase = new java.util.ArrayList<>();
        noBase.add(ri("", new int[][]{{2, 3}}, null));
        ok(TaskStats.warn(TaskStats.compute(2, "点赞", false, noBase), false).contains("没录基线"), "没录基线应提醒");
        ok(TaskStats.describe(TaskStats.compute(2, "点赞", false, noBase)).contains("没录基线"), "描述里注明没录基线");
        ok(TaskStats.report(TaskStats.computeAll(new java.util.ArrayList<TaskStats.RecInfo>(), new java.util.TreeMap<Integer, String>()), "").contains("没有按任务阶段计数"), "没有任务的报告");
    }

    // flowGate / noiseQuota
    static void flowGateTests() {
        ok(!has(run("flowGate=true\n"), false, "不起作用"), "flowGate 默认开着，写 true 不应警告");
        ok(!has(run("flowGate=false\n"), false, "不起作用"), "flowGate=false 不应警告");
        expect("flowGate 配 recOutside", "recOutside=true\nflowGate=true\n", false, "只在 `recOutside=false` 时才有效");
        expect("noiseQuota 超范围", "noiseQuota=500\n", false, "超出允许范围");
        expect("noiseQuota 太小", "noiseQuota=0\n", false, "超出允许范围");
        clean("noiseQuota 正常", "noiseQuota=70\n");
    }

    // 视图快照：配置检查
    static void probeCheckTests() {
        clean("viewProbe 块格式", "[config]\nprobeAfterMs = 0,200,1000,2500\n\n[viewProbe]\ncom.q.feed.DiggView\ncom.q.feed.DiggLayout\n");
        clean("viewProbe 旧格式", "viewProbe=com.q.feed.DiggView\\ncom.q.feed.DiggLayout\nprobeAfterMs=0,500\n");
        expect("probeAfterMs 没有 viewProbe", "probeAfterMs=0,500\n", false, "viewProbe");
        ok(!has(run("probeAfterMs=0,200,1000,2500\n"), false, "不起作用"), "写默认值不警告");
        expect("probeAfterMs 全角逗号", "viewProbe=com.q.A\nprobeAfterMs=0，200\n", true, "全角");
        expect("probeAfterMs 带单位", "viewProbe=com.q.A\nprobeAfterMs=0,200ms\n", true, "不带单位");
        expect("probeAfterMs 空格分隔", "viewProbe=com.q.A\nprobeAfterMs=0 200 1000\n", true, "逗号分隔");
        expect("probeAfterMs 超范围", "viewProbe=com.q.A\nprobeAfterMs=0,20000\n", true, "0～10000");
        expect("probeAfterMs 空项", "viewProbe=com.q.A\nprobeAfterMs=0,,200\n", true, "空项");
        expect("probeAfterMs 重复", "viewProbe=com.q.A\nprobeAfterMs=0,200,200\n", false, "写了两次");
        expect("probeAfterMs 太多", "viewProbe=com.q.A\nprobeAfterMs=1,2,3,4,5,6,7,8,9\n", false, "最多取");
        expect("viewProbe 示例类名", "viewProbe=com.a.B\n", true, "占位类名");
        expect("viewProbe 缺 #", "viewProbe=com.q.A#foo\n", true, "viewProbe");
        expect("viewProbe 没有包名", "viewProbe=DiggView\n", false, "没有包名");
        expect("viewProbe 段名拼错", "[viewProb]\ncom.q.A\n", true, "viewProbe");
        List<ConfigCheck.Issue> kn = ConfigCheck.checkPaste("viewProbe=com.q.Made\n", new ConfigCheck.Options().known(new java.util.HashSet<>(java.util.Arrays.asList("com.q.Real"))));
        ok(has(kn, false, "没有在已有记录里出现过"), "没见过的类应警告");
        RecConfig d = new RecConfig();
        ok(d.viewProbe.isEmpty() && d.probeAfterMs.equals("0,200,1000,2500"), "默认值：不启用");
        RecConfig p = RecConfig.fromText(PasteBlocks.split("[config]\nprobeAfterMs = 0,300\n\n[viewProbe]\ncom.q.A\ncom.q.B\n")[0]);
        ok(p.viewProbe.equals("com.q.A\ncom.q.B") && p.probeAfterMs.equals("0,300"), "块格式读进配置：" + p.viewProbe);
        String ex = PasteBlocks.export(p, "");
        ok(ex.contains("[viewProbe]\ncom.q.A\ncom.q.B") && ex.contains("probeAfterMs = 0,300"), "导出块格式：\n" + ex);
    }

    // 视图快照：配置解析、对比
    static void probeTextTests() {
        ok(java.util.Arrays.equals(ProbeText.parseTimes("0,200,1000,2500"), new int[]{0, 200, 1000, 2500}), "默认时间点解析");
        ok(java.util.Arrays.equals(ProbeText.parseTimes("2500, 0 ,200,200"), new int[]{0, 200, 2500}), "乱序去重后升序");
        ok(java.util.Arrays.equals(ProbeText.parseTimes(""), new int[]{0, 200, 1000, 2500}), "空值回退默认");
        ok(java.util.Arrays.equals(ProbeText.parseTimes("abc,-5,99999"), new int[]{0, 200, 1000, 2500}), "全是坏值回退默认");
        ok(ProbeText.parseTimes("1,2,3,4,5,6,7,8,9,10").length == 8, "最多 8 个");
        java.util.List<String> cls = ProbeText.splitClasses("// 注释\ncom.demo.A\n\ncom.demo.B, com.demo.A\n");
        ok(cls.size() == 2 && cls.get(0).equals("com.demo.A") && cls.get(1).equals("com.demo.B"), "类名拆分去重：" + cls);
        java.util.Set<String> names = new java.util.HashSet<>(java.util.Arrays.asList("com.demo.Base"));
        ok("com.demo.Base".equals(ProbeText.matchChain(java.util.Arrays.asList("com.demo.Sub", "com.demo.Base", "android.view.View"), names)), "父类也能匹配");
        ok(ProbeText.matchChain(java.util.Arrays.asList("android.widget.TextView", "android.view.View"), names) == null, "不相关的类不匹配");
        java.util.LinkedHashMap<String, String> a = ProbeText.map(), b = ProbeText.map();
        a.put("cls", "com.demo.A"); a.put("alpha", "1.00"); a.put("imageAlpha", "255"); a.put("p1", "FrameLayout vis=V");
        b.put("cls", "com.demo.A"); b.put("alpha", "0.00"); b.put("imageAlpha", "0"); b.put("p1", "FrameLayout vis=V"); b.put("selected", "true");
        String d = ProbeText.diff(a, b);
        ok(d.equals("alpha 1.00→0.00，imageAlpha 255→0，selected —→true"), "变化对比：" + d);
        ok(ProbeText.diff(a, a).isEmpty(), "没变化返回空串");
        ok(ProbeText.line(b).startsWith("com.demo.A alpha=0.00") && !ProbeText.line(b).contains("p1"), "主要字段一行：" + ProbeText.line(b));
        ok(ProbeText.parents(b).equals("↑ 父级：d1 FrameLayout vis=V"), "父级链：" + ProbeText.parents(b));
        ok(ProbeText.parents(ProbeText.map()).isEmpty(), "没有父级");
    }

    // 回归：任务清单在别处已有时，不该报「没有任务清单」；常见示例包名只警告不判错；预设只亮最贴近的一个
    static void regressionTests() {
        RecConfig c = new RecConfig();
        c.viewDump = true;
        c.traceMethods = "com.q.Real#foo @seq";
        ok(has(ConfigCheck.checkForm(c, "", new ConfigCheck.Options()), false, "没有任务清单"), "没有任务清单时应提醒");
        ok(!has(ConfigCheck.checkForm(c, "", new ConfigCheck.Options().tasksElsewhere(true)), false, "没有任务清单"), "任务清单在别处已有时不应提醒");
        ok(!has(ConfigCheck.checkPaste("[config]\nviewDump = true\n", new ConfigCheck.Options().tasksElsewhere(true)), false, "没有任务清单"), "粘贴路径同样");
        List<ConfigCheck.Issue> ex = run("traceMethods=com.example.app.Main#foo\n");
        ok(!ConfigCheck.hasError(ex) && has(ex, false, "示例包名"), "com.example 应只警告：\n" + ConfigCheck.format(ex));
        expect("com.demo 仍判错", "traceMethods=com.demo.A#foo\n", true, "占位类名");
        RecConfig t = new RecConfig();
        Presets.applyTo(t, "trace");
        ok(Presets.best(t, "scene") != null && Presets.best(t, "scene").name.equals("trace"), "只选 trace 时最贴近的应是 trace");
        Presets.applyTo(t, "statehook");
        ok(Presets.best(t, "scene").name.equals("statehook"), "选了 statehook 时最贴近的应是 statehook");
        RecConfig t2 = new RecConfig();
        Presets.applyTo(t2, "trace");
        ok(!Presets.matches(t2, "statehook"), "trace 不应同时匹配 statehook");
    }

    // ------------------------------------------------------------ 首页表单 <-> 配置

    static void formModelTests() {
        // 假设首页对所有「非内部、非分段控件」的字段都有控件；keywordMode / threadMode 是分段控件，单独传
        java.util.LinkedHashSet<String> widgets = new java.util.LinkedHashSet<>();
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.group.equals("internal") || f.key.equals("keywordMode") || f.key.equals("threadMode")) continue;
            widgets.add(f.key);
        }
        RecConfig c = new RecConfig();
        c.traceMethods = "com.x.Y#foo @seq\ncom.x.Z#bar";
        c.keywords = "福利\n\\d+";
        c.regex = true;
        c.keywordMode = true;
        c.threadMode = 2;
        c.windowMs = 2000;
        c.quietCount = 200;
        c.flushSec = 5;
        c.treeViews = true;
        c.capTrace = 123;
        c.epoch = 77;
        RecConfig base = new RecConfig();
        java.util.Map<String, String> vals = FormModel.toValues(c, widgets);
        RecConfig back = FormModel.read(base, vals, c.keywordMode, c.threadMode, c.epoch);
        ok(back.toText().replaceAll("(?m)^#.*\\n", "").equals(c.toText().replaceAll("(?m)^#.*\\n", "")), "写进控件再读出来应是同一份配置");
        ok(back.windowMs == 2000 && back.quietCount == 200 && back.flushSec == 5, "windowMs / quietCount / flushSec 不应被重置");
        // 没有控件的字段保持 base 里的值
        RecConfig b2 = new RecConfig();
        b2.flushSec = 9;
        java.util.Map<String, String> v2 = new java.util.LinkedHashMap<>(vals);
        v2.remove("flushSec");
        ok(FormModel.read(b2, v2, true, 0, 1).flushSec == 9, "没有控件的字段应保持原值");
        // 钳制和坏值
        java.util.Map<String, String> v3 = new java.util.LinkedHashMap<>();
        v3.put("stackDepth", "9999");
        v3.put("windowMs", "abc");
        v3.put("viewDumpKey", "  ");
        RecConfig r3 = FormModel.read(new RecConfig(), v3, false, 7, 1);
        ConfigSchema.F sd = ConfigSchema.get("stackDepth");
        ok(r3.stackDepth == sd.max, "超范围应钳制到上限：" + r3.stackDepth);
        ok(r3.windowMs == new RecConfig().windowMs, "非数字应保持原值");
        ok(r3.viewDumpKey.equals(new RecConfig().viewDumpKey), "viewDumpKey 空白应回退默认值");
        ok(r3.threadMode == 2, "threadMode 应钳制到 0～2");
        // 每个字段都能走一遍「写进控件 / 读出」而不丢
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (!widgets.contains(f.key)) continue;
            java.util.Map<String, String> one = FormModel.toValues(new RecConfig(), widgets);
            ok(one.containsKey(f.key), "字段 " + f.key + " 应有控件值");
        }
        // 已改项计数
        RecConfig ch = new RecConfig();
        ch.windowMs = 1500;
        ch.traceMethods = "com.x.Y#foo";
        ch.stackDepth = 20;
        java.util.Map<String, Integer> n = FormModel.changedCounts(ch);
        ok(n.get("filter") != null && n.get("filter") == 1 && n.get("trace") != null && n.get("trace") == 1 && n.get("limits") != null && n.get("limits") == 1, "已改项计数：" + n);
        ok(FormModel.changedCounts(new RecConfig()).isEmpty(), "默认配置没有已改项");
        // 变灰规则
        java.util.Set<String> offSet = new java.util.HashSet<>(java.util.Arrays.asList("traceSet"));
        java.util.function.Predicate<String> own = k -> !offSet.contains(k) && !(k.equals("splitFiles") && offSet.contains("!splitFiles"));
        ok(FormModel.dead(ConfigSchema.get("setText"), own), "子项变灰：父开关 traceSet 关着时灰");
        offSet.clear();
        ok(!FormModel.dead(ConfigSchema.get("setText"), own), "父开关开着时不灰");
        offSet.add("splitFiles");
        ok(!FormModel.dead(ConfigSchema.get("maxRecords"), own), "maxRecords：splitFiles 关着时有效");
        offSet.clear();
        ok(FormModel.dead(ConfigSchema.get("maxRecords"), own), "maxRecords：splitFiles 开着时灰");
    }

    // ------------------------------------------------------------ 文档里的示例

    static void docTests(String file, boolean fix) throws IOException {
        String doc = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        // 预设表由 Presets.docTable() 生成；不一致时：--fix 重写，否则报错
        Matcher pm = Pattern.compile("(?s)(<!-- GEN:presets -->\\n)(.*?)(<!-- /GEN:presets -->)").matcher(doc);
        if (pm.find()) {
            String want = pm.group(1) + "\n" + Presets.docTable() + "\n" + pm.group(3);
            if (!pm.group(0).equals(want)) {
                if (fix) {
                    doc = doc.substring(0, pm.start()) + want + doc.substring(pm.end());
                    Files.write(Paths.get(file), doc.getBytes(StandardCharsets.UTF_8));
                    System.out.println("已重写预设表：" + file);
                } else {
                    ok(false, file + "：预设表和 Presets.java 不一致，运行 tools/run_config_tests.sh --fix");
                }
            }
        }
        Matcher m = Pattern.compile("(?s)```(textrec(?:-bad)?)[ \\t]*\\n(.*?)\\n```").matcher(doc);
        int good = 0, bad = 0;
        while (m.find()) {
            String kind = m.group(1), body = m.group(2);
            int startLine = doc.substring(0, m.start()).split("\n", -1).length;
            String where = file + " 第 " + startLine + " 行起的示例";
            if (kind.equals("textrec")) {
                good++;
                ConfigCheck.Options o = new ConfigCheck.Options();
                o.allowExampleClasses = true; // 文档里的示例用 com.demo.* 这类示例类名；检查真实配置时它们会被判错
                List<ConfigCheck.Issue> l = ConfigCheck.checkPaste(body, o);
                ok(l.isEmpty(), where + "：应该没有任何问题，实际：\n" + ConfigCheck.format(l));
            } else {
                bad++;
                Matcher em = Pattern.compile("^#\\s*expect(-warn)?:\\s*(.+)$", Pattern.MULTILINE).matcher(body);
                if (!em.find()) {
                    ok(false, where + "：textrec-bad 示例必须有一行「# expect: 子串」");
                    continue;
                }
                expect(where, body, em.group(1) == null, em.group(2).trim());
            }
        }
        System.out.println(file + "：正确示例 " + good + " 个，错误示例 " + bad + " 个");
    }
}
