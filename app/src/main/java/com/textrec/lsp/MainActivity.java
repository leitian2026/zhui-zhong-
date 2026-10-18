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

import java.util.List;
import java.util.regex.Pattern;

/** 首页：流程说明 → 目标 App → 记录内容 → 记录范围 → 高级；底部固定「查看记录」和「应用并重新记录」。 */
public class MainActivity extends Activity {
    private EditText etPkgs, etKeywords, etDepth, etPerKey, etSample, etPerSec, etMax, etMaxSetOnly, etTrace;
    private Switch swEnabled, swRegex, swSet, swDraw, swColor, swIcons, swAuto, swSetOnly, swState;
    // 采集过滤：开关 / 分段选择
    private Ui.Seg segWindow, segThread;
    private Switch swWindowAll, swInfra, swClock, swQuiet;
    private static final int[] WINDOW_MS = {0, 1500, 3000, 5000};
    // 手写的过滤条件（可选，放在「高级」里）
    private EditText etThread, etPageF, etViewF, etExclude, etValue;
    private Ui.Seg segRange;
    /** 快速选择按钮：当前开关组合正好等于某个预设时高亮。 */
    private TextView chTextOnly, chAll, chTraceOnly, chHook, chOff;
    private LinearLayout kwBox, advBody;
    private TextView advHeader, rangeDesc, tvStatus, btnPush, btnReset, btnApplyOnly;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("TextRec");

        LinearLayout page = Ui.vertical(this);
        page.setBackgroundColor(Ui.col(this, R.color.bg));
        Ui.Bar bar = Ui.topBar(this, "TextRec", "只读记录：屏幕上的文字 / 图标是谁设置的、谁画的", false);
        bar.actions.addView(Ui.iconBtn(this, R.drawable.ic_code, "粘贴配置", v -> showPasteConfig()));
        page.addView(bar.root);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout root = Ui.vertical(this);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 28));
        sv.addView(root);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildFlowCard(root);
        buildTargetCard(root);
        buildContentCard(root);
        buildRangeCard(root);
        buildFilterCard(root);
        buildAdvancedCard(root);

        // ---- 底部固定操作栏
        LinearLayout bottom = Ui.bottomBar(this);
        TextView btnView = Ui.button(this, "查看记录", Ui.OUTLINE, v -> {
            save(false, false);
            startActivity(new Intent(this, RecordsActivity.class));
        });
        bottom.addView(btnView, new LinearLayout.LayoutParams(0, -2, 1f));
        btnReset = Ui.button(this, "应用并重新记录", Ui.PRIMARY, v -> save(true, true));
        LinearLayout.LayoutParams lpReset = Ui.lpM(this, 0, -2, 10, 0, 0, 0);
        lpReset.weight = 1.5f;
        bottom.addView(btnReset, lpReset);
        page.addView(Ui.divider(this));
        page.addView(bottom);

        setContentView(page);
        wireChips();
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save(false, false); // 离开首页就顺手存一下本地设置（不推送）
    }

    // ------------------------------------------------------------ 各区块

    private void buildFlowCard(LinearLayout root) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.tv(this, "怎么用", 16, R.color.text, true));
        TextView pre = Ui.tv(this, "前提：已在 LSPosed 里启用本模块，并在作用域勾选目标 App。", 12.5f, R.color.text_sub, false);
        pre.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        card.addView(pre);
        card.addView(step(1, "应用设置", "点下方「应用并重新记录」：把设置推送给目标 App，并清掉旧记录（需要 root）"));
        card.addView(step(2, "重新打开目标 App", "先强杀目标 App 再启动，然后操作你想研究的页面；启动阶段的文字也会被记下"));
        card.addView(step(3, "查看记录", "回到这里点「查看记录」，搜文字或类名，点进条目看代码位置和可 hook 的方法"));

        btnApplyOnly = Ui.button(this, "只应用设置，不清空记录", Ui.TEXT, v -> save(true, false));
        btnApplyOnly.setMinHeight(Ui.dp(this, 40));
        card.addView(btnApplyOnly, Ui.lpM(this, -2, -2, -8, 4, 0, 0));

        tvStatus = Ui.note(this, "", false);
        tvStatus.setTextIsSelectable(true);
        tvStatus.setVisibility(View.GONE);
        card.addView(tvStatus, Ui.lpM(this, -1, -2, 0, 6, 0, 0));
        root.addView(card);
    }

    private View step(int n, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        TextView num = Ui.tv(this, String.valueOf(n), 12.5f, R.color.on_primary_container, true);
        num.setGravity(Gravity.CENTER);
        num.setBackground(Ui.rect(Ui.col(this, R.color.primary_container), Ui.dp(this, 12), 0, 0));
        row.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        LinearLayout col = Ui.vertical(this);
        col.setPadding(Ui.dp(this, 12), 0, 0, 0);
        col.addView(Ui.tv(this, title, 14.5f, R.color.text, true));
        TextView d = Ui.tv(this, desc, 12.5f, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 1), 0, 0);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private void buildTargetCard(LinearLayout root) {
        root.addView(Ui.section(this, "目标 App", null));
        LinearLayout card = Ui.card(this);
        card.addView(Ui.tv(this, "包名", 14.5f, R.color.text, true));
        TextView hint = Ui.tv(this, "要记录哪个 App。多个包名用逗号分隔，默认是抖音。", 12.5f, R.color.text_sub, false);
        hint.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        card.addView(hint);
        etPkgs = Ui.field(this, Actions.DEFAULT_PKG, false, false);
        card.addView(etPkgs);
        card.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 12, 0, 0));
        Ui.Sw en = Ui.switchRow(this, "启用记录", "关闭后目标 App 暂停记录，不会再产生新的记录");
        swEnabled = en.sw;
        card.addView(en.row);
        root.addView(card);
    }

    private void buildContentCard(LinearLayout root) {
        root.addView(Ui.section(this, "记录哪些内容", "每一项都是只读观察，不会修改目标 App。开得越多，记录越全，目标 App 也越卡。"));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER_VERTICAL);
        presets.addView(Ui.tv(this, "快速选择", 12.5f, R.color.text_hint, false), Ui.lpM(this, -2, -2, 4, 0, 10, 0));
        chTextOnly = Ui.chip(this, "只记文字（推荐）", false, v -> {
            swIcons.setChecked(false);
            swSetOnly.setChecked(false);
            swState.setChecked(false);
            swSet.setChecked(true);
            swDraw.setChecked(true);
            swColor.setChecked(true);
            swAuto.setChecked(true);
            etDepth.setText("12");
            toast("已选「只记文字」，点底部「应用并重新记录」后生效");
        });
        presets.addView(chTextOnly, Ui.lpM(this, -2, -2, 0, 0, 8, 0));
        chTraceOnly = Ui.chip(this, "只记方法追踪", false, v -> {
            // 一键：文字 / 图标 / 颜色 / 状态这些记录开关全部关掉，只剩「高级」里填的方法追踪
            swSet.setChecked(false);
            swDraw.setChecked(false);
            swColor.setChecked(false);
            swAuto.setChecked(false);
            swIcons.setChecked(false);
            swSetOnly.setChecked(false);
            swState.setChecked(false);
            setAdvanced(true); // 方法清单在「高级」里，顺手展开
            refreshChips();
            toast("已选「只记方法追踪」，到「高级」里填要追踪的方法；点底部「应用并重新记录」后生效");
        });
        presets.addView(chTraceOnly, Ui.lpM(this, -2, -2, 0, 0, 8, 0));
        chAll = Ui.chip(this, "全部打开", false, v -> {
            swSet.setChecked(true);
            swDraw.setChecked(true);
            swColor.setChecked(true);
            swAuto.setChecked(true);
            swIcons.setChecked(true);
            swSetOnly.setChecked(true);
            swState.setChecked(true);
            etDepth.setText("30");
            toast("已全部打开，点底部「应用并重新记录」后生效");
        });
        presets.addView(chAll);
        root.addView(presets, Ui.lpM(this, -1, -2, 0, 0, 0, 8));

        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 2));
        Ui.Sw s;
        s = Ui.switchRow(this, "文字是谁设置的", "记下每段文字由哪行代码 setText 出来（最常用）");
        swSet = s.sw;
        card.addView(s.row);
        card.addView(Ui.divider(this));
        s = Ui.switchRow(this, "文字是谁画的", "记下每段文字由哪行代码画到屏幕上；自绘和 Compose 的文字只能靠它");
        swDraw = s.sw;
        card.addView(s.row);
        card.addView(Ui.divider(this));
        s = Ui.switchRow(this, "文字颜色", "记下画笔颜色，以及谁调用了 setTextColor");
        swColor = s.sw;
        card.addView(s.row);
        card.addView(Ui.divider(this));
        s = Ui.switchRow(this, "Compose 文字参数", "自动抓取 Compose 文字的颜色、描边 / 填充（如弹幕的黑边 + 白字）");
        swAuto = s.sw;
        card.addView(s.row);
        card.addView(Ui.divider(this));
        s = Ui.switchRow(this, "图标和按钮图片", "记下 ImageView 设置的图片和内容描述，用来找图标按钮；记录量会明显增加");
        swIcons = s.sw;
        card.addView(s.row);
        card.addView(Ui.divider(this));
        s = Ui.switchRow(this, "没显示出来的文字", "也记下只被设置、从未画出的文字，如拖动进度条才出现的时长");
        swSetOnly = s.sw;
        card.addView(s.row);
        card.addView(Ui.divider(this));
        s = Ui.switchRow(this, "状态变化", "记下透明度 / 可见性 / 选中态的变化，用来研究“选中”效果是怎么实现的。默认只记已经记过文字或图标的控件；在「高级」里填了「控件包含」，匹配的任何控件都记");
        swState = s.sw;
        card.addView(s.row);
        root.addView(card);
    }

    private void buildRangeCard(LinearLayout root) {
        root.addView(Ui.section(this, "记录范围", null));
        LinearLayout card = Ui.card(this);
        segRange = Ui.segmented(this, new String[]{"全部文字", "只记关键字"}, 0, i -> updateRange());
        card.addView(segRange.root);
        rangeDesc = Ui.tv(this, "", 12.5f, R.color.text_sub, false);
        rangeDesc.setPadding(Ui.dp(this, 2), Ui.dp(this, 8), 0, 0);
        rangeDesc.setLineSpacing(0, 1.1f);
        card.addView(rangeDesc);

        kwBox = Ui.vertical(this);
        etKeywords = Ui.field(this, "关键字，逗号或换行分隔；正则模式每行一个，如 \\d\\d:\\d\\d", true, false);
        etKeywords.setMinLines(2);
        kwBox.addView(etKeywords, Ui.lpM(this, -1, -2, 0, 10, 0, 0));
        Ui.Sw rx = Ui.switchRow(this, "按正则表达式匹配", "打开后每行是一个正则（命中任意一行即记录）");
        swRegex = rx.sw;
        kwBox.addView(rx.row);
        card.addView(kwBox);
        root.addView(card);
    }

    private void updateRange() {
        boolean kw = segRange.sel == 1;
        kwBox.setVisibility(kw ? View.VISIBLE : View.GONE);
        rangeDesc.setText(kw ? "只记录文字命中下面关键字的位置，对目标 App 影响小。"
                : "记录屏幕上出现的所有文字。会拖慢目标 App，只建议调试时使用。");
    }

    /**
     * 采集过滤：全是开关和分段选择，在目标 App 里、抓栈之前就判断，不符合的调用不记（也不抓栈，更省资源）。
     * 全部关掉 = 不过滤。每个条件挡掉了多少次，会写在记录页顶部，记录不够时看是不是被哪个条件挡掉了，再把那个开关关掉。
     * 需要手写内容的条件（线程名、页面、控件、排除名单、值）放在「高级」里，可选。
     */
    private void buildFilterCard(LinearLayout root) {
        root.addView(Ui.section(this, "采集过滤（挡掉不相关的）",
                "在目标 App 里、抓调用栈之前就判断，不符合的不记，也更省资源。全部关掉 = 不过滤。记录页顶部会写每个开关挡掉了多少次，记录不够就关掉那个开关。"));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER_VERTICAL);
        presets.addView(Ui.tv(this, "快速选择", 12.5f, R.color.text_hint, false), Ui.lpM(this, -2, -2, 4, 0, 10, 0));
        chHook = Ui.chip(this, "找钩子（最窄）", false, v -> {
            segWindow.select(1);          // 触摸后 1.5 秒
            swWindowAll.setChecked(false);
            swInfra.setChecked(true);
            segThread.select(0);
            swClock.setChecked(false);
            swQuiet.setChecked(true);
            toast("已选「找钩子」：触摸后 1.5 秒内才记，排除通用基础设施，压低高频方法；点底部按钮后生效");
        });
        presets.addView(chHook, Ui.lpM(this, -2, -2, 0, 0, 8, 0));
        chOff = Ui.chip(this, "关掉全部过滤", false, v -> {
            segWindow.select(0);
            swWindowAll.setChecked(false);
            swInfra.setChecked(false);
            segThread.select(0);
            swClock.setChecked(false);
            swQuiet.setChecked(false);
            etThread.setText("");
            etPageF.setText("");
            etViewF.setText("");
            etExclude.setText("");
            etValue.setText("");
            toast("已关掉全部采集过滤，点底部按钮后生效");
        });
        presets.addView(chOff);
        root.addView(presets, Ui.lpM(this, -1, -2, 0, 0, 0, 8));

        LinearLayout card = Ui.card(this);
        segWindow = segRowIn(card, "触发窗口",
                "只记最近一次触摸之后这段时间内发生的调用。适合“做一次操作看一组记录”（双击、拖进度条），后台刷新基本都被挡掉。方法追踪默认受它管。",
                new String[]{"不限", "1.5 秒", "3 秒", "5 秒"});
        Ui.Sw wa = Ui.switchRow(this, "触发窗口也用于文字 / 图标 / 状态", "默认只管方法追踪；打开后文字、颜色、图标、状态变化、绘制记录也只在触摸后的窗口内记");
        swWindowAll = wa.sw;
        card.addView(wa.row);
        card.addView(Ui.divider(this));
        Ui.Sw inf = Ui.switchRow(this, "排除通用基础设施", "不记 LiveData、Fragment 生命周期、字节的异步包装等几乎所有功能都会经过的类，它们当钩子点没有意义（只对方法追踪生效）");
        swInfra = inf.sw;
        card.addView(inf.row);
        card.addView(Ui.divider(this));
        segThread = segRowIn(card, "线程", "界面更新在主线程，点赞这类后台逻辑多在后台线程；选「只记」就把另一类挡掉。",
                new String[]{"全部", "只主线程", "只后台线程"});
        card.addView(Ui.divider(this));
        Ui.Sw ck = Ui.switchRow(this, "只记带时间的调用", "方法追踪只记参数或返回值里带 00:00 这类时间的调用，找视频时长用（只对方法追踪生效）");
        swClock = ck.sw;
        card.addView(ck.row);
        card.addView(Ui.divider(this));
        Ui.Sw qt = Ui.switchRow(this, "压低高频方法", "同一位置调用超过 50 次且持续超过 3 秒后只计数、不再抓栈，进度条刷新之类就不会淹没记录（只对方法追踪生效）");
        swQuiet = qt.sw;
        card.addView(qt.row);
        root.addView(card);
    }

    /** 标题 + 说明 + 分段选择（几选一）。 */
    private Ui.Seg segRowIn(LinearLayout parent, String title, String desc, String[] labels) {
        LinearLayout col = Ui.vertical(this);
        col.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(Ui.tv(this, title, 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        col.addView(d);
        Ui.Seg s = Ui.segmented(this, labels, 0, i -> { });
        col.addView(s.root);
        parent.addView(col);
        return s;
    }

    private static int windowIndex(int ms) {
        if (ms <= 0) return 0;
        int best = 1;
        for (int i = 1; i < WINDOW_MS.length; i++) {
            if (Math.abs(WINDOW_MS[i] - ms) < Math.abs(WINDOW_MS[best] - ms)) best = i;
        }
        return best;
    }

    private EditText numberRowIn(LinearLayout parent, String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout left = Ui.vertical(this);
        left.addView(Ui.tv(this, title, 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, 0);
        left.addView(d);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText e = Ui.field(this, "", false, true);
        row.addView(e, Ui.lpM(this, Ui.dp(this, 84), -2, 12, 0, 0, 0));
        parent.addView(row);
        return e;
    }

    private EditText textRowIn(LinearLayout parent, String title, String desc, String hint, boolean multi) {
        LinearLayout col = Ui.vertical(this);
        col.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        col.addView(Ui.tv(this, title, 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        col.addView(d);
        EditText e = Ui.field(this, hint, multi, false);
        col.addView(e);
        parent.addView(col);
        return e;
    }

    private void buildAdvancedCard(LinearLayout root) {
        root.addView(Ui.section(this, "高级", null));
        LinearLayout card = Ui.card(this);
        advHeader = Ui.tv(this, "", 15.5f, R.color.text, true);
        advHeader.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        advHeader.setClickable(true);
        advHeader.setOnClickListener(v -> setAdvanced(advBody.getVisibility() != View.VISIBLE));
        card.addView(advHeader);

        advBody = Ui.vertical(this);
        TextView note = Ui.tv(this, "一般不用改。这些参数控制抓取调用栈的力度和记录容量。", 12.5f, R.color.text_sub, false);
        note.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        advBody.addView(note);

        etDepth = numberRow("调用栈保存层数", "每条记录保存多少层调用栈。越大越完整，文件也越大。范围 1–60，默认 12");
        etPerKey = numberRow("同一位置先抓几次栈", "同一处代码前几次完整记录调用栈，之后只计数。默认 3");
        etSample = numberRow("之后每隔多少次再抽样", "用来发现同一控件的其他调用路径。默认 200");
        etPerSec = numberRow("每秒最多抓栈次数", "防止拖垮目标 App 的总闸。冷启动文字很多，设太小会丢文字。默认 1000");
        etMax = numberRow("最多保存记录数", "超过后新记录会被丢弃。默认 2000");
        etMaxSetOnly = numberRow("“没显示出来的文字”上限", "这类记录单独计数。默认 500");

        TextView reset = Ui.button(this, "恢复默认参数", Ui.TEXT, v -> {
            etDepth.setText("12");
            etPerKey.setText("3");
            etSample.setText("200");
            etPerSec.setText("1000");
            etMax.setText("2000");
            etMaxSetOnly.setText("500");
        });
        reset.setMinHeight(Ui.dp(this, 40));
        advBody.addView(reset, Ui.lpM(this, -2, -2, -8, 0, 0, 0));

        advBody.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 8, 0, 12));
        advBody.addView(Ui.tv(this, "方法追踪（只读）", 14.5f, R.color.text, true));
        TextView td = Ui.tv(this, "每行写一个「类名#方法名」，记录它每次被调用时的参数、返回值和调用者。"
                + "构造方法写 类#<init>；写 类#* 追踪整个类（调用很多，配合上面的排除名单和触发窗口）；以 // 开头的行是注释。"
                + "行尾可加选项（空格分隔）：@count 只计数；@args 只记参数 / 返回值、不抓栈；@stack=4 这一行单独的栈深度；@every=10 这一行单独的抽样间隔。"
                + "不要追踪每秒调用上千次的方法，除非加 @count。", 12.5f, R.color.text_sub, false);
        td.setLineSpacing(0, 1.1f);
        td.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        advBody.addView(td);
        etTrace = Ui.field(this, "androidx.compose.ui.text.MultiParagraph#paint-LG529CI", true, false);
        advBody.addView(etTrace);

        advBody.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 12, 0, 12));
        advBody.addView(Ui.tv(this, "手写过滤条件（可选）", 14.5f, R.color.text, true));
        TextView hd = Ui.tv(this, "上面「采集过滤」的开关不够用时再填，全部留空 = 不起作用。", 12.5f, R.color.text_sub, false);
        hd.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        advBody.addView(hd);
        etThread = textRowIn(advBody, "线程名包含",
                "线程名包含其中任意一个词才记，逗号或换行分隔，如 LegoHandler。", "LegoHandler", false);
        etPageF = textRowIn(advBody, "页面包含",
                "页面标签（如 MainActivity > FeedItemFragment）包含其中任意一个词才记，不区分大小写。", "FeedItemFragment", false);
        etViewF = textRowIn(advBody, "控件包含",
                "只对文字 / 图标 / 状态 / 绘制记录生效：View 类名或 id 包含其中任意一个词才记。自绘和 Compose 没有 View，填了就不记。", "DiggLayout", false);
        etExclude = textRowIn(advBody, "额外排除",
                "只对方法追踪生效，每行一项：类#方法、类#*（整个类）、#方法（任何类里的这个方法）、或类名 / 包名前缀。",
                "androidx.lifecycle.LiveData#considerNotify", true);
        etValue = textRowIn(advBody, "额外值匹配",
                "只对方法追踪生效：参数或返回值里包含其中任意一个值才记。时长会自动展开，填 01:37 会同时匹配 97000。参数摘要里字符串只保留前 60 到 80 个字符，藏在大对象深处的值匹配不到。",
                "01:37", false);
        card.addView(advBody);
        root.addView(card);
        setAdvanced(false);
    }

    private void setAdvanced(boolean open) {
        advBody.setVisibility(open ? View.VISIBLE : View.GONE);
        advHeader.setText((open ? "▾  " : "▸  ") + "抓栈参数与方法追踪");
    }

    private EditText numberRow(String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        LinearLayout left = Ui.vertical(this);
        left.addView(Ui.tv(this, title, 14.5f, R.color.text, false));
        TextView d = Ui.tv(this, desc, 12, R.color.text_sub, false);
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, Ui.dp(this, 2), 0, 0);
        left.addView(d);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        EditText e = Ui.field(this, "", false, true);
        row.addView(e, Ui.lpM(this, Ui.dp(this, 84), -2, 12, 0, 0, 0));
        advBody.addView(row);
        return e;
    }

    // ------------------------------------------------------------ 读取 / 保存

    /** 开关 / 分段选择 / 手写框一变，就重新判断四个快速选择按钮该不该高亮（只改按钮颜色，不动任何开关）。 */
    private void wireChips() {
        android.widget.CompoundButton.OnCheckedChangeListener l = (b, on) -> refreshChips();
        for (Switch sw : new Switch[]{swSet, swDraw, swColor, swAuto, swIcons, swSetOnly, swState,
                swWindowAll, swInfra, swClock, swQuiet}) sw.setOnCheckedChangeListener(l);
        segWindow.cb = i -> refreshChips();
        segThread.cb = i -> refreshChips();
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) { refreshChips(); }
        };
        for (EditText et : new EditText[]{etThread, etPageF, etViewF, etExclude, etValue}) et.addTextChangedListener(tw);
    }

    private static boolean blank(EditText e) {
        return e.getText().toString().trim().isEmpty();
    }

    private void refreshChips() {
        if (chHook == null || chOff == null || chTextOnly == null || chAll == null || chTraceOnly == null || segWindow == null) return;
        boolean filtersOff = segWindow.sel == 0 && !swWindowAll.isChecked() && !swInfra.isChecked()
                && segThread.sel == 0 && !swClock.isChecked() && !swQuiet.isChecked()
                && blank(etThread) && blank(etPageF) && blank(etViewF) && blank(etExclude) && blank(etValue);
        boolean hook = segWindow.sel == 1 && !swWindowAll.isChecked() && swInfra.isChecked()
                && segThread.sel == 0 && !swClock.isChecked() && swQuiet.isChecked();
        boolean four = swSet.isChecked() && swDraw.isChecked() && swColor.isChecked() && swAuto.isChecked();
        boolean textOnly = four && !swIcons.isChecked() && !swSetOnly.isChecked() && !swState.isChecked();
        boolean all = four && swIcons.isChecked() && swSetOnly.isChecked() && swState.isChecked();
        boolean traceOnly = !swSet.isChecked() && !swDraw.isChecked() && !swColor.isChecked() && !swAuto.isChecked()
                && !swIcons.isChecked() && !swSetOnly.isChecked() && !swState.isChecked();
        Ui.setChip(chTraceOnly, traceOnly, "只记方法追踪");
        Ui.setChip(chHook, hook, "找钩子（最窄）");
        Ui.setChip(chOff, filtersOff, "关掉全部过滤");
        Ui.setChip(chTextOnly, textOnly, "只记文字（推荐）");
        Ui.setChip(chAll, all, "全部打开");
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
    }

    /** 把一份 RecConfig 回填进表单控件，不碰 SharedPreferences、不推送——load() 和「粘贴配置」共用这一步。 */
    private void applyToUi(RecConfig c) {
        swEnabled.setChecked(c.enabled);
        swSet.setChecked(c.traceSet);
        swDraw.setChecked(c.traceDraw);
        swColor.setChecked(c.traceColor);
        swIcons.setChecked(c.traceIcons);
        swAuto.setChecked(c.autoParams);
        swSetOnly.setChecked(c.traceSetOnly);
        swState.setChecked(c.traceState);
        etTrace.setText(c.traceMethods);
        segWindow.select(windowIndex(c.windowMs));
        swWindowAll.setChecked(c.windowAll);
        swInfra.setChecked(c.excludeInfra);
        segThread.select(Math.max(0, Math.min(2, c.threadMode)));
        swClock.setChecked(c.valueClock);
        swQuiet.setChecked(c.quietCount > 0);
        etThread.setText(c.threadFilter);
        etPageF.setText(c.pageFilter);
        etViewF.setText(c.viewFilter);
        etExclude.setText(c.excludeList);
        etValue.setText(c.valueFilter);
        segRange.select(c.keywordMode ? 1 : 0);
        etKeywords.setText(c.keywords);
        swRegex.setChecked(c.regex);
        etDepth.setText(String.valueOf(c.stackDepth));
        etPerKey.setText(String.valueOf(c.perKeyStacks));
        etSample.setText(String.valueOf(c.sampleEvery));
        etPerSec.setText(String.valueOf(c.stacksPerSec));
        etMax.setText(String.valueOf(c.maxRecords));
        etMaxSetOnly.setText(String.valueOf(c.maxSetOnly));
        updateRange();
        refreshChips();
        // 设置过方法追踪的，默认把“高级”展开，免得以为没生效
        if (!c.traceMethods.trim().isEmpty()) setAdvanced(true);
    }

    private RecConfig read(long epoch) {
        RecConfig c = new RecConfig();
        c.enabled = swEnabled.isChecked();
        c.traceSet = swSet.isChecked();
        c.traceDraw = swDraw.isChecked();
        c.traceColor = swColor.isChecked();
        c.traceIcons = swIcons.isChecked();
        c.autoParams = swAuto.isChecked();
        c.traceSetOnly = swSetOnly.isChecked();
        c.traceState = swState.isChecked();
        c.traceMethods = etTrace.getText().toString();
        c.windowMs = WINDOW_MS[Math.max(0, Math.min(WINDOW_MS.length - 1, segWindow.sel))];
        c.windowAll = swWindowAll.isChecked();
        c.excludeInfra = swInfra.isChecked();
        c.threadMode = Math.max(0, Math.min(2, segThread.sel));
        c.valueClock = swClock.isChecked();
        c.threadFilter = etThread.getText().toString();
        c.pageFilter = etPageF.getText().toString();
        c.viewFilter = etViewF.getText().toString();
        c.excludeList = etExclude.getText().toString();
        c.valueFilter = etValue.getText().toString();
        c.quietCount = swQuiet.isChecked() ? 50 : 0;
        c.quietSpanMs = 3000;
        c.keywordMode = segRange.sel == 1;
        c.keywords = etKeywords.getText().toString();
        c.regex = swRegex.isChecked();
        c.stackDepth = RecConfig.clamp(parse(etDepth, 12), 1, 60);
        c.perKeyStacks = RecConfig.clamp(parse(etPerKey, 3), 1, 50);
        c.sampleEvery = RecConfig.clamp(parse(etSample, 200), 10, 100000);
        c.stacksPerSec = RecConfig.clamp(parse(etPerSec, 1000), 1, 5000);
        c.maxRecords = RecConfig.clamp(parse(etMax, 2000), 10, 20000);
        c.maxSetOnly = RecConfig.clamp(parse(etMaxSetOnly, 500), 10, 5000);
        c.epoch = epoch;
        return c;
    }

    /**
     * 顶栏「粘贴配置」：贴一段 Java Properties 格式的文本（字段名见 README「采集配置」一节，
     * 和 config.properties、导出用的是同一套字段名），只回填表单，不直接推送——推不推、推之前再看一眼，
     * 照常走下面已有的「只应用设置」「应用并重新记录」按钮。
     */
    private void showPasteConfig() {
        LinearLayout body = Ui.vertical(this);
        TextView desc = Ui.tv(this,
                "粘贴 key=value（一行一个），字段名见仓库 README「采集配置」一节；任何 AI 都能照着那张表拼，不用来问开发者。"
                        + "没写的字段保持默认值，不是保持当前表单的值。粘贴后只回填下面的开关/输入框，不会直接推送到目标 App。",
                13, R.color.text_sub, false);
        desc.setLineSpacing(0, 1.15f);
        body.addView(desc);
        final EditText et = Ui.field(this, "keywordMode=true\nkeywords=福利,红包\ntraceIcons=true", true, false);
        body.addView(et);
        new Ui.Dlg(this, "粘贴配置", null).keyboard()
                .body(body, true)
                .button("取消", false, null)
                .button("回填表单", true, () -> {
                    applyToUi(RecConfig.fromText(et.getText().toString()));
                    toast("已回填，检查一遍没问题再点下面的按钮推送");
                })
                .show();
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

    private void save(final boolean push, final boolean reset) {
        SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        long epoch = reset ? System.currentTimeMillis() : Actions.loadCfg(sp).epoch;
        final RecConfig c = read(epoch);

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
        Ui.enable(btnReset, false);
        Ui.enable(btnApplyOnly, false);
        setStatus("正在推送…（首次会弹出 root 授权，请点允许）");
        new Thread(() -> {
            final String msg = Actions.push(list, c, reset)
                    + (reset ? "\n下一步：强杀目标 App 再重新打开，操作完回来点「查看记录」。"
                    : "\n设置约 2 秒内在目标 App 里生效，不用重启；要记录冷启动阶段的文字，请强杀后重新打开。")
                    + (c.keywordMode ? "" : "\n当前是全部文字模式，会拖慢目标 App，只建议调试时使用。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Ui.enable(btnReset, true);
                Ui.enable(btnApplyOnly, true);
                setStatus(msg.trim());
            });
        }).start();
    }
}
