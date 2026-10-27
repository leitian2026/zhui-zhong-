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
        ConfigCheck.knownClasses = new java.util.HashSet<>(java.util.Arrays.asList("com.q.Real"));
        expect("类名查表", "traceMethods=com.q.Made#foo\n", false, "没有在已有记录");
        List<ConfigCheck.Issue> okl = run("traceMethods=com.q.Real#foo\n");
        ok(!has(okl, false, "没有在已有记录"), "见过的类不应警告");
        ConfigCheck.knownClasses = null;
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
                ConfigCheck.allowExampleClasses = true; // 文档里的示例用 com.demo.* 这类示例类名；检查真实配置时它们会被判错
                clean(where, body);
                ConfigCheck.allowExampleClasses = false;
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
