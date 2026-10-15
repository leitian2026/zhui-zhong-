package com.tracelog.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    static final String PREFS = "ui";
    static final String DEFAULT_PKG = "com.ss.android.ugc.aweme";

    private EditText etPkgs, etKeywords, etColorFilter, etClasses, etDepth, etMax;
    private CheckBox cbEnabled, cbText, cbColor, cbClassStack;
    private TextView tvStatus;
    private Button btnPush;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("TraceLog 调用记录");
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root, new ViewGroup.LayoutParams(-1, -2));
        setContentView(sv);

        TextView intro = text("使用步骤：\n"
                + "1. LSPosed 里启用本模块，作用域勾选目标 App（默认已推荐抖音），然后强杀目标 App。\n"
                + "2. 在下面填好目标包名和关键字，点「保存并推送配置」（需要 root 授权）。\n"
                + "3. 回到目标 App，发一条包含关键字的测试弹幕 / 触发要追踪的功能。\n"
                + "4. 回来点「查看日志」。日志里的调用栈就是弹幕相关的代码位置。", 13);
        root.addView(intro);

        root.addView(label("目标 App 包名（每行一个）"));
        etPkgs = edit(true, "com.ss.android.ugc.aweme");
        root.addView(etPkgs);

        cbEnabled = check("总开关（关闭后目标 App 不再记录）");
        root.addView(cbEnabled);

        root.addView(label("文本追踪"));
        cbText = check("追踪文本绘制 / 设置（Canvas.drawText、TextView.setText）");
        root.addView(cbText);
        root.addView(label("关键字（逗号分隔；出现这些文字时记录调用栈。留空=抽样记录各处文本绘制）"));
        etKeywords = edit(false, "例如你发的测试弹幕：ZZTEST123");
        root.addView(etKeywords);

        root.addView(label("颜色追踪"));
        cbColor = check("追踪颜色设置（Paint.setColor 等，会让目标 App 变慢，用完关掉）");
        root.addView(cbColor);
        root.addView(label("颜色栈过滤（逗号分隔；调用栈里包含这些字才记录。留空=全记，强烈建议先填弹幕相关的类名片段）"));
        etColorFilter = edit(false, "例如：com.bytedance.danmaku");
        root.addView(etColorFilter);

        root.addView(label("指定类追踪（每行一个：类名 或 类名#方法名；记录每次调用的参数和返回值）"));
        etClasses = edit(true, "例如：a.b.c 或 a.b.c#d");
        root.addView(etClasses);
        cbClassStack = check("类追踪时同时记录调用栈");
        root.addView(cbClassStack);

        root.addView(label("调用栈深度（1-60）"));
        etDepth = edit(false, "20");
        etDepth.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(etDepth);
        root.addView(label("每个方法最多记录次数（1-5000）"));
        etMax = edit(false, "100");
        etMax.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(etMax);

        btnPush = new Button(this);
        btnPush.setText("保存并推送配置");
        btnPush.setOnClickListener(v -> save(true));
        root.addView(btnPush);

        Button btnLog = new Button(this);
        btnLog.setText("查看日志 / 导出");
        btnLog.setOnClickListener(v -> {
            save(false);
            startActivity(new Intent(this, LogActivity.class));
        });
        root.addView(btnLog);

        tvStatus = text("", 13);
        root.addView(tvStatus);

        load();
    }

    private void load() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        etPkgs.setText(sp.getString("pkgs", DEFAULT_PKG));
        TraceConfig c = TraceConfig.fromText(sp.getString("cfg", ""));
        cbEnabled.setChecked(c.enabled);
        cbText.setChecked(c.traceText);
        cbColor.setChecked(c.traceColor);
        cbClassStack.setChecked(c.classStack);
        etKeywords.setText(c.keywords);
        etColorFilter.setText(c.colorFilter);
        etClasses.setText(c.classes);
        etDepth.setText(String.valueOf(c.stackDepth));
        etMax.setText(String.valueOf(c.maxPerMethod));
    }

    private TraceConfig read() {
        TraceConfig c = new TraceConfig();
        c.enabled = cbEnabled.isChecked();
        c.traceText = cbText.isChecked();
        c.traceColor = cbColor.isChecked();
        c.classStack = cbClassStack.isChecked();
        c.keywords = etKeywords.getText().toString().trim();
        c.colorFilter = etColorFilter.getText().toString().trim();
        c.classes = etClasses.getText().toString().trim();
        c.stackDepth = TraceConfig.clamp(parse(etDepth, 20), 1, 60);
        c.maxPerMethod = TraceConfig.clamp(parse(etMax, 100), 1, 5000);
        return c;
    }

    private static int parse(EditText e, int d) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Throwable t) {
            return d;
        }
    }

    private void save(final boolean push) {
        final TraceConfig c = read();
        final String cfgText = c.toText();
        final String pkgs = etPkgs.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("cfg", cfgText).putString("pkgs", pkgs).apply();
        if (!push) return;

        final java.util.List<String> list = TraceConfig.split(pkgs);
        if (list.isEmpty()) {
            tvStatus.setText("请先填目标包名");
            return;
        }
        btnPush.setEnabled(false);
        tvStatus.setText("正在推送…（首次会弹出 root 授权，请允许）");
        new Thread(() -> {
            final StringBuilder sb = new StringBuilder();
            for (String pkg : list) {
                if (!RootShell.validPkg(pkg)) {
                    sb.append("✗ ").append(pkg).append("：包名格式不对\n");
                    continue;
                }
                RootShell.Result r = RootShell.pushConfig(pkg, cfgText);
                if (r.out.contains("__OK__")) sb.append("✓ ").append(pkg).append("：已推送\n");
                else if (r.out.contains("__NOPKG__")) sb.append("✗ ").append(pkg).append("：没有安装这个应用\n");
                else sb.append("✗ ").append(pkg).append("：失败(").append(r.code).append(") ").append(r.err.trim()).append('\n');
            }
            sb.append("\n目标 App 回到前台后约 2 秒内生效，不用重启。\n"
                    + "重新推送会清零每个方法的计数。目标 App 清除数据/重装后需要重新推送。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnPush.setEnabled(true);
                tvStatus.setText(sb.toString());
            });
        }).start();
    }

    // ---------- 小工具 ----------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(14);
        return c;
    }

    private EditText edit(boolean multi, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(14);
        e.setSingleLine(!multi);
        if (multi) {
            e.setMinLines(2);
            e.setGravity(android.view.Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        e.setVisibility(View.VISIBLE);
        return e;
    }
}
