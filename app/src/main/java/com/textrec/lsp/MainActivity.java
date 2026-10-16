package com.textrec.lsp;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private EditText etPkgs, etKeywords, etDepth, etPerKey, etSample, etPerSec, etMax, etTrace;
    private CheckBox cbEnabled, cbKeywordMode, cbRegex, cbSet, cbDraw, cbColor, cbIcons, cbAuto;
    private Button btnPush, btnReset;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("TextRec 文字位置记录");

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);

        root.addView(text("只读记录屏幕上每段文字的“设置位置”和“绘制位置”，不修改目标 App 的任何内容。\n\n"
                + "用法：\n1. 在 LSPosed 启用本模块，作用域勾选目标 App\n"
                + "2. 在下面设置并「保存并推送配置」（需要 root）\n"
                + "3. 点「清空记录并重新开始」，然后强杀目标 App 再冷启动——启动时所有文字的设置和绘制都会被记下\n"
                + "4. 回到本 App 点「查看记录」，搜索文字或类名，进详情看设置栈/绘制栈和可 hook 的方法签名", 13));

        root.addView(label("目标包名（多个用逗号分隔）"));
        etPkgs = edit(false, Actions.DEFAULT_PKG);
        root.addView(etPkgs);

        cbEnabled = check("启用记录");
        root.addView(cbEnabled);
        cbSet = check("记录“设置位置”（setText）");
        root.addView(cbSet);
        cbDraw = check("记录“绘制位置”（drawText）");
        root.addView(cbDraw);
        cbColor = check("记录文字颜色（画笔颜色 + TextView 的 setTextColor 设置位置）");
        root.addView(cbColor);
        cbIcons = check("记录图标/按钮图片（ImageView 的 setImage*、setContentDescription）");
        root.addView(cbIcons);
        cbAuto = check("自动观察 Compose 文字绘制参数（颜色、描边/填充，如弹幕）");
        root.addView(cbAuto);

        root.addView(label("记录范围"));
        cbKeywordMode = check("只记录命中关键字的文字（默认关闭 = 全量记录屏幕上所有文字）");
        root.addView(cbKeywordMode);
        etKeywords = edit(true, "普通模式：逗号或换行分隔；正则模式：每行一个正则，例如 \\d\\d:\\d\\d");
        root.addView(etKeywords);
        cbRegex = check("关键字按正则表达式处理");
        root.addView(cbRegex);

        root.addView(label("采集参数"));
        root.addView(text("调用栈保存帧数（1~60，默认 30）", 12));
        etDepth = number();
        root.addView(etDepth);
        root.addView(text("同一位置前几次抓栈（默认 3）", 12));
        etPerKey = number();
        root.addView(etPerKey);
        root.addView(text("之后每隔多少次再抽样抓一次（默认 200）", 12));
        etSample = number();
        root.addView(etSample);
        root.addView(text("全局每秒最多抓栈次数（默认 1000；冷启动文字很多，太小会丢文字）", 12));
        etPerSec = number();
        root.addView(etPerSec);
        root.addView(text("最多保存多少条记录（默认 2000）", 12));
        etMax = number();
        root.addView(etMax);

        root.addView(label("方法追踪（可选，只读）"));
        root.addView(text("每行一个 类#方法，例如 androidx.compose.ui.text.MultiParagraph#paint-LG529CI；"
                + "记录每次调用的参数、返回值和调用栈（谁调用了它）。类没加载时进入相关页面后会自动重试；"
                + "构造方法写 类#<init>；以 // 开头的行是注释。注意别追踪每秒调用上千次的方法。", 12));
        etTrace = edit(true, "例如：\nandroidx.compose.ui.text.MultiParagraph#paint-LG529CI");
        root.addView(etTrace);

        btnPush = new Button(this);
        btnPush.setText("保存并推送配置");
        btnPush.setOnClickListener(v -> save(true, false));
        root.addView(btnPush);

        btnReset = new Button(this);
        btnReset.setText("清空记录并重新开始");
        btnReset.setOnClickListener(v -> save(true, true));
        root.addView(btnReset);

        Button btnView = new Button(this);
        btnView.setText("查看记录");
        btnView.setOnClickListener(v -> {
            save(false, false);
            startActivity(new Intent(this, RecordsActivity.class));
        });
        root.addView(btnView);

        tvStatus = text("", 13);
        root.addView(tvStatus);

        load();
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
        cbEnabled.setChecked(c.enabled);
        cbSet.setChecked(c.traceSet);
        cbDraw.setChecked(c.traceDraw);
        cbColor.setChecked(c.traceColor);
        cbIcons.setChecked(c.traceIcons);
        cbAuto.setChecked(c.autoParams);
        etTrace.setText(c.traceMethods);
        cbKeywordMode.setChecked(c.keywordMode);
        etKeywords.setText(c.keywords);
        cbRegex.setChecked(c.regex);
        etDepth.setText(String.valueOf(c.stackDepth));
        etPerKey.setText(String.valueOf(c.perKeyStacks));
        etSample.setText(String.valueOf(c.sampleEvery));
        etPerSec.setText(String.valueOf(c.stacksPerSec));
        etMax.setText(String.valueOf(c.maxRecords));
    }

    private RecConfig read(long epoch) {
        RecConfig c = new RecConfig();
        c.enabled = cbEnabled.isChecked();
        c.traceSet = cbSet.isChecked();
        c.traceDraw = cbDraw.isChecked();
        c.traceColor = cbColor.isChecked();
        c.traceIcons = cbIcons.isChecked();
        c.autoParams = cbAuto.isChecked();
        c.traceMethods = etTrace.getText().toString();
        c.keywordMode = cbKeywordMode.isChecked();
        c.keywords = etKeywords.getText().toString();
        c.regex = cbRegex.isChecked();
        c.stackDepth = RecConfig.clamp(parse(etDepth, 30), 1, 60);
        c.perKeyStacks = RecConfig.clamp(parse(etPerKey, 3), 1, 50);
        c.sampleEvery = RecConfig.clamp(parse(etSample, 200), 10, 100000);
        c.stacksPerSec = RecConfig.clamp(parse(etPerSec, 1000), 1, 5000);
        c.maxRecords = RecConfig.clamp(parse(etMax, 2000), 10, 20000);
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

    private void save(final boolean push, final boolean reset) {
        SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        long epoch = reset ? System.currentTimeMillis() : Actions.loadCfg(sp).epoch;
        final RecConfig c = read(epoch);

        if (c.keywordMode && c.regex) {
            for (String k : RecConfig.split(c.keywords, true)) {
                try {
                    Pattern.compile(k);
                } catch (Throwable t) {
                    tvStatus.setText("正则无效，未保存：" + k);
                    return;
                }
            }
        }
        final String pkgs = etPkgs.getText().toString().trim();
        sp.edit().putString("cfg", c.toText()).putString("pkgs", pkgs).apply();
        if (!push) return;

        final List<String> list = RecConfig.splitLines(pkgs);
        if (list.isEmpty()) {
            tvStatus.setText("请先填目标包名");
            return;
        }
        btnPush.setEnabled(false);
        btnReset.setEnabled(false);
        tvStatus.setText("正在推送…（首次会弹出 root 授权，请允许）");
        new Thread(() -> {
            final String msg = Actions.push(list, c, reset)
                    + "\n目标 App 里约 2 秒内生效，不用重启；要记录冷启动阶段的文字，请先推送，再强杀目标 App 后重新启动。"
                    + (c.keywordMode ? "" : "\n当前是全量记录，会拖慢目标 App，只在调试时开。");
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                btnPush.setEnabled(true);
                btnReset.setEnabled(true);
                tvStatus.setText(msg);
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
        t.setPadding(0, dp(4), 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private CheckBox check(String s) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextSize(14);
        return c;
    }

    private EditText number() {
        EditText e = new EditText(this);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
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
