package com.textrec.lsp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 详情：上面是这条记录的概要卡片，中间用页签切换「概览 / 设置栈 / 绘制栈 / hook 签名」，
 * 底部固定两个复制按钮（复制可 hook 签名是主按钮）。
 */
public class DetailActivity extends Activity {
    private Snap.Item item;
    private TextView body;
    private final String[] cache = new String[4];
    private ScrollView sv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        item = Snap.current;
        if (item == null) {
            finish();
            return;
        }
        final Snap.Item it = item;
        final JSONObject r = it.o;
        final String kind = r.optString("kind");
        setTitle("记录 #" + r.optInt("id"));

        LinearLayout page = Ui.vertical(this);
        page.setBackgroundColor(Ui.col(this, R.color.bg));
        Ui.Bar bar = Ui.topBar(this, "记录 #" + r.optInt("id"),
                RecFormat.kindLabel(kind) + "  ·  " + RecFormat.pageOf(r), true);
        page.addView(bar.root);

        sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout content = Ui.vertical(this);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 24));
        sv.addView(content);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- 概要卡片
        LinearLayout card = Ui.card(this);
        LinearLayout tags = Ui.horizontal(this);
        tags.addView(Ui.badge(this, RecFormat.kindLabel(kind), Ui.kindColor(kind)));
        String vis = r.optString("vis");
        String visText = "yes".equals(vis) ? "● 在屏幕上" : "no".equals(vis) ? "○ 不在屏幕上" : "? 是否可见未知";
        int visColor = Ui.col(this, "yes".equals(vis) ? R.color.good : "no".equals(vis) ? R.color.text_hint : R.color.warn_text);
        tags.addView(Ui.badge(this, visText, visColor), Ui.lpM(this, -2, -2, 8, 0, 0, 0));
        tags.addView(Ui.badge(this, "×" + r.optInt("drawCount"), Ui.col(this, R.color.text_sub)), Ui.lpM(this, -2, -2, 8, 0, 0, 0));
        card.addView(tags);

        String samples = RecFormat.samplesLine(r, 60, 8);
        TextView main = Ui.tv(this, samples.length() <= 2 ? "（没有文字）" : samples, 18, samples.length() <= 2 ? R.color.text_hint : R.color.text, true);
        main.setTextIsSelectable(true);
        main.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        card.addView(main);

        JSONArray ta = r.optJSONArray("thumbs");
        if (ta != null && ta.length() > 0) {
            LinearLayout tr = Ui.horizontal(this);
            for (int k = 0; k < ta.length(); k++) tr.addView(RecordsActivity.thumbView(this, ta.optString(k), Ui.dp(this, 72)));
            card.addView(tr);
        }

        card.addView(Ui.divider(this), Ui.lpM(this, -1, Ui.dp(this, 1), 0, 12, 0, 6));
        card.addView(kv("页面", RecFormat.pageOf(r)));
        card.addView(kv("容器", RecFormat.containerOf(r)));
        String id = r.optString("viewId");
        card.addView(kv("控件", RecFormat.simple(r.optString("view")) + (id.isEmpty() ? "" : "  id=" + id)));
        card.addView(kv("时间", "首次 " + RecFormat.time(r.optLong("first")) + "   末次 " + RecFormat.time(r.optLong("last"))));
        card.addView(kv("进程", it.proc));
        content.addView(card);

        // ---- 页签
        String third = "TRACE".equals(kind) ? "调用栈" : "绘制栈";
        Ui.Seg seg = Ui.segmented(this, new String[]{"概览", "设置栈", third, "hook 签名"}, 0, this::showTab);
        content.addView(seg.root, Ui.lpM(this, -1, -2, 0, 16, 0, 10));

        TextView hint = Ui.tv(this, "", 12.5f, R.color.text_sub, false);
        hint.setPadding(Ui.dp(this, 2), 0, 0, Ui.dp(this, 8));
        content.addView(hint);

        LinearLayout codeBox = Ui.vertical(this);
        codeBox.setBackground(Ui.rect(Ui.col(this, R.color.code_bg), Ui.dp(this, 14), Ui.col(this, R.color.outline), Ui.dp(this, 1)));
        codeBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        body = Ui.tv(this, "", 12, R.color.text, false);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.1f);
        codeBox.addView(body);
        content.addView(codeBox);
        this.tabHint = hint;

        // ---- 底部操作栏
        LinearLayout bottom = Ui.bottomBar(this);
        TextView copyAll = Ui.button(this, "复制全部", Ui.OUTLINE, v -> copy("textrec", RecFormat.full(it)));
        bottom.addView(copyAll, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView copySigs = Ui.button(this, "复制可 hook 签名", Ui.PRIMARY, v -> copy("textrec-sigs", RecFormat.sigsOnly(it)));
        LinearLayout.LayoutParams lpS = Ui.lpM(this, 0, -2, 10, 0, 0, 0);
        lpS.weight = 1.5f;
        bottom.addView(copySigs, lpS);
        page.addView(Ui.divider(this));
        page.addView(bottom);

        setContentView(page);
        showTab(0);
    }

    private TextView tabHint;

    private static final String[] HINTS = {
            "这条记录的概况、推荐的 hook 点、所在控件层级。",
            "这段文字 / 图标是被哪段代码设置出来的（调用栈，最上面一行离设置点最近）。",
            "这段文字是被哪段代码画到屏幕上的；方法追踪记录则是“谁调用了它”。",
            "可以直接拿去 hook 的方法签名：稳定的排在前面，混淆的换版本会变。"};

    private void showTab(int i) {
        if (cache[i] == null) {
            switch (i) {
                case 0:
                    cache[i] = RecFormat.header(item);
                    break;
                case 1:
                    cache[i] = RecFormat.setColumn(item);
                    break;
                case 2:
                    cache[i] = RecFormat.drawColumn(item);
                    break;
                default:
                    cache[i] = RecFormat.sigsOnly(item);
                    break;
            }
        }
        body.setText(cache[i]);
        tabHint.setText(HINTS[i]);
    }

    /** 一行“名称  内容”，名称固定宽度灰色，内容可选中复制。 */
    private View kv(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        TextView kt = Ui.tv(this, k, 13, R.color.text_hint, false);
        row.addView(kt, new LinearLayout.LayoutParams(Ui.dp(this, 44), -2));
        TextView vt = Ui.tv(this, v, 13.5f, R.color.text, false);
        vt.setTextIsSelectable(true);
        row.addView(vt, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private void copy(String label, String s) {
        if (s.length() > 90_000) s = s.substring(0, 90_000);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, s));
        Toast.makeText(this, "已复制（" + s.length() + " 字）", Toast.LENGTH_SHORT).show();
    }
}
