package com.textrec.lsp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 详情：设置栈（左）和绘制栈（右）并排，可横向滑动；一键复制可 hook 的方法签名。 */
public class DetailActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Snap.Item it = Snap.current;
        if (it == null) {
            finish();
            return;
        }
        setTitle("记录 #" + it.o.optInt("id"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(btn("复制全部", v -> copy("textrec", RecFormat.full(it))), w1());
        bar.addView(btn("只复制可hook签名", v -> copy("textrec-sigs", RecFormat.sigsOnly(it))), w1());
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        org.json.JSONArray ta = it.o.optJSONArray("thumbs");
        if (ta != null && ta.length() > 0) {
            LinearLayout tr = new LinearLayout(this);
            tr.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < ta.length(); k++) tr.addView(RecordsActivity.thumbView(this, ta.optString(k), dp(96)));
            body.addView(tr);
        }
        TextView head = mono(RecFormat.header(it), 12);
        body.addView(head);

        // 并排两栏：每栏固定宽度，整体可横向滑动
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(cols);
        TextView left = mono(RecFormat.setColumn(it), 11);
        TextView right = mono(RecFormat.drawColumn(it), 11);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(dp(420), -2);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(420), -2);
        lp2.leftMargin = dp(12);
        cols.addView(left, lp1);
        cols.addView(right, lp2);
        body.addView(hs);

        setContentView(root);
    }

    private TextView mono(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private void copy(String label, String s) {
        if (s.length() > 90_000) s = s.substring(0, 90_000);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, s));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    private Button btn(String t, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
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
