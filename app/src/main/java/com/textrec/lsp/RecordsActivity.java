package com.textrec.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 记录列表：一个位置一张卡片。顶部是搜索框，下面一排筛选芯片（类型 / 页面 / 分类 / 排序 / 仅屏幕上 / 隐藏公共控件），
 * 导出和清空收在顶栏图标里。每个芯片点开都有说明。
 */
public class RecordsActivity extends Activity {
    private static final int REQ_TXT = 2001, REQ_JSON = 2002, REQ_HTML = 2003, REQ_HOOK = 2004;
    private static final String[] SORTS = {"最近更新", "次数最多", "最早出现"};
    private static final String[] SORT_DESC = {"最近一次出现的排前面", "出现 / 绘制次数多的排前面", "最早被记录到的排前面"};
    /**
     * 记录类型和采集端的开关一一对应（颜色 / Compose 参数不单独成记录，跟着文字走）：
     *   文字是谁设置的 → 文字（TextView）；文字是谁画的 → 画出来的文字（自绘 / Compose）；
     *   图标和按钮图片 → 图标；没显示出来的文字 → 没显示的文字；状态变化 → 状态变化；方法追踪 → 方法追踪。
     * 默认选中的类型 = 采集时打开的开关；手动改过就以手动为准。
     */
    private static final String[] KINDS = {"文字（TextView）", "画出来的文字", "图标", "没显示的文字", "状态变化", "方法追踪"};
    private static final String[][] KIND_KEYS = {{"TEXTVIEW", "TEXTCLEAR"}, {"CANVAS", "COMPOSE"}, {"ICON"}, {"TEXTSET"}, {"STATE"}, {"TRACE"}};
    private static final String[] KIND_DESC = {
            "用 TextView 显示的文字，含 setText 空串 / null 的“清空文字”（对应“文字是谁设置的”）",
            "Canvas.drawText 自绘和 Jetpack Compose 里的文字（对应“文字是谁画的”）",
            "ImageView 图片和按钮（对应“图标和按钮图片”）",
            "被设置过、但从未显示出来的文字（对应“没显示出来的文字”）",
            "透明度 / 可见性 / 选中态的变化（对应“状态变化”）",
            "你在首页“方法追踪”里指定的方法"};
    private static final String[] CAT_DESC = {
            "控件放在哪个特殊容器里，如底部栏、顶部 Tab 项",
            "设置这段文字的代码属于哪个功能包，如 feed.bottombar",
            "控件本身的类名，如 TextView、DmtTextView",
            "控件中心点所在区域：顶部栏 / 底部栏 / 右侧栏 / 左下信息区 / 内容区 / 不在屏幕上",
            "TextView / Compose / 自绘 / 图标 / 方法追踪 / 仅设置 / 状态变化",
            "现在能不能直接拿去写 hook：有稳定业务帧 / 只有通用控件帧 / 只有混淆帧 / 无可用帧",
            "调用或绘制了多少次：仅一次的多半是固定文案，高频的多半是列表项公共渲染点",
            "同一段代码（控件类+最近稳定帧）是不是在好几个页面都出现，越多越像全局公共入口",
            "这个位置出现过的文字长什么样：时长格式 / 纯数字计数 / 固定文案 / 多种动态文案",
            "相对这批记录里最晚一次更新时间，是刚冒出来的还是很久前就有的历史记录",
            "这条记录能拿来做什么：改色 / 隐藏显示 / 换文字 / 跳过（方法追踪）。一条可以有几个用途，筛选按“包含”",
            "录过“安静基线”之后：基线里也在调用的是周期性噪声，基线里没出现的才是操作 / 播放触发的",
            "被什么触发：用户操作 / 帧回调·绘制 / 数据变化 / 消息·异步任务 / 同步调用（按栈判断）",
            "出现时的当前标识（方法追踪行写 @tag=N 取参数当标识，如视频 id），不同视频的记录靠它对上号"};

    private ListView lv;
    private EditText etSearch;
    private ImageView btnClearSearch;
    private Ui.Bar bar;
    private ProgressBar progress;
    private TextView chType, chPage, chCat, chSort, chScreen, chCommon;
    private TextView tvLegend, tvBanner, emptyTitle, emptyDesc, emptyBtn;
    /** 分类筛选（空 = 不限），维度见 RecFormat.CAT_DIMS，几项同时选是“且”。 */
    private final String[] catVal = new String[RecFormat.CAT_DIMS.length];
    { java.util.Arrays.fill(catVal, ""); }
    /** 复用范围 / 新鲜度两个维度要扫一遍全部记录才能算，缓存起来，数据没变就不重算。 */
    private Object aggKey;
    private java.util.Map<String, java.util.Set<String>> reuseGroups = new java.util.HashMap<>();
    private long maxLast;
    private int scrW, scrH;
    private Object scrKey;
    private boolean onlyOnScreen = true;
    private boolean hideCommon = true;
    private int hiddenCommon;
    /** 页面筛选：空 = 全部页面；否则只看（并只导出）这个页面的记录 */
    private String pageFilter = "";
    private final List<Snap.Item> all = new ArrayList<>();
    private final List<Snap.Item> shown = new ArrayList<>();
    private volatile String infoBase = "";
    /** 当前筛选下被“只看屏幕上”隐藏的记录数和编号——导出时写进文件头，免得看文件的人以为是漏记 */
    private int hiddenOff;
    private String hiddenIds = "";
    private int sortMode = 0;
    /** 选中的记录类型（和 KINDS 一一对应）。kindManual=false 时按采集开关自动选，true 时以手动为准。 */
    private final boolean[] kindSel = new boolean[KINDS.length];
    private boolean kindManual = false;
    private final AtomicBoolean loading = new AtomicBoolean();
    private String pendingExport;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("记录");
        loadAlias();
        RecFormat.LEAN = true;

        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        // ---- 顶栏：返回 / 标题 + 条数 / 刷新 导出 更多
        bar = Ui.topBar(this, "记录", "", true);
        bar.actions.addView(Ui.iconBtn(this, R.drawable.ic_refresh, "刷新（重新读取目标 App 的记录）", v -> refresh()));
        bar.actions.addView(Ui.iconBtn(this, R.drawable.ic_export, "导出", v -> showExport()));
        bar.actions.addView(Ui.iconBtn(this, R.drawable.ic_more, "更多", v -> showMenu(v)));
        root.addView(bar.root);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Ui.col(this, R.color.primary)));
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        // ---- 搜索框
        LinearLayout sbox = Ui.horizontal(this);
        sbox.setBackground(Ui.rect(Ui.col(this, R.color.surface), Ui.dp(this, 14), Ui.col(this, R.color.outline), Ui.dp(this, 1)));
        sbox.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 4), 0);
        ImageView si = new ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        si.setColorFilter(Ui.col(this, R.color.text_hint));
        sbox.addView(si, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        etSearch = new EditText(this);
        etSearch.setBackground(null);
        etSearch.setHint("搜索：文字 / 类名 / 栈帧 / 页面");
        etSearch.setTextSize(14.5f);
        etSearch.setTextColor(Ui.col(this, R.color.text));
        etSearch.setHintTextColor(Ui.col(this, R.color.text_hint));
        etSearch.setSingleLine(true);
        etSearch.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) {
                btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                applyFilter();
            }
        });
        sbox.addView(etSearch, new LinearLayout.LayoutParams(0, -2, 1f));
        btnClearSearch = Ui.iconBtn(this, R.drawable.ic_close, "清除搜索", v -> etSearch.setText(""));
        btnClearSearch.setVisibility(View.GONE);
        sbox.addView(btnClearSearch, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        root.addView(sbox, Ui.lpM(this, -1, -2, 16, 2, 16, 10));

        // ---- 筛选芯片（横向滑动）
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.horizontal(this);
        chips.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
        chType = Ui.chip(this, "", false, v -> chooseKind());
        chPage = Ui.chip(this, "", false, v -> choosePage());
        chCat = Ui.chip(this, "", false, v -> chooseCat());
        chSort = Ui.chip(this, "", false, v -> chooseSort());
        chScreen = Ui.chip(this, "", false, v -> {
            onlyOnScreen = !onlyOnScreen;
            toast(onlyOnScreen ? "只显示此刻在屏幕上的内容" : "连不在屏幕上的（预加载 / 已离开页面的）也显示");
            updateChips();
            applyFilter();
        });
        chCommon = Ui.chip(this, "", false, v -> {
            hideCommon = !hideCommon;
            toast(hideCommon ? "已隐藏在多个页面里都一样的控件（如底部栏）" : "显示所有控件，包括多个页面里都一样的");
            updateChips();
            applyFilter();
        });
        for (TextView c : new TextView[]{chType, chPage, chCat, chSort, chScreen, chCommon}) {
            chips.addView(c, Ui.lpM(this, -2, -2, 0, 0, 8, 0));
        }
        hs.addView(chips);
        root.addView(hs);

        tvLegend = Ui.tv(this, "", 11.5f, R.color.text_hint, false);
        tvLegend.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));
        root.addView(tvLegend);

        tvBanner = Ui.note(this, "", false);
        tvBanner.setMaxLines(2);
        tvBanner.setEllipsize(TextUtils.TruncateAt.END);
        tvBanner.setVisibility(View.GONE);
        tvBanner.setOnClickListener(v -> tvBanner.setMaxLines(tvBanner.getMaxLines() == 2 ? Integer.MAX_VALUE : 2));
        root.addView(tvBanner, Ui.lpM(this, -1, -2, 16, 2, 16, 6));

        // ---- 列表 + 空状态
        FrameLayout area = new FrameLayout(this);
        lv = new ListView(this);
        lv.setDivider(null);
        lv.setDividerHeight(0);
        lv.setClipToPadding(false);
        lv.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 20));
        lv.setSelector(android.R.color.transparent);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) { return row(shown.get(i), cv); }
        };
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return;
            Snap.current = shown.get(pos);
            startActivity(new Intent(this, DetailActivity.class));
        });
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return true;
            renamePage(RecFormat.rawPageOf(shown.get(pos).o));
            return true;
        });
        area.addView(lv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = Ui.vertical(this);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(Ui.dp(this, 32), Ui.dp(this, 56), Ui.dp(this, 32), 0);
        emptyTitle = Ui.tv(this, "", 17, R.color.text, true);
        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle);
        emptyDesc = Ui.tv(this, "", 13.5f, R.color.text_sub, false);
        emptyDesc.setGravity(Gravity.CENTER);
        emptyDesc.setLineSpacing(0, 1.2f);
        emptyDesc.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        empty.addView(emptyDesc);
        emptyBtn = Ui.button(this, "清除所有筛选", Ui.TONAL, v -> clearFilters());
        empty.addView(emptyBtn, new LinearLayout.LayoutParams(-2, -2));
        area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        lv.setEmptyView(empty);
        root.addView(area, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        applyDefaultKinds(); // 类型默认跟着采集开关走
        updateChips();
        updateEmpty();
    }

    /** 每次回到本页自动读取一次；没有后台轮询。 */
    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 加载

    private void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        progress.setVisibility(View.VISIBLE);
        updateEmpty();
        final SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        final List<String> pkgs = Actions.pkgs(sp);
        new Thread(() -> {
            final List<Snap.Item> items = new ArrayList<>();
            final List<String> infos = new ArrayList<>();
            Snap.resetExtras();
            for (String pkg : pkgs) {
                if (!RootShell.validPkg(pkg)) {
                    infos.add(pkg + "：包名格式不对");
                    continue;
                }
                RootShell.Result r = RootShell.pullSnapshots(pkg);
                if (r.code != 0 && r.out.isEmpty()) {
                    infos.add(pkg + "：读取失败(" + r.code + ") " + r.err.trim());
                    continue;
                }
                if (r.out.startsWith("__NOPKG__")) {
                    infos.add(pkg + "：" + RootShell.nopkgMsg(r.out));
                    continue;
                }
                List<String> sub = new ArrayList<>();
                List<Snap.Item> got = Snap.parse(r.out, sub);
                if (got.isEmpty() && sub.isEmpty()) {
                    infos.add(pkg + "：还没有记录（确认已在 LSPosed 启用并勾选作用域、已推送配置、强杀后冷启动过目标 App）");
                }
                for (String s : sub) infos.add(pkg + "  " + s);
                items.addAll(got);
            }
            final StringBuilder ib = new StringBuilder();
            for (String s : infos) ib.append(s).append('\n');
            runOnUiThread(() -> {
                loading.set(false);
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.INVISIBLE);
                all.clear();
                all.addAll(items);
                infoBase = ib.toString();
                if (!kindManual) applyDefaultKinds(); // 采集开关可能刚改过：没手动选过类型就跟着开关走
                updateChips();
                applyFilter();
            });
        }).start();
    }

    // ------------------------------------------------------------ 公共控件

    /** 同一个位置 + 同一段文字：View 类、id、类型、设置栈顶帧、绘制栈顶帧、文字。 */
    private static String commonKey(JSONObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.optString("kind")).append('|').append(r.optString("view")).append('|').append(r.optString("viewId")).append('|');
        sb.append(topFrame(r.optJSONObject("setStack"))).append('|').append(topFrame(r.optJSONObject("drawStack"))).append('|');
        JSONArray ts = r.optJSONArray("texts");
        if (ts != null && ts.length() > 0) {
            for (int i = 0; i < ts.length() && i < 3; i++) sb.append(ts.optString(i)).append('/');
        } else {
            sb.append(r.optString("lastText"));
        }
        return sb.toString();
    }

    /** 在 2 个以上页面里都出现的 key = 公共控件。只看当前的 all，不依赖筛选。 */
    private java.util.HashSet<String> commonKeys() {
        java.util.HashMap<String, java.util.HashSet<String>> pages = new java.util.HashMap<>();
        for (Snap.Item it : all) {
            String k = commonKey(it.o);
            java.util.HashSet<String> ps = pages.get(k);
            if (ps == null) {
                ps = new java.util.HashSet<>();
                pages.put(k, ps);
            }
            ps.add(RecFormat.rawPageOf(it.o));
        }
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, java.util.HashSet<String>> e : pages.entrySet()) {
            if (e.getValue().size() >= 2) out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------ 分类

    private boolean catMatch(JSONObject r) {
        for (int d = 0; d < catVal.length; d++) {
            if (!catVal[d].isEmpty() && !RecFormat.catHas(d, RecFormat.catOf(d, r, scrW, scrH, reuseGroups, maxLast), catVal[d])) return false;
        }
        return true;
    }

    /** 复用范围（dim 7）、新鲜度（dim 9）要先扫一遍 all 才能算；按 all 是否变过缓存，和 ensureScreen 同一套套路。 */
    private void ensureAgg() {
        Object key = all.isEmpty() ? "" : all.size() + ":" + System.identityHashCode(all.get(0));
        if (key.equals(aggKey)) return;
        aggKey = key;
        java.util.HashMap<String, java.util.Set<String>> groups = new java.util.HashMap<>();
        long mx = 0;
        for (Snap.Item it : all) {
            String k = RecFormat.reuseKeyOf(it.o);
            java.util.Set<String> pages = groups.get(k);
            if (pages == null) {
                pages = new java.util.HashSet<>();
                groups.put(k, pages);
            }
            pages.add(RecFormat.pageOf(it.o));
            long last = it.o.optLong("last");
            if (last > mx) mx = last;
        }
        reuseGroups = groups;
        maxLast = mx;
    }

    /** 屏幕尺寸取记录里出现过的最大坐标（和目标 App 窗口同一坐标系）；一条都没有就用本机屏幕。 */
    private void ensureScreen() {
        Object key = all.isEmpty() ? "" : all.size() + ":" + System.identityHashCode(all.get(0));
        if (key.equals(scrKey)) return;
        scrKey = key;
        int w = 0, h = 0;
        for (Snap.Item it : all) {
            for (String k : new String[]{"screen", "screenSetOnly"}) {
                JSONArray sc = it.o.optJSONArray(k);
                if (sc == null) continue;
                for (int i = 0; i < sc.length(); i++) {
                    JSONObject so = sc.optJSONObject(i);
                    JSONArray rc = so == null ? null : so.optJSONArray("rect");
                    if (rc == null || rc.length() < 4) continue;
                    w = Math.max(w, rc.optInt(2));
                    h = Math.max(h, rc.optInt(3));
                }
            }
        }
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        scrW = w;
        scrH = h;
    }

    private String catSummary() {
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < catVal.length; d++) {
            if (catVal[d].isEmpty()) continue;
            sb.append(sb.length() > 0 ? "；" : "").append(RecFormat.CAT_DIMS[d]).append('=').append(RecFormat.catLabel(d, catVal[d]));
        }
        return sb.toString();
    }

    private int catCount() {
        int n = 0;
        for (String s : catVal) if (!s.isEmpty()) n++;
        return n;
    }

    // ------------------------------------------------------------ 筛选芯片 / 清除

    private void updateChips() {
        Ui.setChip(chType, kindManual, "类型：" + kindChipText() + " ▾");
        String pg = pageFilter.isEmpty() ? "全部" : pageFilter;
        Ui.setChip(chPage, !pageFilter.isEmpty(), "页面：" + pg + " ▾");
        int n = catCount();
        Ui.setChip(chCat, n > 0, n == 0 ? "分类 ▾" : "分类（" + n + "）▾");
        Ui.setChip(chSort, false, "排序：" + SORTS[sortMode] + " ▾");
        Ui.setChip(chScreen, onlyOnScreen, (onlyOnScreen ? "✓ " : "") + "仅屏幕上");
        Ui.setChip(chCommon, hideCommon, (hideCommon ? "✓ " : "") + "隐藏公共控件");
    }

    private void clearFilters() {
        etSearch.setText("");
        pageFilter = "";
        fullIds.clear();
        java.util.Arrays.fill(catVal, "");
        kindManual = false;
        applyDefaultKinds();
        onlyOnScreen = false;
        hideCommon = false;
        updateChips();
        applyFilter();
    }

    private void updateEmpty() {
        if (loading.get()) {
            emptyTitle.setText("正在读取…");
            emptyDesc.setText("首次读取会弹出 root 授权，请点“允许”。");
            emptyBtn.setVisibility(View.GONE);
        } else if (all.isEmpty()) {
            emptyTitle.setText("还没有记录");
            emptyDesc.setText("请确认：\n① 已在 LSPosed 启用本模块并勾选目标 App\n② 在首页点过「应用并重新记录」\n③ 已强杀目标 App 并重新打开、操作过\n然后点右上角刷新");
            emptyBtn.setVisibility(View.GONE);
        } else {
            emptyTitle.setText("没有符合条件的记录");
            emptyDesc.setText("共有 " + all.size() + " 条记录被当前的筛选条件挡住了。");
            emptyBtn.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------ 弹窗：类型 / 排序

    private void chooseKind() {
        final java.util.HashMap<String, Integer> cnt = new java.util.HashMap<>();
        for (Snap.Item it : all) {
            String k = it.o.optString("kind");
            Integer n = cnt.get(k);
            cnt.put(k, n == null ? 1 : n + 1);
        }
        final Ui.Dlg d = new Ui.Dlg(this, "记录类型",
                "和首页的采集开关一一对应，默认选中的就是你采集时打开的开关。可以多选；数字是该类型的记录总数。");
        LinearLayout body = Ui.vertical(this);
        for (int i = 0; i < KINDS.length; i++) {
            final int idx = i;
            int n = 0;
            for (String k : KIND_KEYS[i]) {
                Integer c = cnt.get(k);
                if (c != null) n += c;
            }
            body.addView(Ui.optionRow(this, KINDS[i], KIND_DESC[i], String.valueOf(n), kindSel[i], v -> {
                d.dismiss();
                kindManual = true;
                kindSel[idx] = !kindSel[idx];
                updateChips();
                applyFilter();
                chooseKind(); // 多选：切换后重新打开，连续选几项再点「完成」
            }));
        }
        d.body(body, true)
                .button("按采集开关自动选", false, () -> {
                    kindManual = false;
                    applyDefaultKinds();
                    updateChips();
                    applyFilter();
                })
                .button("完成", true, null)
                .show();
    }

    /** 默认选中的类型 = 采集时打开的开关（读首页保存的配置）。 */
    private void applyDefaultKinds() {
        RecConfig c = Actions.loadCfg(getSharedPreferences(Actions.PREFS, MODE_PRIVATE));
        kindSel[0] = c.traceSet || c.traceDraw; // TextView 的文字：设置或绘制任一个打开都会产生
        kindSel[1] = c.traceDraw;
        kindSel[2] = c.traceIcons;
        kindSel[3] = c.traceSetOnly;
        kindSel[4] = c.traceState;
        kindSel[5] = !c.traceMethods.trim().isEmpty();
    }

    private boolean anyKindSel() {
        for (boolean b : kindSel) if (b) return true;
        return false;
    }

    /** 这条记录的类型有没有被选中。一个都没选（所有开关都关着）时不过滤。 */
    private boolean kindOk(String kind) {
        if (!anyKindSel()) return true;
        for (int i = 0; i < KIND_KEYS.length; i++) {
            if (!kindSel[i]) continue;
            for (String k : KIND_KEYS[i]) if (k.equals(kind)) return true;
        }
        return false;
    }

    /** 导出文件头 / 芯片上用的文字：选中的类型名，一个都没选写「全部」。 */
    private String kindSummary() {
        if (!anyKindSel()) return "全部";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KINDS.length; i++) {
            if (kindSel[i]) sb.append(sb.length() > 0 ? "、" : "").append(KINDS[i]);
        }
        return sb.toString();
    }

    private String kindChipText() {
        int n = 0;
        for (boolean b : kindSel) if (b) n++;
        return n <= 2 ? kindSummary() : n + " 类";
    }

    private void chooseSort() {
        final Ui.Dlg d = new Ui.Dlg(this, "排序方式", null);
        LinearLayout body = Ui.vertical(this);
        for (int i = 0; i < SORTS.length; i++) {
            final int idx = i;
            body.addView(Ui.optionRow(this, SORTS[i], SORT_DESC[i], null, i == sortMode, v -> {
                d.dismiss();
                sortMode = idx;
                updateChips();
                applyFilter();
            }));
        }
        d.body(body, false).show();
    }

    // ------------------------------------------------------------ 弹窗：页面

    private void loadAlias() {
        RecFormat.ALIAS.clear();
        String s = getSharedPreferences("pages", MODE_PRIVATE).getString("alias", "");
        for (String line : s.split("\n")) {
            int i = line.indexOf('\t');
            if (i > 0 && i < line.length() - 1) RecFormat.ALIAS.put(line.substring(0, i), line.substring(i + 1));
        }
    }

    private void saveAlias() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : RecFormat.ALIAS.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            sb.append(e.getKey()).append('\t').append(e.getValue().replace('\n', ' ').replace('\t', ' ')).append('\n');
        }
        getSharedPreferences("pages", MODE_PRIVATE).edit().putString("alias", sb.toString()).apply();
    }

    private void renamePage(final String raw) {
        final EditText et = Ui.field(this, "起个好认的名字，如：消息（留空 = 用原名）", false, false);
        et.setText(RecFormat.aliasOfRaw(raw).equals(raw) ? "" : RecFormat.aliasOfRaw(raw));
        et.setSelection(et.getText().length());
        new Ui.Dlg(this, "给页面命名", raw + "\n多个页面起同一个名字，会合成一类。")
                .body(et, false)
                .keyboard()
                .button("取消", false, null)
                .button("确定", true, () -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) RecFormat.ALIAS.remove(raw);
                    else RecFormat.ALIAS.put(raw, n);
                    saveAlias();
                    pageFilter = ""; // 名字变了，旧筛选可能失效
                    updateChips();
                    applyFilter();
                })
                .show();
    }

    private void choosePage() {
        final java.util.LinkedHashMap<String, Integer> cnt = new java.util.LinkedHashMap<>(); // 原始页面名 → 条数
        for (Snap.Item it : all) {
            String p = RecFormat.rawPageOf(it.o);
            Integer n = cnt.get(p);
            cnt.put(p, n == null ? 1 : n + 1);
        }
        if (cnt.isEmpty()) {
            toast("还没有记录，先刷新");
            return;
        }
        final Ui.Dlg d = new Ui.Dlg(this, "选择页面",
                "点页面名 = 只看（导出也只含）这个页面；点右边的铅笔 = 给它起个好认的名字，多个页面起同名会合成一类。");
        LinearLayout body = Ui.vertical(this);
        body.addView(Ui.optionRow(this, "全部页面", "导出时按页面分组", String.valueOf(all.size()), pageFilter.isEmpty(), v -> {
            d.dismiss();
            pageFilter = "";
            updateChips();
            applyFilter();
        }));
        for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
            final String raw = e.getKey();
            final String nm = RecFormat.aliasOfRaw(raw);
            String sub = nm.equals(raw) ? null : raw.replace("MainActivity > ", "");
            LinearLayout row = Ui.optionRow(this, nm, sub, String.valueOf(e.getValue()), nm.equals(pageFilter), v -> {
                d.dismiss();
                pageFilter = nm;
                updateChips();
                applyFilter();
            });
            ImageView pen = Ui.iconBtn(this, R.drawable.ic_edit, "给这个页面命名", v -> {
                d.dismiss();
                renamePage(raw);
            });
            pen.setColorFilter(Ui.col(this, R.color.text_hint));
            pen.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
            row.addView(pen);
            body.addView(row);
        }
        d.body(body, true).button("取消", false, null).show();
    }

    // ------------------------------------------------------------ 弹窗：分类

    private void chooseCat() {
        ensureScreen();
        final Ui.Dlg d = new Ui.Dlg(this, "分类",
                "几项同时选择 = 同时满足。进入某一项后，每个取值后面的数字是符合其余筛选的记录条数，所以能一层层缩小范围。");
        LinearLayout body = Ui.vertical(this);
        for (int i = 0; i < RecFormat.CAT_DIMS.length; i++) {
            final int dim = i;
            String cur = catVal[dim];
            body.addView(Ui.optionRow(this, RecFormat.CAT_DIMS[dim], CAT_DESC[dim], cur.isEmpty() ? "不限" : RecFormat.catLabel(dim, cur), !cur.isEmpty(), v -> {
                d.dismiss();
                chooseCatValue(dim);
            }));
        }
        d.body(body, true)
                .button("清除全部", false, () -> {
                    java.util.Arrays.fill(catVal, "");
                    updateChips();
                    applyFilter();
                })
                .button("完成", true, null)
                .show();
    }

    /** 列出这一项的所有取值和条数。条数按“去掉这一项分类、其余筛选都生效”来数，所以能一层层缩小。 */
    private void chooseCatValue(final int dim) {
        ensureScreen();
        final String keep = catVal[dim];
        catVal[dim] = "";
        applyFilter(); // 此时 shown = 不含这一项分类筛选的结果
        ensureAgg();
        final java.util.HashMap<String, Integer> cnt = new java.util.HashMap<>();
        for (Snap.Item it : shown) {
            String v0 = RecFormat.catOf(dim, it.o, scrW, scrH, reuseGroups, maxLast);
            for (String v : (dim == 10 ? v0.split(",") : new String[]{v0})) { // 用途可以有几个，每个各算一次
                Integer n = cnt.get(v);
                cnt.put(v, n == null ? 1 : n + 1);
            }
        }
        catVal[dim] = keep;
        applyFilter(); // 还原
        if (cnt.isEmpty()) {
            toast("当前筛选下没有记录");
            chooseCat();
            return;
        }
        final ArrayList<String> vals = new ArrayList<>(cnt.keySet());
        Collections.sort(vals, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int c = Integer.compare(cnt.get(b), cnt.get(a));
                return c != 0 ? c : a.compareTo(b);
            }
        });
        int total = 0;
        for (int n : cnt.values()) total += n;
        final Ui.Dlg d = new Ui.Dlg(this, RecFormat.CAT_DIMS[dim], CAT_DESC[dim]);
        LinearLayout body = Ui.vertical(this);
        body.addView(Ui.optionRow(this, "不限", null, String.valueOf(total), keep.isEmpty(), v -> {
            d.dismiss();
            catVal[dim] = "";
            updateChips();
            applyFilter();
            chooseCat();
        }));
        for (final String val : vals) {
            body.addView(Ui.optionRow(this, RecFormat.catLabel(dim, val), null, String.valueOf(cnt.get(val)), val.equals(keep), v -> {
                d.dismiss();
                catVal[dim] = val;
                updateChips();
                applyFilter();
                chooseCat();
            }));
        }
        d.body(body, true).button("返回", false, this::chooseCat).show();
    }

    // ------------------------------------------------------------ 过滤 / 排序

    private void applyFilter() {
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        Pattern re = null;
        String[] terms = null;
        String bad = "";
        if (q.startsWith("re:")) {
            try {
                re = Pattern.compile(q.substring(3));
            } catch (Throwable t) {
                bad = "（正则无效）";
            }
        } else if (!q.isEmpty()) {
            terms = q.split("\\s+");
        }
        int hidden = 0;
        StringBuilder hid = new StringBuilder();
        shown.clear();
        final java.util.HashSet<String> common = hideCommon ? commonKeys() : null;
        if (!catVal[3].isEmpty()) ensureScreen();
        ensureAgg(); // 有缓存，all 没变时这一句基本免费
        int hiddenC = 0;
        for (Snap.Item it : all) {
            final String kind = it.o.optString("kind");
            if (common != null && !"TRACE".equals(kind) && common.contains(commonKey(it.o))) { // 方法追踪不算公共控件
                if (kindOk(kind)) hiddenC++;
                continue;
            }
            if (!kindOk(kind)) continue;
            if (!pageFilter.isEmpty() && !pageFilter.equals(RecFormat.pageOf(it.o))) continue;
            if (!catMatch(it.o)) continue;
            // “没显示的文字”“清空文字”本来就不在屏幕上，不套“只看屏幕上”
            if (onlyOnScreen && !"TEXTSET".equals(kind) && !"TEXTCLEAR".equals(kind) && "no".equals(it.o.optString("vis"))) {
                hidden++;
                if (hid.length() < 200) hid.append(" #").append(it.o.optInt("id"));
                continue;
            }
            if (re != null && !re.matcher(it.hay).find()) continue;
            if (terms != null) {
                boolean ok = true;
                for (String t : terms) {
                    if (!it.hay.contains(t)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
            }
            shown.add(it);
        }
        hiddenOff = hidden;
        hiddenCommon = hiddenC;
        hiddenIds = hid.toString();
        final int mode = sortMode;
        Collections.sort(shown, new Comparator<Snap.Item>() {
            @Override
            public int compare(Snap.Item a, Snap.Item b) {
                if (mode == 1) return Integer.compare(b.o.optInt("drawCount"), a.o.optInt("drawCount"));
                if (mode == 2) return Long.compare(a.o.optLong("first"), b.o.optLong("first"));
                return Long.compare(b.o.optLong("last"), a.o.optLong("last"));
            }
        });

        // 顶栏：显示 N / 共 M 条
        bar.sub.setText("显示 " + shown.size() + " / 共 " + all.size() + " 条" + bad);
        bar.sub.setVisibility(View.VISIBLE);
        // 图例 + 被隐藏的数量
        StringBuilder lg = new StringBuilder("● 在屏幕上    ○ 不在屏幕上    ? 未知（自绘 / Compose）");
        StringBuilder hd = new StringBuilder();
        if (onlyOnScreen && hiddenOff > 0) hd.append("不在屏幕上 ").append(hiddenOff).append(" 条");
        if (hideCommon && hiddenCommon > 0) hd.append(hd.length() > 0 ? "，" : "").append("公共控件 ").append(hiddenCommon).append(" 条");
        if (hd.length() > 0) lg.append("\n已隐藏：").append(hd);
        tvLegend.setText(lg);
        // 读取概况 / 警告
        String info = infoBase.trim();
        if (info.isEmpty()) {
            tvBanner.setVisibility(View.GONE);
        } else {
            boolean warn = info.contains("⚠") || info.contains("失败") || info.contains("没有记录") || info.contains("格式不对");
            tvBanner.setText(info);
            tvBanner.setTextColor(Ui.col(this, warn ? R.color.warn_text : R.color.text_sub));
            tvBanner.setBackground(Ui.rect(Ui.col(this, warn ? R.color.warn_bg : R.color.surface_alt), Ui.dp(this, 10), 0, 0));
            tvBanner.setVisibility(View.VISIBLE);
        }
        updateEmpty();
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ 列表行（卡片）

    private static final class VH {
        TextView vis, kind, id, count, text, meta, meta2, set, draw;
        LinearLayout thumbs, code;
    }

    private View newRow() {
        FrameLayout outer = new FrameLayout(this);
        outer.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        LinearLayout card = Ui.vertical(this);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        card.setBackground(Ui.ripple(this,
                Ui.rect(Ui.col(this, R.color.surface), Ui.dp(this, 16), Ui.col(this, R.color.outline), Ui.dp(this, 1)),
                Ui.dp(this, 16), false));
        VH h = new VH();

        LinearLayout top = Ui.horizontal(this);
        h.vis = Ui.tv(this, "", 13, R.color.text, true);
        top.addView(h.vis, Ui.lpM(this, -2, -2, 0, 0, 8, 0));
        h.kind = Ui.badge(this, "", 0xFF4C6FFF);
        top.addView(h.kind);
        h.id = Ui.tv(this, "", 12, R.color.text_hint, false);
        top.addView(h.id, Ui.lpM(this, -2, -2, 8, 0, 0, 0));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        h.count = Ui.tv(this, "", 12.5f, R.color.text_sub, false);
        top.addView(h.count);
        card.addView(top);

        h.text = Ui.tv(this, "", 16, R.color.text, true);
        h.text.setMaxLines(2);
        h.text.setEllipsize(TextUtils.TruncateAt.END);
        h.text.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(h.text);

        h.thumbs = Ui.horizontal(this);
        card.addView(h.thumbs);

        h.meta = Ui.tv(this, "", 12.5f, R.color.text_sub, false);
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        h.meta.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(h.meta);
        h.meta2 = Ui.tv(this, "", 12, R.color.text_hint, false);
        h.meta2.setSingleLine(true);
        h.meta2.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(h.meta2);

        h.code = Ui.vertical(this);
        h.code.setBackground(Ui.rect(Ui.col(this, R.color.code_bg), Ui.dp(this, 8), 0, 0));
        h.code.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        h.set = Ui.tv(this, "", 11, R.color.text_sub, false);
        h.set.setTypeface(Typeface.MONOSPACE);
        h.set.setSingleLine(true);
        h.set.setEllipsize(TextUtils.TruncateAt.START);
        h.code.addView(h.set);
        h.draw = Ui.tv(this, "", 11, R.color.text_sub, false);
        h.draw.setTypeface(Typeface.MONOSPACE);
        h.draw.setSingleLine(true);
        h.draw.setEllipsize(TextUtils.TruncateAt.START);
        h.code.addView(h.draw);
        card.addView(h.code, Ui.lpM(this, -1, -2, 0, 8, 0, 0));

        outer.addView(card, new FrameLayout.LayoutParams(-1, -2));
        outer.setTag(h);
        return outer;
    }

    private View row(Snap.Item it, View cv) {
        View v = cv != null && cv.getTag() instanceof VH ? cv : newRow();
        VH h = (VH) v.getTag();
        JSONObject r = it.o;
        String kind = r.optString("kind");

        String vis = r.optString("vis");
        h.vis.setText(RecFormat.visMark(r));
        h.vis.setTextColor(Ui.col(this, "yes".equals(vis) ? R.color.good : "no".equals(vis) ? R.color.text_hint : R.color.warn_text));
        Ui.setBadge(h.kind, RecFormat.kindLabel(kind), Ui.kindColor(kind));
        h.id.setText("#" + r.optInt("id"));
        h.count.setText("×" + r.optInt("drawCount"));

        String samples = RecFormat.samplesLine(r, 18, 4);
        if (samples.length() <= 2) {
            h.text.setText("（没有文字）");
            h.text.setTextColor(Ui.col(this, R.color.text_hint));
        } else {
            h.text.setText(samples);
            h.text.setTextColor(Ui.col(this, R.color.text));
        }

        h.thumbs.removeAllViews();
        JSONArray ta = r.optJSONArray("thumbs");
        if (ta == null || ta.length() == 0) {
            h.thumbs.setVisibility(View.GONE);
        } else {
            h.thumbs.setVisibility(View.VISIBLE);
            for (int k = 0; k < ta.length(); k++) h.thumbs.addView(thumbView(this, ta.optString(k), Ui.dp(this, 40)));
        }

        h.meta.setText("页面 " + RecFormat.pageOf(r) + "  ·  容器 " + RecFormat.containerOf(r));
        String id = r.optString("viewId");
        h.meta2.setText(RecFormat.simple(r.optString("view")) + (id.isEmpty() ? "" : "  id=" + id));

        String setF = topFrame(r.optJSONObject("setStack"));
        String drawF = topFrame(r.optJSONObject("drawStack"));
        boolean hasSet = !"(无)".equals(setF), hasDraw = !"(无)".equals(drawF);
        h.code.setVisibility(hasSet || hasDraw ? View.VISIBLE : View.GONE);
        h.set.setVisibility(hasSet ? View.VISIBLE : View.GONE);
        h.set.setText("设置  " + setF);
        h.draw.setVisibility(hasDraw ? View.VISIBLE : View.GONE);
        h.draw.setText(("TRACE".equals(kind) ? "调用  " : "绘制  ") + drawF);
        return v;
    }

    private static final android.util.LruCache<String, android.graphics.Bitmap> THUMBS = new android.util.LruCache<>(300);

    static android.graphics.Bitmap thumbBitmap(String b64) {
        android.graphics.Bitmap b = THUMBS.get(b64);
        if (b != null) return b;
        try {
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            b = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (b != null) THUMBS.put(b64, b);
        } catch (Throwable ignored) {
        }
        return b;
    }

    /** 缩略图控件：灰底，白色图标也看得见。 */
    static android.widget.ImageView thumbView(android.content.Context ctx, String b64, int sizePx) {
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setBackground(Ui.rect(0xFF9E9E9E, sizePx / 6f, 0, 0));
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(sizePx / 12, sizePx / 12, sizePx / 12, sizePx / 12);
        android.graphics.Bitmap b = thumbBitmap(b64);
        if (b != null) iv.setImageBitmap(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
        lp.rightMargin = sizePx / 8;
        lp.topMargin = sizePx / 5;
        iv.setLayoutParams(lp);
        return iv;
    }

    static String topFrame(JSONObject s) {
        if (s == null) return "(无)";
        JSONArray f = s.optJSONArray("frames");
        if (f == null || f.length() == 0) return "(无)";
        String t = f.optString(0);
        return t.length() > 90 ? "…" + t.substring(t.length() - 90) : t;
    }

    // ------------------------------------------------------------ 菜单 / 帮助 / 清空

    private long markerSeq = System.currentTimeMillis();

    /** 打开任务浮窗：先要“显示在其他应用上层”权限，没有就跳到系统设置。 */
    private void startGuide() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("先给 TextRec 开「显示在其他应用上层」，开完回来再点一次");
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1); // 不给也能用，只是看不到“运行中”通知
        }
        if (GuideService.isRunning()) {
            toast("浮窗已经开着了");
            return;
        }
        startForegroundService(new Intent(this, GuideService.class));
        toast("浮窗已打开：切到目标 App，按浮窗上的提示做");
    }

    private void editTasks() {
        final android.content.SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        LinearLayout body = Ui.vertical(this);
        helpItem(body, "怎么写", "一行一件事：名称|提示|秒数（后两项可省）。名称写 @基线 的是自动计时的基线，秒数是时长。"
                + "每件事开始、结束、重做都会作为标记进目标 App 的时间线。\n"
                + "先启动还是后启动目标 App：没写 ! 的清单，一律是「先启动目标 App、切到它的界面，再在浮窗点开始」；"
                + "第一件的名称前写 !（如 !冷启动抖音），浮窗一打开就直接开始这件事，用于要在目标 App 还没运行时就开始的任务（冷启动）。\n"
                + "点「下一件」时会检查这件事期间目标进程有没有记录到事件，没有就停在这件事上，用黄色字写原因，点「复制原因」发给 Claude。");
        final EditText et = Ui.field(this, "暂停|点一下视频暂停，停 3 秒", true, false);
        et.setText(sp.getString(GuideService.PREF_TASKS, GuideService.DEFAULT_TASKS));
        body.addView(et);
        new Ui.Dlg(this, "任务清单", null).keyboard()
                .body(body, true)
                .button("取消", false, null)
                .button("恢复默认", false, () -> sp.edit().remove(GuideService.PREF_TASKS).apply())
                .button("保存", true, () -> sp.edit().putString(GuideService.PREF_TASKS, et.getText().toString()).apply())
                .show();
    }

    private void askMarker() {
        LinearLayout body = Ui.vertical(this);
        helpItem(body, "标记", "写下接下来要做的事，比如“开始划到下一个视频”“点进评论”。点“记下”后马上去做，时间线里这一笔之后的记录就属于这件事。");
        final EditText et = Ui.field(this, "例如：划到下一个视频", true, false);
        body.addView(et);
        new Ui.Dlg(this, "打标记", null).keyboard()
                .body(body, true)
                .button("取消", false, null)
                .button("记下", true, () -> sendMarker(et.getText().toString().trim().isEmpty() ? "（标记）" : et.getText().toString().trim()))
                .show();
    }

    /** 经 root 把标记写进目标 App 的数据目录，目标进程监听到就记进时间线。 */
    private void sendMarker(final String label) {
        final long seq = ++markerSeq;
        final List<String> pk = Actions.pkgs(getSharedPreferences(Actions.PREFS, MODE_PRIVATE));
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (String pkg : pk) {
                if (!RootShell.validPkg(pkg)) continue;
                RootShell.Result r = RootShell.pushMarker(pkg, seq, label);
                sb.append(r.out.contains("__OK__") ? "✓ " : "✗ ").append(pkg).append(' ');
            }
            final String msg = label + "  " + sb;
            runOnUiThread(() -> toast(msg));
        }).start();
    }

    private void showTimeline() {
        String t = Snap.timelineText;
        if (t.isEmpty()) {
            toast("还没有时间线（先刷新；确认首页「时间线」开关开着）");
            return;
        }
        LinearLayout body = Ui.vertical(this);
        TextView tv = Ui.tv(this, t, 11.5f, R.color.text, false);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.addView(tv);
        new Ui.Dlg(this, "时间线", "导出的 txt / json 里也有这份").body(body, true).button("关闭", true, null).show();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "这些筛选是什么意思");
        pm.getMenu().add(0, 3, 1, "粘贴命令…");
        pm.getMenu().add(0, 8, 2, "任务浮窗（一步一步提示要做什么）");
        pm.getMenu().add(0, 9, 2, "编辑任务清单…");
        pm.getMenu().add(0, 4, 3, "打标记…（时间线上记一笔“现在开始做某某”）");
        pm.getMenu().add(0, 5, 4, "基线：开始（什么都不操作）");
        pm.getMenu().add(0, 6, 5, "基线：结束");
        pm.getMenu().add(0, 7, 6, "查看时间线");
        pm.getMenu().add(0, 2, 7, "清空所有记录…");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showHelp();
            else if (item.getItemId() == 4) askMarker();
            else if (item.getItemId() == 8) startGuide();
            else if (item.getItemId() == 9) editTasks();
            else if (item.getItemId() == 5) sendMarker("基线开始");
            else if (item.getItemId() == 6) sendMarker("基线结束");
            else if (item.getItemId() == 7) showTimeline();
            else if (item.getItemId() == 3) showCmd();
            else confirmClear();
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------ 命令行（一行文本=一套筛选，见 README「命令」一节）

    private static final String[] ZONE_CODES = {"top", "bottom", "right", "bottomleft", "content", "offscreen"};
    private static final String[] HOOK_CODES = {"biz", "widget", "obf", "none"};
    private static final String[] FREQ_CODES = {"once", "low", "mid", "high"};
    private static final String[] REUSE_CODES = {"local", "cross", "global"};
    private static final String[] SHAPE_CODES = {"empty", "duration", "number", "fixed", "dynamic"};
    private static final String[] FRESH_CODES = {"new", "recent", "old"};
    private static final String[] PURPOSE_CODES = {"color", "hide", "text", "skip", "other"};
    private static final String[] BASE_CODES = {"noise", "live", "unknown"};
    private static final String[] TRIG_CODES = {"touch", "frame", "data", "msg", "sync", "none"};
    /** 导出时只给这些编号的记录完整细节，其余一行摘要；空 = 全部完整 */
    private final java.util.HashSet<Integer> fullIds = new java.util.HashSet<>();

    private void showCmd() {
        LinearLayout body = Ui.vertical(this);
        helpItem(body, "命令格式", "一行里空格分隔多个 key=value；没有 = 的词当搜索词（和原搜索框一样，多个词要同时命中）。"
                + "执行会先清空当前所有筛选，再按这行命令重新设置，所以同一行命令每次粘贴结果都一样，不受之前点过什么影响。"
                + "完整 key/value 表见仓库 README「命令」一节，任何 AI 读那一节就能照着拼命令，不需要来问我。");
        final EditText et = Ui.field(this, "kind=ICON container=CommentItemView hook=biz", true, false);
        body.addView(et);
        new Ui.Dlg(this, "粘贴命令", null).keyboard()
                .body(body, true)
                .button("取消", false, null)
                .button("执行", true, () -> runCmd(et.getText().toString()))
                .show();
    }

    /** 在 dim 维度里找一个取值，使其忽略大小写后包含 val；完全相等优先。扫 all，不考虑命令里其它 token 的效果。 */
    private String resolveFree(int dim, String val) {
        ensureScreen();
        ensureAgg();
        String vl = val.toLowerCase(Locale.ROOT);
        String best = null;
        for (Snap.Item it : all) {
            String v = dim < 0 ? RecFormat.pageOf(it.o) : RecFormat.catOf(dim, it.o, scrW, scrH, reuseGroups, maxLast);
            if (v == null || v.isEmpty()) continue;
            if (v.equalsIgnoreCase(val)) return v;
            if (best == null && v.toLowerCase(Locale.ROOT).contains(vl)) best = v;
        }
        return best;
    }

    private boolean applyEnum(int dim, String val, String[] codes) {
        String vl = val.toLowerCase(Locale.ROOT);
        for (String c : codes) {
            if (c.equals(vl)) {
                catVal[dim] = c;
                return true;
            }
        }
        return false;
    }

    private void runCmd(String line) {
        ensureScreen();
        ensureAgg();
        etSearch.setText("");
        pageFilter = "";
        java.util.Arrays.fill(catVal, "");
        kindManual = false;
        applyDefaultKinds();
        onlyOnScreen = false;
        hideCommon = false;
        java.util.ArrayList<String> words = new java.util.ArrayList<>();
        java.util.ArrayList<String> ignored = new java.util.ArrayList<>();
        for (String tok : line.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            int eq = tok.indexOf('=');
            if (eq <= 0) {
                words.add(tok);
                continue;
            }
            String key = tok.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = tok.substring(eq + 1).trim();
            if (val.isEmpty()) continue;
            boolean ok = true;
            switch (key) {
                case "q":
                    words.add(val);
                    break;
                case "page": {
                    String r = resolveFree(-1, val);
                    if (r == null) ok = false; else pageFilter = r;
                    break;
                }
                case "container": {
                    String r = resolveFree(0, val);
                    if (r == null) ok = false; else catVal[0] = r;
                    break;
                }
                case "module": {
                    String r = resolveFree(1, val);
                    if (r == null) ok = false; else catVal[1] = r;
                    break;
                }
                case "view": {
                    String r = resolveFree(2, val);
                    if (r == null) ok = false; else catVal[2] = r;
                    break;
                }
                case "zone":
                    ok = applyEnum(3, val, ZONE_CODES);
                    break;
                case "kind": {
                    kindManual = true;
                    if (val.equalsIgnoreCase("all")) {
                        java.util.Arrays.fill(kindSel, true);
                    } else {
                        int idx = -1;
                        for (int i = 0; i < KIND_KEYS.length && idx < 0; i++) {
                            for (String k : KIND_KEYS[i]) if (k.equalsIgnoreCase(val)) { idx = i; break; }
                        }
                        if (idx < 0) {
                            ok = false;
                        } else {
                            java.util.Arrays.fill(kindSel, false);
                            kindSel[idx] = true;
                        }
                    }
                    break;
                }
                case "hook":
                    ok = applyEnum(5, val, HOOK_CODES);
                    break;
                case "freq":
                    ok = applyEnum(6, val, FREQ_CODES);
                    break;
                case "reuse":
                    ok = applyEnum(7, val, REUSE_CODES);
                    break;
                case "shape":
                    ok = applyEnum(8, val, SHAPE_CODES);
                    break;
                case "fresh":
                    ok = applyEnum(9, val, FRESH_CODES);
                    break;
                case "purpose":
                    ok = applyEnum(10, val, PURPOSE_CODES);
                    break;
                case "baseline":
                    ok = applyEnum(11, val, BASE_CODES);
                    break;
                case "trigger":
                    ok = applyEnum(12, val, TRIG_CODES);
                    break;
                case "tag": {
                    String r = resolveFree(13, val);
                    if (r == null) ok = false; else catVal[13] = r;
                    break;
                }
                case "full": {
                    fullIds.clear();
                    for (String x : val.split("[,;]")) {
                        try {
                            fullIds.add(Integer.parseInt(x.trim()));
                        } catch (Throwable bad) {
                        }
                    }
                    break;
                }
                case "onscreen":
                    onlyOnScreen = val.equals("1") || val.equalsIgnoreCase("true");
                    break;
                case "hidecommon":
                    hideCommon = val.equals("1") || val.equalsIgnoreCase("true");
                    break;
                case "sort": {
                    String v = val.toLowerCase(Locale.ROOT);
                    if (v.startsWith("recent")) sortMode = 0;
                    else if (v.startsWith("count")) sortMode = 1;
                    else if (v.startsWith("first")) sortMode = 2;
                    else ok = false;
                    break;
                }
                default:
                    ok = false;
            }
            if (!ok) ignored.add(tok);
        }
        etSearch.setText(String.join(" ", words));
        updateChips();
        applyFilter();
        if (!ignored.isEmpty()) toast("忽略（没认出来或没匹配到数据）：" + String.join(" ", ignored));
    }

    private void helpItem(LinearLayout body, String title, String desc) {
        TextView t = Ui.tv(this, title, 14.5f, R.color.text, true);
        t.setPadding(0, Ui.dp(this, 10), 0, 0);
        body.addView(t);
        TextView d = Ui.tv(this, desc, 13, R.color.text_sub, false);
        d.setLineSpacing(0, 1.15f);
        d.setPadding(0, Ui.dp(this, 2), 0, 0);
        body.addView(d);
    }

    private void showHelp() {
        LinearLayout body = Ui.vertical(this);
        helpItem(body, "● ○ ?", "● 此刻真的在屏幕上；○ 不在屏幕上（预加载的、已经离开的页面）；? 自绘 / Compose 没有 View 可查，无法判断。");
        helpItem(body, "仅屏幕上", "只显示 ● 和 ?，把 ○ 藏起来。想看全部就关掉。");
        helpItem(body, "隐藏公共控件", "在两个以上页面里位置和文字都一样的控件（底部栏、顶部栏等）。隐藏后更容易看到当前页面独有的内容。");
        helpItem(body, "类型 / 页面", "类型 = 记录是怎么来的（TextView、Compose、图标…）。页面 = 记录发生在哪个页面，可以给页面起名字。");
        helpItem(body, "分类", "按所属容器、代码模块、控件类、屏幕位置、记录类型、hook可靠度、调用频次、复用范围、内容形态、新鲜度缩小范围，几项同时选 = 同时满足。");
        helpItem(body, "搜索", "多个词用空格分隔，都要命中；以 re: 开头按正则匹配。搜的范围包括文字、类名、栈帧、页面。");
        helpItem(body, "长按列表里的一张卡片", "给它所在的页面起名字。");
        new Ui.Dlg(this, "筛选说明", null).body(body, true).button("知道了", true, null).show();
    }

    private void confirmClear() {
        new Ui.Dlg(this, "清空所有记录？", "会清空目标 App 里已记录的全部内容（包括内存里的），并立即重新开始记录。此操作不能撤销。")
                .button("取消", false, null)
                .button("清空", true, () -> {
                    final SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
                    final RecConfig c = Actions.loadCfg(sp);
                    c.epoch = System.currentTimeMillis(); // epoch 变了，目标进程会清空内存里的记录
                    sp.edit().putString("cfg", c.toText()).apply();
                    final List<String> pkgs = Actions.pkgs(sp);
                    new Thread(() -> {
                        Actions.push(pkgs, c, true);
                        runOnUiThread(this::refresh);
                    }).start();
                })
                .show();
    }

    // ------------------------------------------------------------ 导出

    private void showExport() {
        if (shown.isEmpty()) {
            toast("当前没有可导出的记录");
            return;
        }
        final Ui.Dlg d = new Ui.Dlg(this, "导出", "导出当前筛选后的 " + shown.size() + " 条记录（先在列表里筛好再导出）");
        LinearLayout body = Ui.vertical(this);
        body.addView(Ui.actionRow(this, R.drawable.ic_code, "hook 清单（txt）",
                "每个控件只写它是什么和可以 hook 的方法，最适合直接拿去写模块", true, v -> {
                    d.dismiss();
                    export(REQ_HOOK);
                }));
        body.addView(Ui.actionRow(this, R.drawable.ic_file, "完整记录（txt）",
                "含设置栈、绘制栈等全部细节，适合发给别人分析", false, v -> {
                    d.dismiss();
                    export(REQ_TXT);
                }));
        body.addView(Ui.actionRow(this, R.drawable.ic_list, "数据文件（JSON）",
                "字段固定，含图标缩略图，适合程序读取", false, v -> {
                    d.dismiss();
                    export(REQ_JSON);
                }));
        body.addView(Ui.actionRow(this, R.drawable.ic_image, "网页（HTML，含图标）",
                "用浏览器打开，直接看到每个图标长什么样", false, v -> {
                    d.dismiss();
                    export(REQ_HTML);
                }));
        body.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 8, 0, 0));
        Ui.Sw lean = Ui.switchRow(this, "精简模式（仅完整 txt）", "不写绘制栈和文字颜色栈，文件小很多");
        lean.sw.setChecked(RecFormat.LEAN);
        lean.sw.setOnCheckedChangeListener((bv, on) -> RecFormat.LEAN = on);
        body.addView(lean.row);
        d.body(body, true).button("取消", false, null).show();
    }

    /** 导出顺序：选了某个页面就是当前列表顺序；选“全部页面”就按页面名分组（组内保持当前排序）。 */
    private List<Snap.Item> exportOrder() {
        ArrayList<Snap.Item> out = new ArrayList<>(shown);
        if (pageFilter.isEmpty()) {
            final java.util.LinkedHashMap<String, Integer> rank = new java.util.LinkedHashMap<>();
            for (Snap.Item it : out) {
                String p = RecFormat.pageOf(it.o);
                if (!rank.containsKey(p)) rank.put(p, rank.size());
            }
            Collections.sort(out, (a, b) -> Integer.compare(rank.get(RecFormat.pageOf(a.o)), rank.get(RecFormat.pageOf(b.o)))); // 稳定排序
        }
        return out;
    }

    /** hook 清单：每个控件只写“它是什么 + 可以 hook 哪里”，没有栈、画笔、父级链。按页面分组。 */
    private String buildHookList() {
        StringBuilder sb = new StringBuilder();
        StringBuilder entries = new StringBuilder();
        int skipped = 0, written = 0;
        String curPage = null;
        final java.util.HashMap<String, java.util.List<Integer>> use = new java.util.HashMap<>();
        for (Snap.Item it : exportOrder()) RecFormat.collectHooks(it, use);
        for (Snap.Item it : exportOrder()) {
            String e = RecFormat.hookEntry(it, use);
            if (e == null) {
                skipped++;
                continue;
            }
            String pg = RecFormat.pageOf(it.o);
            if (!pg.equals(curPage)) {
                curPage = pg;
                entries.append("\n████ 页面：").append(pg).append(" ████\n\n");
            }
            entries.append(e).append('\n');
            written++;
        }
        sb.append("TextRec hook 清单  ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("  共 ").append(written).append(" 条");
        if (skipped > 0) sb.append("（另有 ").append(skipped).append(" 条没有可用 hook，已略过）");
        sb.append('\n');
        sb.append("筛选：类型=").append(kindSummary()).append("  页面=").append(pageFilter.isEmpty() ? "全部" : pageFilter)
                .append(catSummary().isEmpty() ? "" : "  分类=" + catSummary())
                .append("  只看屏幕上=").append(onlyOnScreen ? "是" : "否");
        if (hideCommon) sb.append("  已隐藏公共控件=").append(hiddenCommon);
        sb.append('\n');
        if (!infoBase.isEmpty()) sb.append(infoBase);
        sb.append("说明：[稳定]=类名和方法名都没混淆；[混淆]=只对记录时的这个版本有效，换版本重新记录一次；栈N=离设置/绘制点第几层，越小越精确。\n");
        sb.append("⚠ 共用×N = 同一个 hook 出现在 N 条记录里，说明它是通用入口，hook 后要靠参数 / 文字 / id 再区分。\n");
        sb.append("“系统方法”是兜底，不会被混淆，但同类控件都会进来，必须按 View 类 / id（/文字）过滤。\n");
        sb.append(entries);
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 带图标图片的网页：txt 放不了图，用浏览器打开这个文件就能一眼看出每条记录是哪个图标。 */
    private String buildHtml() {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>TextRec</title><style>body{font:13px sans-serif;margin:8px}table{border-collapse:collapse;width:100%}")
                .append("td,th{border:1px solid #bbb;padding:4px;vertical-align:top}img{width:56px;height:56px;object-fit:contain;")
                .append("background:#9e9e9e;margin-right:4px}.g{background:#e8eef7;font-weight:bold}code{font-size:11px;word-break:break-all}</style>");
        sb.append("<h3>TextRec 导出 ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("　类型=").append(esc(kindSummary())).append("　页面=").append(esc(pageFilter.isEmpty() ? "全部" : pageFilter))
                .append("　共 ").append(shown.size()).append(" 条</h3>");
        sb.append("<table><tr><th>#</th><th>图标</th><th>类型</th><th>文字 / 描述</th><th>View</th><th>设置位置</th><th>次数</th></tr>");
        String cur = null;
        for (Snap.Item it : exportOrder()) {
            JSONObject r = it.o;
            String pg = RecFormat.pageOf(r);
            if (pageFilter.isEmpty() && !pg.equals(cur)) {
                cur = pg;
                sb.append("<tr><td class=g colspan=7>页面：").append(esc(pg)).append("（").append(countPage(pg)).append(" 条）</td></tr>");
            }
            sb.append("<tr><td>").append(r.optInt("id")).append("</td><td>");
            JSONArray ta = r.optJSONArray("thumbs");
            if (ta != null) for (int i = 0; i < ta.length(); i++) {
                sb.append("<img src='data:image/png;base64,").append(ta.optString(i)).append("'>");
            }
            String id = r.optString("viewId");
            sb.append("</td><td>").append(esc(RecFormat.kindLabel(r.optString("kind")))).append("</td><td>")
                    .append(esc(RecFormat.samplesLine(r, 40, 4))).append("<br><code>").append(esc(r.optString("lastText"))).append("</code></td><td>")
                    .append(esc(RecFormat.simple(r.optString("view")))).append(id.isEmpty() ? "" : " id=" + esc(id)).append("</td><td><code>")
                    .append(esc(topFrame(r.optJSONObject("setStack")))).append("</code></td><td>").append(r.optInt("drawCount")).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private int countPage(String page) {
        int n = 0;
        for (Snap.Item it : shown) if (page.equals(RecFormat.pageOf(it.o))) n++;
        return n;
    }

    private void export(int req) {
        if (shown.isEmpty()) {
            toast("没有可导出的内容");
            return;
        }
        String content;
        if (req == REQ_TXT) {
            StringBuilder sb = new StringBuilder();
            sb.append("TextRec 导出  ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    .append("  共 ").append(shown.size()).append(" 条\n");
            sb.append("筛选：只看屏幕上=").append(onlyOnScreen ? "是" : "否");
            if (onlyOnScreen) {
                sb.append("（已隐藏 ").append(hiddenOff).append(" 条不在屏幕上的")
                        .append(hiddenOff > 0 ? "：" + hiddenIds.trim() : "").append("）");
            }
            sb.append("  类型=").append(kindSummary());
            sb.append("  页面=").append(pageFilter.isEmpty() ? "全部" : pageFilter);
            if (!catSummary().isEmpty()) sb.append("  分类=").append(catSummary());
            if (hideCommon) sb.append("  已隐藏公共控件=").append(hiddenCommon);
            sb.append(RecFormat.LEAN ? "  精简导出" : "");
            String qs = etSearch.getText().toString().trim();
            if (!qs.isEmpty()) sb.append("  搜索=「").append(qs).append("」");
            sb.append("  （全部记录 ").append(all.size()).append(" 条）\n");
            if (!infoBase.isEmpty()) sb.append(infoBase);
            if (!Snap.timelineText.isEmpty()) sb.append("\n══ 时间线 ══\n").append(Snap.timelineText).append("══ 时间线结束 ══\n");
            if (!fullIds.isEmpty()) sb.append("完整细节只给编号 ").append(fullIds).append(" 的记录，其余只有一行摘要（命令 full=编号,编号）\n");
            sb.append('\n');
            RecFormat.Dedup dd = new RecFormat.Dedup(); // 同一份栈 / 签名只完整写一次
            String curPage = null;
            for (Snap.Item it : exportOrder()) {
                String pg = RecFormat.pageOf(it.o);
                if (pageFilter.isEmpty() && !pg.equals(curPage)) {
                    curPage = pg;
                    sb.append("\n████ 页面：").append(pg).append("（").append(countPage(pg)).append(" 条）████\n\n");
                }
                if (!fullIds.isEmpty() && !fullIds.contains(it.o.optInt("id"))) {
                    sb.append(RecFormat.oneLineSummary(it.o)).append('\n');
                    continue;
                }
                sb.append(RecFormat.full(it, dd)).append("\n==========================================\n\n");
            }
            content = sb.toString();
        } else if (req == REQ_HOOK) {
            content = buildHookList();
        } else if (req == REQ_HTML) {
            content = buildHtml();
        } else {
            try {
                JSONObject o = new JSONObject();
                o.put("schema", 1);
                o.put("exportTime", System.currentTimeMillis());
                JSONArray inf = new JSONArray();
                for (String s : infoBase.split("\n")) if (!s.trim().isEmpty()) inf.put(s);
                o.put("info", inf);
                o.put("timelineText", Snap.timelineText);
                JSONObject fo = new JSONObject();
                fo.put("onlyOnScreen", onlyOnScreen);
                fo.put("hiddenOffScreen", hiddenOff);
                fo.put("hiddenIds", hiddenIds.trim());
                fo.put("kind", kindSummary());
                fo.put("page", pageFilter.isEmpty() ? "全部" : pageFilter);
                fo.put("query", etSearch.getText().toString().trim());
                fo.put("totalRecords", all.size());
                o.put("filter", fo);
                JSONArray arr = new JSONArray();
                for (Snap.Item it : exportOrder()) {
                    it.o.put("pageLabel", RecFormat.pageOf(it.o));
                    arr.put(it.o);
                }
                o.put("records", arr);
                content = o.toString(2);
            } catch (Throwable t) {
                Toast.makeText(this, "生成 JSON 失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        pendingExport = content;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(req == REQ_TXT || req == REQ_HOOK ? "text/plain" : req == REQ_HTML ? "text/html" : "application/json");
        i.putExtra(Intent.EXTRA_TITLE, "textrec-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + (req == REQ_TXT || req == REQ_HOOK ? ".txt" : req == REQ_HTML ? ".html" : ".json"));
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_TXT && req != REQ_JSON && req != REQ_HTML && req != REQ_HOOK) || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String content = pendingExport;
        if (content == null) return;
        new Thread(() -> {
            String msg;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.flush();
                msg = "已导出 " + content.length() + " 个字符";
            } catch (Throwable t) {
                msg = "导出失败：" + t.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }).start();
    }
}
