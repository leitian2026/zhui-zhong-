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
    private android.text.TextWatcher tw;

    private static final String[] SCENE = {"text", "icons", "state", "all", "trace", "statehook", "viewtree", "tap"};
    private static final String[] SCENE_LABEL = {"文字", "图标", "状态", "全部", "方法追踪", "状态钩子", "视图树", "点哪查哪"};
    private static final String[] SCENE_GROUP = {"content", "content", "content", "content", "trace", "trace", "view", "view"};
    private static final HashSet<String> MULTI = new HashSet<>(java.util.Arrays.asList("traceMethods", "watch", "excludeList", "keywords", "dumpPrefixes", "valueFilter"));

    // 首页上手写了人话说明的项：key, 标题, 说明。没有的项用 field_meta.txt 里的一句话说明。
    private static final String[][] RICH = {
            {"enabled", "启用记录", "关闭后目标 App 暂停记录，不会再产生新的记录"},
            {"traceSet", "文字是谁设置的", "记下每段文字由哪行代码 setText 出来（最常用）"},
            {"traceDraw", "文字是谁画的", "记下每段文字由哪行代码画到屏幕上；自绘和 Compose 的文字只能靠它"},
            {"traceColor", "文字颜色", "记下画笔颜色，以及谁调用了 setTextColor"},
            {"autoParams", "Compose 文字参数", "自动抓取 Compose 文字的颜色、描边 / 填充（如弹幕的黑边 + 白字）"},
            {"traceIcons", "图标和按钮图片", "记下 ImageView 设置的图片和内容描述，用来找图标按钮；记录量会明显增加"},
            {"traceSetOnly", "没显示出来的文字", "也记下只被设置、从未画出的文字，如拖动进度条才出现的时长"},
            {"traceState", "状态变化", "记下透明度 / 可见性 / 选中态的变化，研究“选中”效果怎么实现的。默认只记已经记过文字或图标的控件；在「采集过滤」里填了「控件包含」，匹配的任何控件都记"},
            {"traceCompose", "Compose 文字绘制", "钩 MultiParagraph.paint*（颜色 / 描边 / 填充），Compose 才有"},
            {"composeObj", "Compose：记对象身份", "每条 Compose 记录记下 MultiParagraph 对象的身份哈希（看是同一个对象反复画还是每次新建）"},
            {"composeDump", "Compose：展开对象字段", "每条 Compose 记录展开一次 MultiParagraph 的字段（含文字）"},
            {"dumpPrefixes", "额外放行的类", "字段展开默认只进 App 自己的类。这里的包名 / 类名前缀也会进，逗号或换行分隔"},
            {"traceExclusive", "方法追踪时只记方法追踪", "默认开：填了要追踪的方法时，文字 / 图标 / 颜色 / 状态 / 绘制这些记录开关在目标 App 里自动当成关闭（界面上的开关不会被改，方法清单清空后恢复），记录里只剩方法追踪，更干净。想一起记就关掉它"},
            {"windowMs", "触发窗口（毫秒）", "只记最近一次触摸之后这段时间内发生的调用；0 = 不限。常用 1500 / 3000 / 5000。适合“做一次操作看一组记录”，后台刷新基本都被挡掉。方法追踪默认受它管"},
            {"windowAll", "触发窗口也用于文字 / 图标 / 状态", "默认只管方法追踪；打开后文字、颜色、图标、状态变化、绘制记录也只在触摸后的窗口内记"},
            {"excludeInfra", "排除通用基础设施", "不记 LiveData、Fragment 生命周期、字节的异步包装等几乎所有功能都会经过的类（只对方法追踪生效）"},
            {"valueClock", "只记带时间的调用", "方法追踪只记参数或返回值里带 00:00 这类时间的调用，找视频时长用"},
            {"quietCount", "压低高频方法（次数）", "同一位置调用超过这个次数且持续一段时间后只计数、不再抓栈，进度条刷新之类就不会淹没记录；0 = 关，常用 50（只对方法追踪生效）"},
            {"threadFilter", "线程名包含", "线程名包含其中任意一个词才记，逗号或换行分隔，如 LegoHandler"},
            {"pageFilter", "页面包含", "页面标签（如 MainActivity > FeedItemFragment）包含其中任意一个词才记，不区分大小写"},
            {"viewFilter", "控件包含", "只对文字 / 图标 / 状态 / 绘制记录生效：View 类名或 id 包含其中任意一个词才记。自绘和 Compose 没有 View，填了就不记"},
            {"excludeList", "额外排除", "只对方法追踪生效，每行一项：类#方法、类#*（整个类）、#方法（任何类里的这个方法）、或类名 / 包名前缀"},
            {"valueFilter", "额外值匹配", "只对方法追踪生效：参数或返回值里包含其中任意一个值才记。时长会自动展开，填 01:37 会同时匹配 97000"},
            {"traceDump", "记录对象字段值", "方法追踪时，每隔几次调用把被追踪对象（this）周围的字段值记一份：找进度、总时长存在哪时用，用完关掉（只对方法追踪生效）"},
            {"treeOn", "展开数据树", "默认开。导出里有「数据树」「值差异」两节：树上每个节点在每个任务里的值，值随任务不同的节点（✦）往往就是判断状态的字段"},
            {"treeViews", "数据树也展开控件的字段", "默认关。打开后展开控件自己的业务字段，字段多，建议把 treeNodes 调到 600"},
            {"watchAuto", "自动给「值变过的叶子」加观察点", "默认开，最多 24 个；导出「观察点变化」里写出变化发生在哪个方法执行期间"},
            {"annotFrames", "调用栈每一帧标分层 / 角色 / 稳定度", "默认开。每帧后面标〔业务·ViewHolder·稳〕这样的标签，并标出 ★入口帧、◎推荐钩点、↯异步边界"},
            {"annotOwners", "方法追踪命中时反查「谁持有这个对象」", "默认开。从当前页面的 Activity / Fragment / ViewModel / 视图树里反向找出持有它的容器，在后台线程做，有时间上限"},
            {"annotSweep", "任务开始 / 结束 / 点标记时扫「容器 → 数据实体」", "默认开。导出里有「对象归属」一节，不需要追踪任何方法就能知道 Aweme 住在哪个容器里"},
            {"chainOn", "链路追踪", "以方法追踪里的方法为落脚点，记下它的上游（谁调用它、谁异步投递）和下游（它执行期间调用了哪些方法、改了哪些界面）。会拖慢目标 App，用完关掉"},
            {"chainAsync", "上游：异步来源", "钩 Handler 消息队列和线程池，记下异步任务是谁投递的"},
            {"chainDown", "下游：被调用的方法", "锚点所在的类（最多 8 个类、300 个方法）会被临时钩住，记下锚点执行期间它们被调用的先后和嵌套"},
            {"chainEffects", "下游：界面效果", "锚点执行期间发生的 setText / 清空 / 图标 / 状态变化"},
            {"traceStackDepth", "方法追踪栈层数", "方法追踪的调用栈保存多少层。默认 30"},
            {"viewDump", "任务开始 / 结束 / 点标记时导出视图树", "弄清一块界面里各个控件的父子关系和布局类型。默认关，用完关掉"},
            {"viewDumpKey", "以哪个控件为准", "屏幕上类名或 id 含这些词（逗号分隔）的控件当作「主角」，取可见面积最大的一个。默认是抖音视频文案的两个类"},
            {"viewTap", "点哪查哪", "默认关。开了以后，每次点一下屏幕，把这个点上所有控件列出来：类名、id、可见性、坐标、文字，从外层排到最上层"},
            {"tlOn", "时间线（总开关）", "把用户操作、页面切换、各位置的记录样本按毫秒排成一条时间线；开销很小，默认开"},
            {"tlOps", "记用户操作", "点击、滑动、返回键，带坐标和点中的控件"},
            {"tlSamples", "记各位置的样本", "每个位置前 5 次、之后每 100 次进一次时间线"},
            {"recOutside", "任务外也记录", "默认关：用任务浮窗录的时候，只有「任务开始 → 任务结束」之间才建记录。打开后任务外也记，但单独一桶存"},
            {"splitFiles", "分文件存", "每种记录一个快照文件、时间线一个文件，各自独立、各自有上限；关掉 = 一个文件 + 总上限"},
            {"stackDepth", "调用栈保存层数", "每条记录保存多少层调用栈。越大越完整，文件也越大。默认 12"},
            {"perKeyStacks", "同一位置先抓几次栈", "同一处代码前几次完整记录调用栈，之后只计数。默认 3"},
            {"sampleEvery", "之后每隔多少次再抽样", "用来发现同一控件的其他调用路径。默认 200"},
            {"stacksPerSec", "每秒最多抓栈次数", "防止拖垮目标 App 的总闸。冷启动文字很多，设太小会丢文字。默认 1000"},
            {"maxRecords", "最多保存记录数", "超过后新记录会被丢弃（分文件存关掉时才用）。默认 2000"},
            {"maxSetOnly", "“没显示出来的文字”上限", "这类记录单独计数。默认 500"},
    };

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
    };

    private static String[] rich(String key) {
        for (String[] r : RICH) if (r[0].equals(key)) return r;
        return null;
    }

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
        new Thread(() -> {
            java.util.Set<String> kn = KnownNames.load(new java.io.File(getFilesDir(), "known_classes.txt"));
            if (!kn.isEmpty()) ConfigCheck.knownClasses = kn;
        }).start();
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
        String[] r = rich(key);
        String title = r != null ? r[1] : f.desc;
        String desc = r != null ? r[2] : "";
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
        for (ConfigSchema.F c : ConfigSchema.ALL) if (c.parent.equals(key)) addField(parent, c.key, depth + 1);
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
        String hit = null;
        for (int i = 0; i < SCENE.length; i++) {
            boolean on = Presets.matches(c, SCENE[i]);
            Ui.setChip(sceneChips.get(SCENE[i]), on, SCENE_LABEL[i]);
            if (on && hit == null) hit = SCENE[i];
        }
        if (chHook != null) Ui.setChip(chHook, Presets.matches(c, "hook"), "找钩子（最窄）");
        if (chOff != null) Ui.setChip(chOff, Presets.matches(c, "nofilter"), "关掉全部过滤");
        if (hit != null) {
            Presets.P p = Presets.get(hit);
            tvSceneDesc.setText(p.title + "\n" + p.desc);
        } else {
            tvSceneDesc.setText("当前是自定义组合（和上面任何一个场景都不完全相同）。");
        }
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
        List<ConfigCheck.Issue> issues = ConfigCheck.checkPaste(text);
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
        sub(g.body, "Compose 文字", "只有 Compose 画的文字（如弹幕）才用得上");
        addField(g.body, "traceCompose", 0);
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
        sub(g.body, "时间线与任务", null);
        addField(g.body, "tlOn", 0);
        addField(g.body, "recOutside", 0);
    }

    private void buildLimitsGroup(LinearLayout root) {
        Group g = new Group(root, "limits", "记录容量与抓栈力度", "一般不用改。这些参数控制抓取调用栈的力度和记录容量；每种记录各自一个文件、各自有上限，一种灌满不会挤掉别的。", false);
        for (String k : new String[]{"stackDepth", "perKeyStacks", "sampleEvery", "stacksPerSec", "flushSec", "splitFiles"}) addField(g.body, k, 0);
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
            StringBuilder raw = new StringBuilder(), snaps = new StringBuilder();
            for (String pkg : list) {
                if (!RootShell.validPkg(pkg)) continue;
                raw.append(RootShell.pullStatus(pkg).out);
                snaps.append(RootShell.pullSnapshots(pkg).out);
            }
            List<TargetStatus.Proc> ps = TargetStatus.parse(raw.toString());
            java.util.Set<String> fresh = KnownNames.extract(snaps.toString());
            if (!fresh.isEmpty()) ConfigCheck.knownClasses = KnownNames.learn(new java.io.File(getFilesDir(), "known_classes.txt"), fresh);
            final String text = TargetStatus.describe(ps, RecConfig.fromText(pushedText), System.currentTimeMillis())
                    + (fresh.isEmpty() ? "" : "\n（已从目标 App 的记录里学到 " + fresh.size() + " 个类名，写方法追踪时会拿它核对）");
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

    private boolean parentOn(String key) {
        if (key.equals("keywordMode")) return segRange != null && segRange.sel == 1;
        Switch s = sw.get(key);
        if (s != null) return s.isChecked();
        EditText e = ed.get(key);
        if (e != null) return parse(e, 0) != 0;
        return true;
    }

    private void refreshUi() {
        if (segRange == null) return;
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.parent.isEmpty()) continue;
            View row = rows.get(f.key);
            if (row == null) continue;
            boolean on = parentOn(f.parent);
            boolean dead = f.parentInverse ? on : !on;
            row.setAlpha(dead ? 0.45f : 1f);
        }
        refreshScenes();
        refreshBadges();
    }

    private static String uiGroup(ConfigSchema.F f) {
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

    private void refreshBadges() {
        RecConfig c = readUi(cur.epoch);
        RecConfig d = new RecConfig();
        HashMap<String, Integer> n = new HashMap<>();
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            if (f.group.equals("internal") || f.group.equals("target")) continue;
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                if (!String.valueOf(fl.get(c)).equals(String.valueOf(fl.get(d)))) {
                    String gid = uiGroup(f);
                    Integer old = n.get(gid);
                    n.put(gid, old == null ? 1 : old + 1);
                }
            } catch (Throwable ignored) {
            }
        }
        for (Group g : groups.values()) {
            if (g.id.equals("paste") || g.id.equals("effective")) continue;
            Integer k = n.get(g.id);
            // 「其他」组里的项算在各自原来的分组里；这里按组 id 对不上的归 other
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
            for (ConfigSchema.F f : ConfigSchema.ALL) {
                try {
                    java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                    if (f.type.equals("bool")) {
                        Switch s = sw.get(f.key);
                        if (s != null) s.setChecked(fl.getBoolean(c));
                    } else if (f.type.equals("int")) {
                        EditText e = ed.get(f.key);
                        if (e != null) e.setText(String.valueOf(fl.getInt(c)));
                    } else if (f.type.equals("string")) {
                        EditText e = ed.get(f.key);
                        if (e != null) e.setText(String.valueOf(fl.get(c)));
                    }
                } catch (Throwable ignored) {
                }
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

    /** 从控件读出一份配置：没有控件的字段保持 cur 里的值。 */
    private RecConfig readUi(long epoch) {
        RecConfig c = RecConfig.fromText(cur.toText());
        for (ConfigSchema.F f : ConfigSchema.ALL) {
            try {
                java.lang.reflect.Field fl = RecConfig.class.getField(f.key);
                if (f.type.equals("bool")) {
                    Switch s = sw.get(f.key);
                    if (s != null) fl.setBoolean(c, s.isChecked());
                } else if (f.type.equals("int")) {
                    EditText e = ed.get(f.key);
                    if (e != null) {
                        int v = parse(e, fl.getInt(c));
                        if (f.hasRange) v = RecConfig.clamp(v, (int) f.min, (int) f.max);
                        fl.setInt(c, v);
                    }
                } else if (f.type.equals("string")) {
                    EditText e = ed.get(f.key);
                    if (e != null) fl.set(c, e.getText().toString());
                }
            } catch (Throwable ignored) {
            }
        }
        if (c.viewDumpKey.trim().isEmpty()) c.viewDumpKey = new RecConfig().viewDumpKey;
        c.keywordMode = segRange.sel == 1;
        c.threadMode = Math.max(0, Math.min(2, segThread.sel));
        c.epoch = epoch;
        return c;
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
            final List<ConfigCheck.Issue> tl = ConfigCheck.checkForm(c, savedTasks());
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
