package com.textrec.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 首页，按「你要做什么」而不是「代码里有什么开关」来排：
 * 目标 App → 我想找什么（一键套预设）→ 导入 / 导出（粘贴 AI 给的配置）→ 六个折叠分组（记录内容 / 记录范围 / 方法追踪 / 采集过滤 / 视图与时间线 /
 * 记录容量）→ 目标 App 实际生效的配置（回读）。底部固定「查看记录」和「应用并重新记录」。
 *
 * 【给 AI / 维护者】控件不再一个个手写字段：所有配置项的控件登记在 sw（开关）/ ed（数字和文字）两张表里，按 {@link ConfigSchema}
 * 的字段表读写（{@link #readUi} / {@link #applyToUi}），新增配置项时在 RecConfig.java + tools/field_meta.txt 里加一行，
 * 跑 tools/gen_schema.py，首页就会自动多出一行（没有手写行的项落在对应分组里，按父子关系缩进在父开关下面）。
 * 没有控件的字段保持 {@link #cur} 里的值，不会被悄悄重置成默认值。
 */
public class MainActivity extends Activity {
    private final LinkedHashMap<String, Switch> sw = new LinkedHashMap<>();
    private final LinkedHashMap<String, EditText> ed = new LinkedHashMap<>();
    private final HashMap<String, View> rows = new HashMap<>();
    private final HashSet<String> placed = new HashSet<>();
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> sceneChips = new LinkedHashMap<>();
    private Ui.Seg segRange, segThread;
    private EditText etPkgs, etPaste;
    private TextView tvStatus, btnPush, btnApplyOnly, tvSceneDesc, tvRangeDesc, tvEffective, tvTaskInfo, chHook, chOff;
    private LinearLayout kwBox;
    /** 当前基准配置：没有控件的字段保持这里的值；applyToUi 时更新。 */
    private RecConfig cur = new RecConfig();
    private boolean loading;
    private String lastWarnSig = "";
    /** 已有记录里出现过的类名（记录页读取时学到），给方法追踪的类名查表用。 */
    private volatile java.util.Set<String> knownClasses;
    private android.text.TextWatcher tw;

    private static final String[] SCENE = {"text", "icons", "state", "all", "trace", "statehook", "viewtree", "tap"};
    private static final String[] SCENE_LABEL = {"文字", "图标", "状态", "全部", "方法追踪", "状态钩子", "视图树", "点哪查哪"};
    private static final String[] SCENE_GROUP = {"content", "content", "content", "content", "trace", "trace", "view", "view"};
    private static final HashSet<String> MULTI = new HashSet<>(java.util.Arrays.asList("traceMethods", "watch", "excludeList", "keywords", "dumpPrefixes", "valueFilter", "viewProbe"));

    private static final String[][] HINT = {
            {"traceMethods", "androidx.compose.ui.text.MultiParagraph#paint-LG529CI"},
            {"watch", "com.a.B#foo:arg0.mAweme.userDigg"},
            {"threadFilter", "LegoHandler"},
            {"pageFilter", "FeedItemFragment"},
            {"viewFilter", "DiggLayout"},
            {"excludeList", "androidx.lifecycle.LiveData#considerNotify"},
            {"valueFilter", "01:37"},
            {"keywords", "关键字，逗号或换行分隔；正则模式每行一个，如 \\d\\d:\\d\\d"},
            {"dumpPrefixes", "androidx.compose.ui.text."},
            {"viewDumpKey", "ScrollMentionTextView,MentionTextView"},
            {"viewProbe", "每行一个类全名，如 com.ss.android.ugc.aweme.feed.widget.DiggAnimationView"},
    };

    private static String hint(String key) {
        for (String[] r : HINT) if (r[0].equals(key)) return r[1];
        return "";
    }

    // ------------------------------------------------------------ 折叠分组

    private final class Group {
        final String id;
        final LinearLayout body;
        final TextView head, badge;
        final String title;
        boolean open;

        Group(LinearLayout root, String id, String title, String desc, boolean open) {
            this.id = id;
            this.title = title;
            LinearLayout card = Ui.card(MainActivity.this);
            LinearLayout bar = Ui.horizontal(MainActivity.this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            head = Ui.tv(MainActivity.this, "", 15.5f, R.color.text, true);
            bar.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
            badge = Ui.badge(MainActivity.this, "默认", 0xFF7C8596);
            bar.addView(badge);
            bar.setClickable(true);
            bar.setPadding(0, Ui.dp(MainActivity.this, 4), 0, Ui.dp(MainActivity.this, 4));
            bar.setOnClickListener(v -> set(!this.open));
            card.addView(bar);
            body = Ui.vertical(MainActivity.this);
            if (desc != null && !desc.isEmpty()) {
                TextView d = Ui.tv(MainActivity.this, desc, 12.5f, R.color.text_sub, false);
                d.setLineSpacing(0, 1.1f);
                d.setPadding(0, Ui.dp(MainActivity.this, 6), 0, Ui.dp(MainActivity.this, 4));
                body.addView(d);
            }
            card.addView(body);
            root.addView(card, Ui.lpM(MainActivity.this, -1, -2, 0, 0, 0, 10));
            groups.put(id, this);
            set(open);
        }

        void set(boolean o) {
            open = o;
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            head.setText((o ? "▾  " : "▸  ") + title);
        }

        void setCount(int n) {
            Ui.setBadge(badge, n == 0 ? "默认" : "已改 " + n + " 项", n == 0 ? 0xFF7C8596 : 0xFF4C6FFF);
        }
    }

    private void openGroup(String id) {
        Group g = groups.get(id);
        if (g != null) g.set(true);
    }

    private void sub(LinearLayout parent, String title, String desc) {
        parent.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 10, 0, 10));
        parent.addView(Ui.tv(this, title, 14.5f, R.color.text, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
            d.setLineSpacing(0, 1.1f);
            d.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            parent.addView(d);
        }
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("TextRec");
        tw = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(android.text.Editable e) { onChanged(); }
        };

        LinearLayout page = Ui.vertical(this);
        page.setBackgroundColor(Ui.col(this, R.color.bg));
        Ui.Bar bar = Ui.topBar(this, "TextRec", "只读记录：屏幕上的文字 / 图标是谁设置的、谁画的", false);
        page.addView(bar.root);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout root = Ui.vertical(this);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 28));
        sv.addView(root);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildTargetCard(root);
        buildScenes(root);
        buildPasteGroup(root);
        root.addView(Ui.section(this, "设置", "按要做的事分组，默认折叠；标题右边是这一组里和默认值不同的项数。"));
        buildContentGroup(root);
        buildRangeGroup(root);
        buildTraceGroup(root);
        buildFilterGroup(root);
        buildViewGroup(root);
        buildLimitsGroup(root);
        buildOtherGroup(root);
        buildEffectiveGroup(root);

        LinearLayout bottom = Ui.bottomBar(this);
        TextView btnView = Ui.button(this, "查看记录", Ui.OUTLINE, v -> {
            save(false, false);
            startActivity(new Intent(this, RecordsActivity.class));
        });
        bottom.addView(btnView, new LinearLayout.LayoutParams(0, -2, 1f));
        btnPush = Ui.button(this, "应用并重新记录", Ui.PRIMARY, v -> save(true, true));
        LinearLayout.LayoutParams lpPush = Ui.lpM(this, 0, -2, 10, 0, 0, 0);
        lpPush.weight = 1.5f;
        bottom.addView(btnPush, lpPush);
        page.addView(Ui.divider(this));
        page.addView(bottom);

        setContentView(page);
        load();
        reloadKnown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadKnown(); // 从记录页回来时，那边可能刚学到新的类名
    }

    private void reloadKnown() {
        new Thread(() -> knownClasses = KnownNames.load(new java.io.File(getFilesDir(), "known_classes.txt"))).start();
    }

    private ConfigCheck.Options checkOptions(boolean tasksElsewhere) {
        return new ConfigCheck.Options().known(knownClasses).tasksElsewhere(tasksElsewhere);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(false, false); // 离开首页就顺手存一下本地设置（不推送）
    }

    // ------------------------------------------------------------ 通用：一个配置项一行

    private void indent(View row, int depth) {
        if (depth > 0) row.setPadding(Ui.dp(this, 14 * depth), row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom());
    }

    /** 放一个配置项（按类型出开关 / 数字框 / 文字框），然后把它的子项缩进放在下面。 */
    private void addField(LinearLayout parent, String key, int depth) {
        if (placed.contains(key)) return;
        ConfigSchema.F f = ConfigSchema.get(key);
        if (f == null) return;
        placed.add(key);
        String title = f.title.isEmpty() ? f.desc : f.title; // 标题和说明只在 tools/field_meta.txt 里写一份
        String desc = f.help;
        String tail = "〔" + key + (f.hasRange ? "，" + f.min + "～" + f.max : "") + "〕";
        desc = desc.isEmpty() ? tail : desc + "  " + tail;
        if (f.type.equals("bool")) {
            Ui.Sw x = Ui.switchRow(this, title, desc);
            indent(x.row, depth);
            sw.put(key, x.sw);
            rows.put(key, x.row);
            x.sw.setOnCheckedChangeListener((b, on) -> onChanged());
            parent.addView(x.row);
            parent.addView(Ui.divider(this));
        } else if (f.type.equals("int")) {
            addNumber(parent, key, title, desc, depth);
        } else if (f.type.equals("string")) {
            addText(parent, key, title, desc, depth);
        } else {
            return;
        }
        for (ConfigSchema.F c : ConfigSchema.ALL) if (ConfigSchema.treeParent(c).equals(key)) addField(parent, c.key, depth + 1);
    }

    private void addNumber(LinearLayout parent, String key, String title, String desc, int depth) {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14 * depth), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout left = Ui.vertical(this);
        left.addView(Ui.tv(this, title, 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, 0);
        left.addView(d);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText e = Ui.field(this, "", false, true);
        row.addView(e, Ui.lpM(this, Ui.dp(this, 84), -2, 12, 0, 0, 0));
        ed.put(key, e);
        rows.put(key, row);
        e.addTextChangedListener(tw);
        parent.addView(row);
    }

    private void addText(LinearLayout parent, String key, String title, String desc, int depth) {
        LinearLayout col = Ui.vertical(this);
        col.setPadding(Ui.dp(this, 14 * depth), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(Ui.tv(this, title, 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        col.addView(d);
        EditText e = Ui.field(this, hint(key), MULTI.contains(key), false);
        if (key.equals("traceMethods")) e.setMinLines(5);
        col.addView(e);
        ed.put(key, e);
        rows.put(key, col);
        e.addTextChangedListener(tw);
        parent.addView(col);
    }

    private void note(LinearLayout parent, String text) {
        TextView t = Ui.tv(this, text, 12.5f, R.color.text_sub, false);
        t.setLineSpacing(0, 1.1f);
        t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        parent.addView(t);
    }

    // ------------------------------------------------------------ 目标 App

    private void buildTargetCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.tv(this, "目标 App", 16, R.color.text, true));
        TextView hint = Ui.tv(this, "要记录哪个 App 的包名，多个用逗号分隔，默认是抖音。前提：已在 LSPosed 里启用本模块并勾选作用域。", 12.5f, R.color.text_sub, false);
        hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        hint.setLineSpacing(0, 1.1f);
        card.addView(hint);
        etPkgs = Ui.field(this, Actions.DEFAULT_PKG, false, false);
        card.addView(etPkgs);
        card.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 10, 0, 0));
        addField(card, "enabled", 0);
        TextView flow = Ui.tv(this, "用法：① 选下面「我想找什么」② 点底部「应用并重新记录」③ 强杀目标 App 再打开，操作想研究的页面 ④ 回来点「查看记录」", 12.5f, R.color.text_sub, false);
        flow.setLineSpacing(0, 1.15f);
        card.addView(flow);
        btnApplyOnly = Ui.button(this, "只应用设置，不清空记录", Ui.TEXT, v -> save(true, false));
        btnApplyOnly.setMinHeight(Ui.dp(this, 40));
        card.addView(btnApplyOnly, Ui.lpM(this, -2, -2, -8, 6, 0, 0));
        tvStatus = Ui.note(this, "", false);
        tvStatus.setTextIsSelectable(true);
        tvStatus.setVisibility(View.GONE);
        card.addView(tvStatus, Ui.lpM(this, -1, -2, 0, 4, 0, 0));
        root.addView(card, Ui.lpM(this, -1, -2, 0, 4, 0, 0));
    }

    // ------------------------------------------------------------ 我想找什么

    private void buildScenes(LinearLayout root) {
        root.addView(Ui.section(this, "我想找什么", "选一个，自动设好相关开关（只改这个场景用到的几项，别的不动），再到下面对应的分组里细调。"));
        LinearLayout card = Ui.card(this);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = Ui.horizontal(this);
            for (int k = 0; k < 4; k++) {
                final int idx = row * 4 + k;
                TextView chip = Ui.chip(this, SCENE_LABEL[idx], false, v -> pickScene(idx));
                chip.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
                sceneChips.put(SCENE[idx], chip);
                line.addView(chip, Ui.lpM(this, 0, -2, k == 0 ? 0 : 6, 0, 0, 0));
                ((LinearLayout.LayoutParams) chip.getLayoutParams()).weight = 1f;
            }
            card.addView(line, Ui.lpM(this, -1, -2, 0, row == 0 ? 0 : 8, 0, 0));
        }
        tvSceneDesc = Ui.tv(this, "", 12.5f, R.color.text_sub, false);
        tvSceneDesc.setLineSpacing(0, 1.15f);
        tvSceneDesc.setPadding(Ui.dp(this, 2), Ui.dp(this, 10), 0, 0);
        card.addView(tvSceneDesc);
        root.addView(card);
    }

    private void pickScene(int idx) {
        RecConfig c = readUi(cur.epoch);
        int n = Presets.applyTo(c, SCENE[idx]);
        applyToUi(c);
        openGroup(SCENE_GROUP[idx]);
        Presets.P p = Presets.get(SCENE[idx]);
        tvSceneDesc.setText(p.title + "\n" + p.desc + "\n已调整 " + n + " 项，点底部「应用并重新记录」后生效。");
        toast("已选「" + SCENE_LABEL[idx] + "」");
    }

    private void refreshScenes() {
        if (tvSceneDesc == null) return;
        RecConfig c = readUi(cur.epoch);
        Presets.P best = Presets.best(c, "scene"); // 只亮最贴近的一个（statehook 比 trace 多设了几项，满足它时不再同时亮 trace）
        for (int i = 0; i < SCENE.length; i++) Ui.setChip(sceneChips.get(SCENE[i]), best != null && best.name.equals(SCENE[i]), SCENE_LABEL[i]);
        if (chHook != null) Ui.setChip(chHook, Presets.matches(c, "hook"), "找钩子（最窄）");
        if (chOff != null) Ui.setChip(chOff, Presets.matches(c, "nofilter"), "关掉全部过滤");
        if (best != null) tvSceneDesc.setText(best.title + "\n" + best.desc);
        else tvSceneDesc.setText("当前是自定义组合（和上面任何一个场景都不完全相同）。");
    }

    private LinearLayout filterChips() {
        LinearLayout line = Ui.horizontal(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView lb = Ui.tv(this, "快速选择", 12.5f, R.color.text_hint, false);
        line.addView(lb, Ui.lpM(this, -2, -2, 0, 0, 10, 0));
        TextView hook = Ui.chip(this, "找钩子（最窄）", false, v -> applyFilterPreset("hook", "已选「找钩子」：触摸后 1.5 秒内才记，排除通用基础设施，压低高频方法"));
        TextView off = Ui.chip(this, "关掉全部过滤", false, v -> applyFilterPreset("nofilter", "已关掉全部采集过滤"));
        line.addView(hook, Ui.lpM(this, -2, -2, 0, 0, 8, 0));
        line.addView(off);
        if (chHook == null) {
            chHook = hook;
            chOff = off;
        }
        return line;
    }

    private void applyFilterPreset(String name, String msg) {
        RecConfig c = readUi(cur.epoch);
        Presets.applyTo(c, name);
        applyToUi(c);
        toast(msg + "，点底部按钮后生效");
    }

    // ------------------------------------------------------------ 导入 / 导出

    private void buildPasteGroup(LinearLayout root) {
        root.addView(Ui.section(this, "导入 / 导出", null));
        Group g = new Group(root, "paste", "粘贴配置和任务清单（给 AI 用的入口）", null, true);
        LinearLayout card = g.body;
        note(card, "一个框装两样：采集配置（推荐「块格式」：[config] / [traceMethods] / [keywords] 分段，值里不用任何转义）和任务清单（[tasks] 段，名称|提示|秒数）。"
                + "旧的 key=value 写法仍然支持。可以写 preset=名字 套预设。粘贴后会先校验，错在哪一行会说清楚。");
        etPaste = Ui.field(this, "[config]\npreset = trace\n\n[traceMethods]\n类全名#方法名\n\n[tasks]\n暂停|点一下视频暂停，停 3 秒|", true, false);
        etPaste.setMinLines(5);
        card.addView(etPaste);
        LinearLayout row = Ui.horizontal(this);
        row.addView(Ui.button(this, "回填并保存", Ui.PRIMARY, v -> applyPaste(false)), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView open = Ui.button(this, "打开浮窗", Ui.OUTLINE, v -> applyPaste(true));
        row.addView(open, Ui.lpM(this, 0, -2, 10, 0, 0, 0));
        ((LinearLayout.LayoutParams) open.getLayoutParams()).weight = 1f;
        card.addView(row, Ui.lpM(this, -1, -2, 0, 10, 0, 0));
        tvTaskInfo = Ui.tv(this, "", 12.5f, R.color.text_hint, false);
        tvTaskInfo.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, 0);
        card.addView(tvTaskInfo);
        TextView c1 = Ui.button(this, "复制当前配置（块格式）", Ui.TEXT, v -> copyText("TextRec 当前配置", PasteBlocks.export(readUi(cur.epoch), savedTasks()), "已复制当前配置，可发给 AI 或自己留存"));
        card.addView(c1, Ui.lpM(this, -2, -2, -8, 4, 0, 0));
        TextView c2 = Ui.button(this, "复制《写配置手册》（发给 AI 先读）", Ui.TEXT, v -> copyAsset("ai_manual.md", "TextRec 写配置手册"));
        card.addView(c2, Ui.lpM(this, -2, -2, -8, 0, 0, 0));
        TextView c3 = Ui.button(this, "复制完整参考（原理 / 全部功能）", Ui.TEXT, v -> copyAsset("ai_guide.md", "TextRec 完整参考"));
        card.addView(c3, Ui.lpM(this, -2, -2, -8, 0, 0, 0));
    }

    private String savedTasks() {
        return getSharedPreferences(Actions.PREFS, MODE_PRIVATE).getString(GuideService.PREF_TASKS, "");
    }

    private void refreshTaskInfo() {
        if (tvTaskInfo == null) return;
        String t = savedTasks();
        int n = t.trim().isEmpty() ? 0 : GuideService.parse(t).size();
        tvTaskInfo.setText(n == 0 ? "任务清单：还没保存（浮窗会用默认清单）" : "任务清单：已保存 " + n + " 件");
    }

    private void copyText(String label, String text, String msg) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
        toast(msg);
    }

    /** 把 assets 里的文档复制到剪贴板：粘给任何窗口里的 AI，它不用读源码就知道怎么写配置。 */
    private void copyAsset(String name, String label) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            String text = bo.toString("UTF-8");
            copyText(label, text, "已复制「" + label + "」（" + text.length() + " 字），粘贴发给 AI 即可");
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    /** 处理粘贴框：先校验，再拆开配置和任务清单各自生效；openGuide = true 时接着打开浮窗。 */
    private void applyPaste(final boolean openGuide) {
        final String text = etPaste.getText().toString();
        // 粘贴里没带任务清单但之前已存过：浮窗会用已存的，不报「没有任务清单」
        List<ConfigCheck.Issue> issues = ConfigCheck.checkPaste(text, checkOptions(!savedTasks().trim().isEmpty()));
        if (ConfigCheck.hasError(issues)) {
            showIssues("配置有 " + ConfigCheck.count(issues, true) + " 处错误，没有回填", issues, text, "忽略错误，仍然回填", new Runnable() {
                @Override
                public void run() {
                    doApplyPaste(openGuide);
                }
            });
            return;
        }
        doApplyPaste(openGuide);
        if (!issues.isEmpty()) showIssues("已回填，但有 " + issues.size() + " 条提醒", issues, text, null, null);
    }

    /** 列出校验问题：错误在前、警告在后，每条带行号和原因。force != null 时多一个「仍然继续」按钮；pasteText != null 时可复制「错误报告」给 AI。 */
    private void showIssues(String title, final List<ConfigCheck.Issue> issues, final String pasteText, String forceLabel, final Runnable force) {
        TextView body = Ui.tv(this, ConfigCheck.format(issues), 13, R.color.text, false);
        body.setLineSpacing(0, 1.2f);
        body.setTextIsSelectable(true);
        Ui.Dlg d = new Ui.Dlg(this, title, "第几行指粘贴框里的行号；写法见《写配置手册》").body(body, true);
        if (force != null) d.button(forceLabel, false, force);
        d.button("复制给 AI", false, new Runnable() {
            @Override
            public void run() {
                copyText("TextRec 校验报告", ConfigCheck.reportForAi(pasteText, issues), "已复制错误报告，粘给 AI，它会只改被点到的行");
            }
        });
        d.button(force != null ? "返回修改" : "知道了", true, null);
        d.show();
    }

    private void doApplyPaste(boolean openGuide) {
        String[] parts = RecConfig.splitPaste(etPaste.getText().toString());
        StringBuilder msg = new StringBuilder();
        if (!parts[0].trim().isEmpty()) {
            applyToUi(RecConfig.fromText(parts[0])); // 只回填下面的开关 / 输入框，不推送；没写的字段是默认值，不是当前表单的值
            save(false, false);
            msg.append("配置已回填（还没推送，检查后点下面的按钮）");
        }
        if (!parts[1].trim().isEmpty()) {
            int n = GuideService.parse(parts[1]).size();
            getSharedPreferences(Actions.PREFS, MODE_PRIVATE).edit().putString(GuideService.PREF_TASKS, parts[1]).apply();
            msg.append(msg.length() > 0 ? "；" : "").append("任务清单已保存（").append(n).append(" 件）");
            refreshTaskInfo();
        }
        if (msg.length() == 0) {
            String saved = savedTasks();
            msg.append(saved.trim().isEmpty() ? "框里没有内容；浮窗会用默认任务清单" : "框里没有内容；浮窗会用上次保存的任务清单");
        }
        if (openGuide) {
            if (GuideService.isRunning() && !parts[1].trim().isEmpty()) msg.append("；浮窗已开着，要用新清单请先在浮窗上点 ✕ 再打开");
            toast(msg.toString());
            GuideService.open(this);
        } else {
            toast(msg.toString());
        }
    }

    // ------------------------------------------------------------ 分组

    private void buildContentGroup(LinearLayout root) {
        Group g = new Group(root, "content", "记录内容（文字 / 图标 / 状态）",
                "每一项都是只读观察，不会修改目标 App。开得越多记录越全，目标 App 也越卡。缩进的是子项，上面的总开关关着时子项不起作用。", false);
        for (String k : new String[]{"traceSet", "traceDraw", "traceColor", "autoParams", "traceIcons", "traceSetOnly", "traceState"}) addField(g.body, k, 0);
    }

    private void buildRangeGroup(LinearLayout root) {
        Group g = new Group(root, "range", "记录范围（全部文字 / 只记关键字）", null, false);
        segRange = Ui.segmented(this, new String[]{"全部文字", "只记关键字"}, 0, i -> {
            updateRange();
            onChanged();
        });
        g.body.addView(segRange.root, Ui.lpM(this, -1, -2, 0, 6, 0, 0));
        tvRangeDesc = Ui.tv(this, "", 12.5f, R.color.text_sub, false);
        tvRangeDesc.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, 0);
        tvRangeDesc.setLineSpacing(0, 1.1f);
        g.body.addView(tvRangeDesc);
        kwBox = Ui.vertical(this);
        addField(kwBox, "keywords", 0);
        addField(kwBox, "regex", 0);
        g.body.addView(kwBox);
        placed.add("keywordMode");
    }

    private void updateRange() {
        boolean kw = segRange.sel == 1;
        kwBox.setVisibility(kw ? View.VISIBLE : View.GONE);
        tvRangeDesc.setText(kw ? "只记录文字命中下面关键字的位置，对目标 App 影响小。"
                : "记录屏幕上出现的所有文字。会拖慢目标 App，只建议调试时使用。");
    }

    private void buildTraceGroup(LinearLayout root) {
        Group g = new Group(root, "trace", "方法追踪",
                "每行写一个「类全名#方法名」，记录它每次被调用时的参数、返回值和调用者。构造方法写 类#<init>；写 类#* 追踪整个类；// 开头是注释。"
                        + "行尾可加选项（空格分隔）：@count 只计数；@args 只记参数 / 返回值；@stack=4 单独栈深；@every=10 单独抽样间隔；@seq 按先后记序列。"
                        + "类名要逐字从导出的记录里复制，不要自己编。", false);
        addField(g.body, "traceMethods", 0);
        addField(g.body, "watch", 0);
        addField(g.body, "traceExclusive", 0);
        g.body.addView(filterChips(), Ui.lpM(this, -1, -2, 0, 8, 0, 4));
        sub(g.body, "只对方法追踪生效的过滤", null);
        for (String k : new String[]{"excludeList", "valueFilter", "excludeInfra", "valueClock", "quietCount"}) addField(g.body, k, 0);
        sub(g.body, "字段快照", "找某个对象里有哪些字段、进度 / 时长存在哪");
        addField(g.body, "traceDump", 0);
        sub(g.body, "数据树与观察点", "值随任务变化的节点（✦）往往就是判断状态的字段；需要「应用并重新记录」并强杀目标 App 重开才生效");
        addField(g.body, "treeOn", 0);
        sub(g.body, "标注", "给记录加语义标签，找钩子更快更准");
        for (String k : new String[]{"annotFrames", "annotOwners", "annotSweep"}) addField(g.body, k, 0);
        sub(g.body, "链路追踪", null);
        addField(g.body, "chainOn", 0);
        addField(g.body, "traceStackDepth", 0);
    }

    private void buildFilterGroup(LinearLayout root) {
        Group g = new Group(root, "filter", "采集过滤（挡掉不相关的）",
                "在目标 App 里、抓调用栈之前就判断，不符合的不记，也更省资源。全部留空 / 关掉 = 不过滤。记录页顶部会写每个条件挡掉了多少次，记录不够就放宽它。", false);
        addField(g.body, "windowMs", 0);
        LinearLayout col = Ui.vertical(this);
        col.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(Ui.tv(this, "线程", 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, "界面更新在主线程，点赞这类后台逻辑多在后台线程；选「只记」就把另一类挡掉。〔threadMode〕", 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        col.addView(d);
        segThread = Ui.segmented(this, new String[]{"全部", "只主线程", "只后台线程"}, 0, i -> onChanged());
        col.addView(segThread.root);
        g.body.addView(col);
        placed.add("threadMode");
        for (String k : new String[]{"threadFilter", "pageFilter", "viewFilter"}) addField(g.body, k, 0);
    }

    private void buildViewGroup(LinearLayout root) {
        Group g = new Group(root, "view", "视图与时间线",
                "视图树导出、点哪查哪、时间线、任务外是否记录。都是只读观察。", false);
        sub(g.body, "视图树", null);
        addField(g.body, "viewDump", 0);
        addField(g.body, "viewTap", 0);
        addField(g.body, "viewProbe", 0);
        sub(g.body, "时间线与任务", null);
        addField(g.body, "tlOn", 0);
        addField(g.body, "recOutside", 0);
    }

    private void buildLimitsGroup(LinearLayout root) {
        Group g = new Group(root, "limits", "记录容量与抓栈力度", "一般不用改。这些参数控制抓取调用栈的力度和记录容量；每种记录各自一个文件、各自有上限，一种灌满不会挤掉别的。", false);
        for (String k : new String[]{"stackDepth", "perKeyStacks", "sampleEvery", "stacksPerSec", "flushSec", "noiseQuota", "splitFiles"}) addField(g.body, k, 0);
        TextView reset = Ui.button(this, "恢复默认参数", Ui.TEXT, v -> {
            RecConfig c = readUi(cur.epoch);
            RecConfig d = new RecConfig();
            for (ConfigSchema.F f : ConfigSchema.ALL) {
                if (!f.group.equals("limits")) continue;
                try {
                    java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                    fl.set(c, fl.get(d));
                } catch (Throwable ignored) {
                }
            }
            applyToUi(c);
        });
        reset.setMinHeight(Ui.dp(this, 40));
        g.body.addView(reset, Ui.lpM(this, -2, -2, -8, 4, 0, 0));
        sub(g.body, "每种记录各自的上限 / 栈深", "0 = 用上面的调用栈保存层数");
        for (ConfigSchema.F f : ConfigSchema.ALL) if (f.group.equals("limits") && (f.key.startsWith("cap") || f.key.startsWith("depth"))) addField(g.body, f.key, 0);
    }

    /** 兜底：schema 里有、上面没放的字段（新增配置项时自动出现在这里，不会漏）。 */
    private void buildOtherGroup(LinearLayout root) {
        ArrayList<String> rest = new ArrayList<>();
        for (ConfigSchema.F f : ConfigSchema.ALL) if (!f.group.equals("internal") && !placed.contains(f.key)) rest.add(f.key);
        if (rest.isEmpty()) return;
        Group g = new Group(root, "other", "其他配置项", "没有归到上面分组的项（按字段表自动生成）。", false);
        for (String k : rest) addField(g.body, k, 0);
    }

    // ------------------------------------------------------------ 实际生效（回读）

    private void buildEffectiveGroup(LinearLayout root) {
        root.addView(Ui.section(this, "确认", null));
        Group g = new Group(root, "effective", "目标 App 实际生效的配置", null, false);
        note(g.body, "配置检查只能查「写得对不对」，查不了「类存不存在」。应用后强杀目标 App 重开，再点下面的按钮：读回目标 App 里真正在用的配置，"
                + "和首页设置对比哪些开关被自动改了（如方法追踪独占），并列出每个钩点有没有装上。");
        TextView btn = Ui.button(this, "读取目标 App 的实际状态", Ui.TONAL, v -> readStatus());
        g.body.addView(btn, Ui.lpM(this, -2, -2, 0, 4, 0, 6));
        tvEffective = Ui.note(this, "还没有读取。", false);
        tvEffective.setTextIsSelectable(true);
        g.body.addView(tvEffective);
    }

    private void readStatus() {
        SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        final List<String> list = RecConfig.splitLines(etPkgs.getText().toString());
        if (list.isEmpty()) {
            tvEffective.setText("请先填目标 App 包名");
            return;
        }
        final String pushedText = sp.getString("pushed", sp.getString("cfg", ""));
        tvEffective.setText("正在读取…（需要 root）");
        new Thread(() -> {
            StringBuilder raw = new StringBuilder();
            for (String pkg : list) {
                if (!RootShell.validPkg(pkg)) continue;
                raw.append(RootShell.pullStatus(pkg).out); // 只读几个很小的状态文件，不拉整份记录
            }
            List<TargetStatus.Proc> ps = TargetStatus.parse(raw.toString());
            final String text = TargetStatus.describe(ps, RecConfig.fromText(pushedText), System.currentTimeMillis());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                tvEffective.setText(text.trim());
            });
        }).start();
    }

    // ------------------------------------------------------------ 读取 / 保存

    private void onChanged() {
        if (loading) return;
        refreshUi();
    }

    /** 这个开关自己开着吗（只看它自己的值，不看它的前提）：有控件看控件，没有控件看当前基准配置。 */
    private boolean ownOn(String key) {
        if (key.equals("keywordMode")) return segRange != null && segRange.sel == 1;
        Switch s = sw.get(key);
        if (s != null) return s.isChecked();
        EditText e = ed.get(key);
        if (e != null) return Relations.isOn(ConfigSchema.get(key), e.getText().toString());
        try {
            return Relations.isOn(ConfigSchema.get(key), String.valueOf(RecConfig.class.getField(key).get(cur)));
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void refreshUi() {
        if (segRange == null) return;
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.terms.length == 0) continue;
            View row = rows.get(f.key);
            if (row == null) continue;
            row.setAlpha(FormModel.dead(f, this::ownOn) ? 0.45f : 1f); // 前提不满足（含祖先）就变灰
        }
        refreshScenes();
        refreshBadges();
    }

    private void refreshBadges() {
        java.util.Map<String, Integer> n = FormModel.changedCounts(readUi(cur.epoch));
        for (Group g : groups.values()) {
            if (g.id.equals("paste") || g.id.equals("effective")) continue;
            Integer k = n.get(g.id);
            g.setCount(k == null ? 0 : k);
        }
    }

    private void load() {
        SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        etPkgs.setText(sp.getString("pkgs", Actions.DEFAULT_PKG));
        RecConfig c = Actions.loadCfg(sp);
        if (sp.getInt("ver", 0) < 2) {
            // 1.0 的默认值 50 太小（冷启动会丢文字），升级时迁移成 1000
            if (c.stacksPerSec == 50) c.stacksPerSec = 1000;
            sp.edit().putInt("ver", 2).putString("cfg", c.toText()).apply();
        }
        applyToUi(c);
        refreshTaskInfo();
    }

    /** 把一份 RecConfig 回填进表单控件，不碰 SharedPreferences、不推送——load() 和「粘贴配置」共用这一步。 */
    private void applyToUi(RecConfig c) {
        loading = true;
        try {
            cur = RecConfig.fromText(c.toText());
            cur.epoch = c.epoch;
            java.util.HashSet<String> keys = new java.util.HashSet<>(sw.keySet());
            keys.addAll(ed.keySet());
            for (java.util.Map.Entry<String, String> e : FormModel.toValues(c, keys).entrySet()) {
                Switch s = sw.get(e.getKey());
                if (s != null) {
                    s.setChecked(e.getValue().equals("true"));
                    continue;
                }
                EditText x = ed.get(e.getKey());
                if (x != null) x.setText(e.getValue());
            }
            segRange.select(c.keywordMode ? 1 : 0);
            segThread.select(Math.max(0, Math.min(2, c.threadMode)));
            updateRange();
            // 设置过方法追踪的，默认把方法追踪组展开，免得以为没生效
            if (!c.traceMethods.trim().isEmpty()) openGroup("trace");
        } finally {
            loading = false;
        }
        refreshUi();
    }

    /** 从控件读出一份配置：没有控件的字段保持 cur 里的值（转换规则在 {@link FormModel}，有单元测试）。 */
    private RecConfig readUi(long epoch) {
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, Switch> e : sw.entrySet()) values.put(e.getKey(), e.getValue().isChecked() ? "true" : "false");
        for (java.util.Map.Entry<String, EditText> e : ed.entrySet()) values.put(e.getKey(), e.getValue().getText().toString());
        return FormModel.read(cur, values, segRange.sel == 1, segThread.sel, epoch);
    }

    private static int parse(EditText e, int d) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Throwable t) {
            return d;
        }
    }

    private void setStatus(String s) {
        tvStatus.setText(s);
        tvStatus.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private boolean skipCheck;

    private void save(final boolean push, final boolean reset) {
        SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        long epoch = reset ? System.currentTimeMillis() : Actions.loadCfg(sp).epoch;
        final RecConfig c = readUi(epoch);

        if (push && !skipCheck) {
            // 整份配置过一遍和粘贴框同一套检查（写法 + 父子开关 + 排除名单 + 任务清单配套……）
            // 没保存过任务清单时浮窗用默认清单，所以任务清单总是有的
            final List<ConfigCheck.Issue> tl = ConfigCheck.checkForm(c, savedTasks(), checkOptions(true));
            if (!tl.isEmpty()) {
                final boolean fp = push, fr = reset;
                if (ConfigCheck.hasError(tl)) {
                    showIssues("配置有 " + ConfigCheck.count(tl, true) + " 处错误，没有应用", tl, PasteBlocks.export(c, savedTasks()), "忽略错误，仍然应用", new Runnable() {
                        @Override
                        public void run() {
                            skipCheck = true;
                            try {
                                save(fp, fr);
                            } finally {
                                skipCheck = false;
                            }
                        }
                    });
                    return;
                }
                String sig = ConfigCheck.format(tl);
                if (!sig.equals(lastWarnSig)) { // 同一组提醒只弹一次，免得每次应用都打断
                    lastWarnSig = sig;
                    showIssues("配置有 " + tl.size() + " 条提醒（已继续应用）", tl, PasteBlocks.export(c, savedTasks()), null, null);
                }
            }
        }
        if (c.keywordMode && c.regex) {
            for (String k : RecConfig.split(c.keywords, true)) {
                try {
                    Pattern.compile(k);
                } catch (Throwable t) {
                    setStatus("正则无效，未保存：" + k);
                    return;
                }
            }
        }
        final String pkgs = etPkgs.getText().toString().trim();
        sp.edit().putString("cfg", c.toText()).putString("pkgs", pkgs).apply();
        if (!push) return;

        final List<String> list = RecConfig.splitLines(pkgs);
        if (list.isEmpty()) {
            setStatus("请先填目标 App 包名");
            return;
        }
        Ui.enable(btnPush, false);
        Ui.enable(btnApplyOnly, false);
        setStatus("正在推送…（首次会弹出 root 授权，请点允许）");
        new Thread(() -> {
            final String res = Actions.push(list, c, reset);
            if (res.contains("✓")) sp.edit().putString("pushed", c.toText()).apply();
            final String msg = res
                    + (reset ? "\n下一步：强杀目标 App 再重新打开，操作完回来点「查看记录」；想确认配置真的生效，点下面「目标 App 实际生效的配置」里的读取按钮。"
                    : "\n设置约 2 秒内在目标 App 里生效，不用重启；要记录冷启动阶段的文字，请强杀后重新打开。")
                    + (c.keywordMode ? "" : "\n当前是全部文字模式，会拖慢目标 App，只建议调试时使用。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Ui.enable(btnPush, true);
                Ui.enable(btnApplyOnly, true);
                setStatus(msg.trim());
            });
        }).start();
    }
}
