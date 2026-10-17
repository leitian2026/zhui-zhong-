package com.textrec.lsp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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

/** 记录列表：一个位置一行，可搜索（文字 / 类名 / 栈帧 / 页面），可排序，可导出 txt 和 JSON。 */
public class RecordsActivity extends Activity {
    private static final int REQ_TXT = 2001, REQ_JSON = 2002;
    private static final String[] SORTS = {"最近", "次数", "首次"};
    private static final String[] KINDS = {"全部", "TextView", "自绘", "Compose", "图标", "方法追踪", "仅设置", "状态变化"};
    private static final String[] KIND_KEYS = {"", "TEXTVIEW", "CANVAS", "COMPOSE", "ICON", "TRACE", "TEXTSET", "STATE"};

    private ListView lv;
    private EditText etSearch;
    private TextView tvInfo;
    private Button btnSort, btnKind, btnPage;
    private CheckBox cbLean, cbCommon;
    private int hiddenCommon;
    /** 页面筛选：空 = 全部页面；否则只看（并只导出）这个页面的记录 */
    private String pageFilter = "";
    private CheckBox cbOnScreen;
    private final List<Snap.Item> all = new ArrayList<>();
    private final List<Snap.Item> shown = new ArrayList<>();
    private volatile String infoBase = "";
    /** 当前筛选下被“只看屏幕上”隐藏的记录数和编号——导出时写进文件头，免得看文件的人以为是漏记 */
    private int hiddenOff;
    private String hiddenIds = "";
    private int sortMode = 0, kindMode = 1; // 默认只看 TextView
    private final AtomicBoolean loading = new AtomicBoolean();
    private String pendingExport;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("记录");
        loadAlias();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(btn("刷新", v -> refresh()), w1());
        btnSort = btn("排序:" + SORTS[0], v -> {
            sortMode = (sortMode + 1) % SORTS.length;
            btnSort.setText("排序:" + SORTS[sortMode]);
            applyFilter();
        });
        bar.addView(btnSort, w1());
        btnKind = btn("类型:" + KINDS[kindMode], v -> {
            kindMode = (kindMode + 1) % KINDS.length;
            btnKind.setText("类型:" + KINDS[kindMode]);
            applyFilter();
        });
        bar.addView(btnKind, w1());
        root.addView(bar);

        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.addView(btn("导出txt", v -> export(REQ_TXT)), w1());
        bar2.addView(btn("导出JSON", v -> export(REQ_JSON)), w1());
        bar2.addView(btn("清空", v -> confirmClear()), w1());
        root.addView(bar2);

        btnPage = btn("页面：全部（点这里选择，长按条目可命名）", v -> choosePage());
        root.addView(btnPage);

        // 默认只看此刻在屏幕上的文字：预加载的、已离开页面的不显示；自绘/Compose 可见性未知，照常显示
        cbOnScreen = new CheckBox(this);
        cbOnScreen.setText("只看屏幕上的文字（● 在屏幕上   ○ 不在   ? 未知）");
        cbOnScreen.setTextSize(13);
        cbOnScreen.setChecked(true);
        cbOnScreen.setOnClickListener(v -> applyFilter());
        root.addView(cbOnScreen);

        cbCommon = new CheckBox(this);
        cbCommon.setText("隐藏公共控件（多个页面里位置和文字都相同的，如底部栏）");
        cbCommon.setTextSize(13);
        cbCommon.setChecked(true);
        cbCommon.setOnClickListener(v -> applyFilter());
        root.addView(cbCommon);

        cbLean = new CheckBox(this);
        cbLean.setText("精简导出（不写绘制栈、颜色栈）");
        cbLean.setTextSize(13);
        cbLean.setChecked(true);
        cbLean.setOnClickListener(v -> RecFormat.LEAN = cbLean.isChecked());
        root.addView(cbLean);
        RecFormat.LEAN = true;

        etSearch = new EditText(this);
        etSearch.setHint("搜索：文字 / 类名 / 栈帧 / 页面（空格分隔多个词；re: 开头按正则）");
        etSearch.setTextSize(13);
        etSearch.setSingleLine(true);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) { applyFilter(); }
        });
        root.addView(etSearch);

        tvInfo = new TextView(this);
        tvInfo.setTextSize(11);
        root.addView(tvInfo);

        lv = new ListView(this);
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
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    /** 每次回到本页自动读取一次；没有后台轮询。 */
    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------ 加载

    private void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        tvInfo.setText("正在读取…（首次会弹出 root 授权）");
        final SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        final List<String> pkgs = Actions.pkgs(sp);
        new Thread(() -> {
            final List<Snap.Item> items = new ArrayList<>();
            final List<String> infos = new ArrayList<>();
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
                all.clear();
                all.addAll(items);
                infoBase = ib.toString();
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

    // ------------------------------------------------------------ 页面别名

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
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(RecFormat.aliasOfRaw(raw).equals(raw) ? "" : RecFormat.aliasOfRaw(raw));
        et.setHint("起个名字，如：消息（留空=用原名）");
        new AlertDialog.Builder(this)
                .setTitle("给 " + raw + " 命名")
                .setMessage("多个页面起同一个名字，会合成一类。")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.isEmpty()) RecFormat.ALIAS.remove(raw);
                    else RecFormat.ALIAS.put(raw, n);
                    saveAlias();
                    pageFilter = ""; // 名字变了，旧筛选可能失效
                    btnPage.setText("页面：全部（点这里选择，长按条目可命名）");
                    applyFilter();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------ 页面选择

    private void choosePage() {
        final java.util.LinkedHashMap<String, Integer> cnt = new java.util.LinkedHashMap<>(); // 原始页面名 → 条数
        for (Snap.Item it : all) {
            String p = RecFormat.rawPageOf(it.o);
            Integer n = cnt.get(p);
            cnt.put(p, n == null ? 1 : n + 1);
        }
        if (cnt.isEmpty()) {
            Toast.makeText(this, "还没有记录，先刷新", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] raws = new String[cnt.size()];
        final String[] labels = new String[cnt.size() + 1];
        labels[0] = "全部页面（" + all.size() + " 条，导出时按页面分组）";
        int i = 0;
        for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
            raws[i] = e.getKey();
            String nm = RecFormat.aliasOfRaw(e.getKey());
            labels[i + 1] = nm + (nm.equals(e.getKey()) ? "" : "（" + e.getKey().replace("MainActivity > ", "") + "）")
                    + "  " + e.getValue() + " 条";
            i++;
        }
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择页面（点选=只看/只导出；长按=命名）")
                .setItems(labels, (d, which) -> {
                    pageFilter = which == 0 ? "" : RecFormat.aliasOfRaw(raws[which - 1]);
                    btnPage.setText(pageFilter.isEmpty() ? "页面：全部（点这里选择，长按条目可命名）" : "页面：" + pageFilter);
                    applyFilter();
                })
                .create();
        dlg.show();
        dlg.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos > 0 && pos - 1 < raws.length) {
                dlg.dismiss();
                renamePage(raws[pos - 1]);
            }
            return true;
        });
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
        String kindKey = KIND_KEYS[kindMode];
        boolean onlyOnScreen = cbOnScreen.isChecked();
        int hidden = 0;
        StringBuilder hid = new StringBuilder();
        shown.clear();
        final java.util.HashSet<String> common = cbCommon.isChecked() ? commonKeys() : null;
        int hiddenC = 0;
        for (Snap.Item it : all) {
            if (common != null && common.contains(commonKey(it.o))) {
                if (kindKey.isEmpty() || kindKey.equals(it.o.optString("kind"))) hiddenC++;
                continue;
            }
            if (!kindKey.isEmpty() && !kindKey.equals(it.o.optString("kind"))) continue;
            if (!pageFilter.isEmpty() && !pageFilter.equals(RecFormat.pageOf(it.o))) continue;
            // 选了“仅设置”类型就是要看它们（它们本来就不在屏幕上），不再套“只看屏幕上”
            if (onlyOnScreen && !"TEXTSET".equals(kindKey) && "no".equals(it.o.optString("vis"))) {
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
        tvInfo.setText(infoBase + "显示 " + shown.size() + " / 共 " + all.size() + " 条"
                + (onlyOnScreen && hiddenOff > 0 ? "（已隐藏 " + hiddenOff + " 条不在屏幕上的）" : "")
                + (hiddenCommon > 0 ? "（已隐藏公共控件 " + hiddenCommon + " 条）" : "") + bad);
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ 列表行

    private View row(Snap.Item it, View cv) {
        LinearLayout row;
        TextView t1, t2, t3;
        if (cv instanceof LinearLayout && cv.getTag() != null) {
            row = (LinearLayout) cv;
            t1 = (TextView) row.getChildAt(0);
            t2 = (TextView) row.getChildAt(1);
            t3 = (TextView) row.getChildAt(2);
        } else {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(8), dp(6), dp(8));
            t1 = new TextView(this);
            t1.setTextSize(14);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t2 = new TextView(this);
            t2.setTextSize(12);
            t3 = new TextView(this);
            t3.setTextSize(11);
            t3.setTypeface(Typeface.MONOSPACE);
            t3.setTextColor(Color.GRAY);
            row.addView(t1);
            row.addView(t2);
            row.addView(t3);
            row.setTag("row");
        }
        JSONObject r = it.o;
        t1.setText(RecFormat.visMark(r) + " #" + r.optInt("id") + " [" + RecFormat.kindLabel(r.optString("kind")) + "] "
                + RecFormat.samplesLine(r, 10, 4) + " ×" + r.optInt("drawCount"));
        String id = r.optString("viewId");
        t2.setText(RecFormat.simple(r.optString("view")) + (id.isEmpty() ? "" : " id=" + id)
                + "   页面 " + RecFormat.pageOf(r));
        t3.setText("设: " + topFrame(r.optJSONObject("setStack")) + "\n画: " + topFrame(r.optJSONObject("drawStack")));
        return row;
    }

    private static String topFrame(JSONObject s) {
        if (s == null) return "(无)";
        JSONArray f = s.optJSONArray("frames");
        if (f == null || f.length() == 0) return "(无)";
        String t = f.optString(0);
        return t.length() > 90 ? "…" + t.substring(t.length() - 90) : t;
    }

    // ------------------------------------------------------------ 导出 / 清空

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

    private int countPage(String page) {
        int n = 0;
        for (Snap.Item it : shown) if (page.equals(RecFormat.pageOf(it.o))) n++;
        return n;
    }

    private void export(int req) {
        if (shown.isEmpty()) {
            Toast.makeText(this, "没有可导出的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        String content;
        if (req == REQ_TXT) {
            StringBuilder sb = new StringBuilder();
            sb.append("TextRec 导出  ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                    .append("  共 ").append(shown.size()).append(" 条\n");
            sb.append("筛选：只看屏幕上=").append(cbOnScreen.isChecked() ? "是" : "否");
            if (cbOnScreen.isChecked()) {
                sb.append("（已隐藏 ").append(hiddenOff).append(" 条不在屏幕上的")
                        .append(hiddenOff > 0 ? "：" + hiddenIds.trim() : "").append("）");
            }
            sb.append("  类型=").append(KINDS[kindMode]);
            sb.append("  页面=").append(pageFilter.isEmpty() ? "全部" : pageFilter);
            if (cbCommon.isChecked()) sb.append("  已隐藏公共控件=").append(hiddenCommon);
            sb.append(RecFormat.LEAN ? "  精简导出" : "");
            String qs = etSearch.getText().toString().trim();
            if (!qs.isEmpty()) sb.append("  搜索=「").append(qs).append("」");
            sb.append("  （全部记录 ").append(all.size()).append(" 条）\n");
            if (!infoBase.isEmpty()) sb.append(infoBase);
            sb.append('\n');
            RecFormat.Dedup dd = new RecFormat.Dedup(); // 同一份栈 / 签名只完整写一次
            String curPage = null;
            for (Snap.Item it : exportOrder()) {
                String pg = RecFormat.pageOf(it.o);
                if (pageFilter.isEmpty() && !pg.equals(curPage)) {
                    curPage = pg;
                    sb.append("\n████ 页面：").append(pg).append("（").append(countPage(pg)).append(" 条）████\n\n");
                }
                sb.append(RecFormat.full(it, dd)).append("\n==========================================\n\n");
            }
            content = sb.toString();
        } else {
            try {
                JSONObject o = new JSONObject();
                o.put("schema", 1);
                o.put("exportTime", System.currentTimeMillis());
                JSONArray inf = new JSONArray();
                for (String s : infoBase.split("\n")) if (!s.trim().isEmpty()) inf.put(s);
                o.put("info", inf);
                JSONObject fo = new JSONObject();
                fo.put("onlyOnScreen", cbOnScreen.isChecked());
                fo.put("hiddenOffScreen", hiddenOff);
                fo.put("hiddenIds", hiddenIds.trim());
                fo.put("kind", KINDS[kindMode]);
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
        i.setType(req == REQ_TXT ? "text/plain" : "application/json");
        i.putExtra(Intent.EXTRA_TITLE, "textrec-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + (req == REQ_TXT ? ".txt" : ".json"));
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != REQ_TXT && req != REQ_JSON) || res != RESULT_OK || data == null || data.getData() == null) return;
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

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("清空目标 App 里的所有记录（包括内存里的），并重新开始记录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
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

    // ------------------------------------------------------------ 小工具

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(13);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams w1() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
