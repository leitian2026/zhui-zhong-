package com.tracelog.lsp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class LogActivity extends Activity {
    private static final int REQ_EXPORT = 1001;
    private static final int SHOW_TAIL = 150_000;

    private TextView tvLog, tvInfo;
    private EditText etFilter;
    private ScrollView scroll;
    private volatile String full = "";
    private final AtomicBoolean loading = new AtomicBoolean();
    private String pendingExport;
    private volatile String infoBase = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("日志");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(btn("刷新", v -> refresh()), w1());
        bar.addView(btn("导出", v -> export()), w1());
        bar.addView(btn("复制", v -> copy()), w1());
        bar.addView(btn("清空", v -> confirmClear()), w1());
        root.addView(bar);

        etFilter = new EditText(this);
        etFilter.setHint("过滤：只显示包含此文字的记录");
        etFilter.setSingleLine(true);
        etFilter.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etFilter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            public void onTextChanged(CharSequence s, int a, int c, int d) {}
            public void afterTextChanged(Editable s) { render(); }
        });
        root.addView(etFilter);

        tvInfo = new TextView(this);
        tvInfo.setTextSize(12);
        root.addView(tvInfo);

        scroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(11);
        tvLog.setTextIsSelectable(true);
        scroll.addView(tvLog);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    /** 每次回到本页（比如从目标 App 切回来）自动重新读取一次；没有后台轮询。 */
    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        tvInfo.setText("正在读取日志…（首次会弹出 root 授权）");
        final List<String> pkgs = TraceConfig.split(
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString("pkgs", MainActivity.DEFAULT_PKG));
        new Thread(() -> {
            StringBuilder all = new StringBuilder();
            StringBuilder info = new StringBuilder();
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            for (String pkg : pkgs) {
                if (!RootShell.validPkg(pkg)) {
                    info.append(pkg).append(": 包名格式不对  ");
                    continue;
                }
                RootShell.Result r = RootShell.pullLog(pkg);
                if (r.code != 0 && r.out.isEmpty()) {
                    info.append(pkg).append(": 读取失败(").append(r.code).append(") ").append(r.err.trim()).append("  ");
                    continue;
                }
                if (r.out.startsWith("__NOPKG__")) {
                    info.append(pkg).append(": 未安装  ");
                    continue;
                }
                all.append("=== ").append(pkg).append("  (读取于 ").append(stamp).append(") ===\n");
                if (r.out.isEmpty()) {
                    all.append("(还没有日志：确认已在 LSPosed 启用并强杀目标 App、已推送配置、并触发过目标功能)\n");
                } else {
                    all.append(r.out);
                    if (!r.out.endsWith("\n")) all.append('\n');
                }
                all.append('\n');
            }
            final String text = all.toString();
            final String infoText = info.toString();
            runOnUiThread(() -> {
                loading.set(false);
                if (isFinishing() || isDestroyed()) return;
                full = text;
                infoBase = infoText.isEmpty() ? "" : infoText + "\n";
                render();
            });
        }).start();
    }

    /** 按过滤词，以"记录"为单位过滤（一条记录 = 以 # 开头的一行 + 后面缩进的调用栈）。 */
    private String filtered() {
        String f = etFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        String src = full;
        if (f.isEmpty()) return src;
        StringBuilder out = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            if (line.startsWith("#") || line.startsWith("===")) {
                flush(cur, f, out);
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        flush(cur, f, out);
        return out.toString();
    }

    private static void flush(StringBuilder cur, String f, StringBuilder out) {
        if (cur.length() == 0) return;
        String s = cur.toString();
        if (s.startsWith("===") || s.toLowerCase(Locale.ROOT).contains(f)) out.append(s);
    }

    private void render() {
        String s = filtered();
        int entries = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '#' && (i == 0 || s.charAt(i - 1) == '\n')) entries++;
        }
        boolean cut = s.length() > SHOW_TAIL;
        if (cut) s = "…（仅显示最后 " + SHOW_TAIL + " 个字符，导出可得完整内容）\n" + s.substring(s.length() - SHOW_TAIL);
        tvLog.setText(s.isEmpty() ? "(空)" : s);
        tvInfo.setText(infoBase + "共 " + entries + " 条记录" + (cut ? "（已截取显示）" : ""));
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ---------- 导出 / 复制 / 清空 ----------
    private void export() {
        String s = filtered();
        if (s.trim().isEmpty()) {
            Toast.makeText(this, "没有可导出的内容", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExport = s;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "tracelog-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
        startActivityForResult(i, REQ_EXPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
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

    private void copy() {
        String s = filtered();
        if (s.length() > 90_000) s = s.substring(s.length() - 90_000); // 剪贴板太大会失败，复制最后一段
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("tracelog", s));
        Toast.makeText(this, "已复制（超长时只复制最后一段，完整内容请用导出）", Toast.LENGTH_SHORT).show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setMessage("清空所有目标 App 里的日志文件？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> new Thread(() -> {
                    for (String pkg : TraceConfig.split(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                            .getString("pkgs", MainActivity.DEFAULT_PKG))) {
                        if (RootShell.validPkg(pkg)) RootShell.clearLog(pkg);
                    }
                    runOnUiThread(this::refresh);
                }).start())
                .show();
    }

    // ---------- 小工具 ----------
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
