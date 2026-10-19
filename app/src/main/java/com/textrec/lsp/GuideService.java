package com.textrec.lsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务浮窗：浮在所有 App 上面，一步一步提示“现在要做什么”，按键把“任务开始 / 结束 / 重做”作为标记经 root 推给目标 App，
 * 进它的时间线。浮窗在 TextRec 自己的进程里，不往目标 App 里画任何东西。
 *
 * 四个键：✕ 关闭　↺ 重做（这一件重来，上一次作废）　◀ 上一件　▶ 下一件。
 * 清单每行一件：「名称|提示|秒数」；名称以 @基线 开头的是自动计时的基线（开始时自动发“基线开始”，倒计时到 0 自动发“基线结束”）。
 */
public class GuideService extends Service {
    static final String PREF_TASKS = "tasks";
    static final String DEFAULT_TASKS =
            "@基线|什么都不要操作，手离开屏幕，让视频正常播放|10\n"
                    + "划到下一个视频|向上划一次，等新视频开始播放||\n"
                    + "暂停|点一下视频暂停，停 3 秒||\n"
                    + "继续播放|再点一下继续播放||\n"
                    + "拖进度条回退|按住进度条往左拖一段再松手||\n"
                    + "进评论再返回|点评论图标，等评论出来，再按返回||\n";

    static final class Task {
        final String name, hint;
        final int secs;
        final boolean baseline;
        /** 名称前写 !：这件事要在目标 App 还没运行时就开始（比如冷启动）；只对清单第一件有意义 */
        final boolean noWait;

        Task(String name, String hint, int secs, boolean baseline, boolean noWait) {
            this.name = name;
            this.hint = hint;
            this.secs = secs;
            this.baseline = baseline;
            this.noWait = noWait;
        }
    }

    static List<Task> parse(String text) {
        ArrayList<Task> out = new ArrayList<>();
        for (String line : text.split("[\\r\\n]+")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            String[] p = t.split("\\|", -1);
            String name = p[0].trim();
            boolean noWait = name.startsWith("!");
            if (noWait) name = name.substring(1).trim();
            boolean base = name.startsWith("@基线");
            if (base) name = "基线（什么都不操作）";
            String hint = p.length > 1 ? p[1].trim() : "";
            int secs = 0;
            if (p.length > 2) {
                try {
                    secs = Integer.parseInt(p[2].trim());
                } catch (Throwable ignored) {
                }
            }
            if (base && secs <= 0) secs = 10;
            out.add(new Task(name, hint, secs, base, noWait));
        }
        return out;
    }

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());
    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private TextView tvStep, tvTitle, tvHint, tvTimer;
    private LinearLayout rowRun, rowReady;
    private boolean ready;
    private boolean checking, failed;
    private TextView tvWarn;
    private LinearLayout rowErr;
    private String lastReason = "";
    private List<Task> tasks = new ArrayList<>();
    private List<String> pkgs = new ArrayList<>();
    private int idx = -1;
    private boolean baseRunning;
    private int remain;
    private boolean finished;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            closeAll(true);
            return START_NOT_STICKY;
        }
        startFg();
        if (root == null) {
            SharedPreferences sp = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
            tasks = parse(sp.getString(PREF_TASKS, DEFAULT_TASKS));
            pkgs = Actions.pkgs(sp);
            if (tasks.isEmpty()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            buildOverlay();
            running = true;
            if (tasks.get(0).noWait) {
                ready = true;
                startRun(); // 第一件要在目标 App 还没运行时就开始（如冷启动），不停在“准备”
            } else {
                showReady();
            }
        }
        return START_NOT_STICKY;
    }

    private void startFg() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("guide", "任务浮窗", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "guide")
                    .setContentTitle("TextRec 任务浮窗运行中")
                    .setContentText("在浮窗上点 ✕ 关闭")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, n);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ 浮窗界面

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView btn(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF3A4A66);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setPadding(dp(4), dp(10), dp(4), dp(10));
        b.setOnClickListener(l);
        return b;
    }

    private void buildOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101820);
        bg.setCornerRadius(dp(14));
        box.setBackground(bg);

        tvStep = new TextView(this);
        tvStep.setTextColor(0xFF9FB4D6);
        tvStep.setTextSize(11);
        tvTitle = new TextView(this);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvHint = new TextView(this);
        tvHint.setTextColor(0xFFD0D8E8);
        tvHint.setTextSize(12.5f);
        tvTimer = new TextView(this);
        tvTimer.setTextColor(0xFFFFC857);
        tvTimer.setTextSize(22);
        tvTimer.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(tvStep);
        box.addView(tvTitle);
        box.addView(tvHint);
        box.addView(tvTimer);
        tvWarn = new TextView(this);
        tvWarn.setTextColor(0xFFFFD84D);
        tvWarn.setTextSize(12.5f);
        tvWarn.setVisibility(View.GONE);
        box.addView(tvWarn);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
        bl.setMargins(dp(3), dp(8), dp(3), 0);
        row.addView(btn("✕ 关闭", v -> closeAll(true)), bl);
        row.addView(btn("↺ 重做", v -> redo()), bl);
        row.addView(btn("◀ 上一件", v -> {
            if (!checking) go(idx - 1, "任务上一件", true);
        }), bl);
        row.addView(btn("▶ 下一件", v -> next()), bl);
        rowRun = row;
        box.addView(row);

        // 准备阶段只有两个键：关闭、开始。要先切到目标 App 的界面再按开始
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(btn("✕ 关闭", v -> closeAll(false)), bl);
        row2.addView(btn("▶ 开始", v -> startRun()), bl);
        rowReady = row2;
        box.addView(row2);

        // 检查没通过：复制原因（发给 Claude）/ 重做 / 忽略继续 / 关闭
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(btn("复制原因", v -> copyReason()), bl);
        row3.addView(btn("↺ 重做", v -> redo()), bl);
        row3.addView(btn("忽略继续", v -> ignoreAndGo()), bl);
        row3.addView(btn("✕ 关闭", v -> closeAll(true)), bl);
        row3.setVisibility(View.GONE);
        rowErr = row3;
        box.addView(row3);

        lp = new WindowManager.LayoutParams(dp(300), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(140);

        // 按住上面的文字区域可以拖动浮窗，免得挡住要操作的地方
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = lp.x;
                        oy = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + Math.round(e.getRawX() - sx);
                        lp.y = oy + Math.round(e.getRawY() - sy);
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
        tvStep.setOnTouchListener(drag);
        tvTitle.setOnTouchListener(drag);
        tvHint.setOnTouchListener(drag);
        root = box;
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            root = null;
            stopSelf();
        }
    }

    // ------------------------------------------------------------ 流程

    /** 目标 App 的名字：优先显示应用名（如“抖音”），查不到就显示包名。 */
    private String targetName() {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            String n = p;
            try {
                n = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p, 0)).toString();
            } catch (Throwable ignored) {
            }
            sb.append(sb.length() > 0 ? "、" : "").append(n);
        }
        return sb.length() == 0 ? "目标 App" : sb.toString();
    }

    private void showReady() {
        ready = true;
        rowRun.setVisibility(View.GONE);
        rowReady.setVisibility(View.VISIBLE);
        tvStep.setText("准备    按住这里可拖动");
        tvTitle.setText("先切到「" + targetName() + "」界面");
        tvHint.setText("让视频已经在播放，再点 ▶ 开始。点开始后第一件事就开始计时 / 记录，所以开始前不要先倒计时。\n共 " + tasks.size() + " 件事。");
        tvTimer.setText("");
    }

    private void startRun() {
        if (!ready) return;
        ready = false;
        rowReady.setVisibility(View.GONE);
        rowRun.setVisibility(View.VISIBLE);
        marker("任务流程开始（共 " + tasks.size() + " 件）");
        go(0, "任务开始", false);
    }

    private void marker(String label) {
        final long seq = SEQ.incrementAndGet();
        final List<String> pk = new ArrayList<>(pkgs);
        sender.execute(() -> {
            for (String p : pk) {
                try {
                    if (RootShell.validPkg(p)) RootShell.pushMarker(p, seq, label);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {
        }
    }

    private void stopTimer() {
        ui.removeCallbacksAndMessages(null);
        if (baseRunning) {
            baseRunning = false;
            marker("基线结束");
        }
    }

    /** 结束当前这件，切到第 to 件（越界 = 全部完成 / 已经是第一件）。 */
    private void go(int to, String startLabel, boolean sendEnd) {
        if (to < 0) {
            buzz();
            return;
        }
        if (sendEnd && idx >= 0 && idx < tasks.size() && !finished) {
            stopTimer();
            marker("任务结束 " + (idx + 1) + " " + tasks.get(idx).name);
        }
        if (to >= tasks.size()) {
            finished = true;
            idx = tasks.size();
            tvStep.setText("全部完成");
            tvTitle.setText("录完了");
            tvHint.setText("回 TextRec 点刷新、导出。点 ✕ 关闭浮窗。");
            tvTimer.setText("");
            marker("任务全部完成");
            buzz();
            return;
        }
        finished = false;
        int from = idx;
        idx = to;
        if (startLabel.equals("任务上一件")) marker("任务上一件 " + (from + 1) + "→" + (to + 1));
        beginTask();
    }

    private void redo() {
        if (checking || finished || idx < 0 || idx >= tasks.size()) return;
        stopTimer();
        marker("任务重做 " + (idx + 1) + " " + tasks.get(idx).name + "（上一次作废）");
        redoFlag = true;
        beginTask();
    }

    private void beginTask() {
        Task t = tasks.get(idx);
        tvStep.setText((idx + 1) + " / " + tasks.size() + "    按住这里可拖动");
        tvTitle.setText(t.name);
        tvHint.setText(t.hint);
        tvTimer.setText("");
        failed = false;
        tvWarn.setVisibility(View.GONE);
        rowErr.setVisibility(View.GONE);
        rowReady.setVisibility(View.GONE);
        rowRun.setVisibility(View.VISIBLE);
        // 重做时已经发过“任务重做”，不再重复发“任务开始”
        if (!redoFlag) marker("任务开始 " + (idx + 1) + " " + t.name);
        redoFlag = false;
        buzz();
        if (t.baseline) {
            marker("基线开始 " + t.secs);
            baseRunning = true;
            remain = t.secs;
            tickDown();
        }
    }

    private boolean redoFlag;

    // ------------------------------------------------------------ 检查这件事有没有记录到

    static final class Check {
        boolean ok;
        String summary = "";
        String full = "";
    }

    private static final java.util.Set<String> NOT_EVENT = new java.util.HashSet<>(java.util.Arrays.asList(
            "TASK", "BASE", "PAGE", "CLASS", "TAG", "MARK"));

    /** 点 ▶ 下一件：先发结束标记，等目标进程把快照写出来，读回来检查这件事期间有没有记录到东西；没有就停在这件事上，用黄色字写原因。 */
    private void next() {
        if (ready || checking || finished || idx < 0 || idx >= tasks.size()) return;
        final int n = idx + 1;
        final Task t = tasks.get(idx);
        stopTimer();
        marker("任务结束 " + n + " " + t.name);
        checking = true;
        tvTimer.setText("");
        tvWarn.setVisibility(View.VISIBLE);
        tvWarn.setText("检查有没有记录到…（约 5 秒，别动）");
        sender.execute(() -> {
            try {
                Thread.sleep(5000); // 目标进程 3 秒落一次盘，再留点余量
            } catch (InterruptedException ignored) {
            }
            final Check c = evaluate(n, t);
            ui.post(() -> afterCheck(c));
        });
    }

    private void afterCheck(Check c) {
        checking = false;
        if (root == null) return;
        if (c.ok) {
            tvWarn.setVisibility(View.GONE);
            go(idx + 1, "任务开始", false);
            return;
        }
        failed = true;
        lastReason = c.full;
        buzz();
        tvWarn.setVisibility(View.VISIBLE);
        tvWarn.setText(c.summary);
        rowRun.setVisibility(View.GONE);
        rowErr.setVisibility(View.VISIBLE);
    }

    private void ignoreAndGo() {
        if (!failed) return;
        marker("任务检查未通过，用户选择忽略继续 " + (idx + 1));
        tvWarn.setVisibility(View.GONE);
        rowErr.setVisibility(View.GONE);
        rowRun.setVisibility(View.VISIBLE);
        go(idx + 1, "任务开始", false);
    }

    private void copyReason() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TextRec 任务检查", lastReason));
            android.widget.Toast.makeText(this, "已复制，发给 Claude", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    /** 读回目标进程的快照，找这件事的开始 / 结束标记，数标记之间有多少个事件。 */
    private Check evaluate(int n, Task t) {
        Check res = new Check();
        StringBuilder d = new StringBuilder();
        d.append("TextRec 任务检查未通过\n任务 ").append(n).append("「").append(t.name).append("」\n");
        boolean anySnap = false, gotStart = false, gotEnd = false;
        int bestEvents = -1;
        String cfgInfo = "", warn = "";
        long bestRecs = -1;
        for (String pkg : pkgs) {
            RootShell.Result r;
            try {
                r = RootShell.pullSnapshots(pkg);
            } catch (Throwable e) {
                d.append(pkg).append("：读快照出错 ").append(e).append('\n');
                continue;
            }
            if (r.out.startsWith("__NOPKG__")) {
                d.append(pkg).append("：找不到这个 App 的数据目录（包名不对 / 没装 / 没 root）\n");
                continue;
            }
            if (r.code != 0 && r.out.trim().isEmpty()) d.append(pkg).append("：读快照失败(").append(r.code).append(") ").append(r.err.trim()).append('\n');
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (!line.startsWith("{")) continue;
                try {
                    JSONObject root = new JSONObject(line);
                    anySnap = true;
                    String proc = root.optString("process", "?");
                    JSONObject st = root.optJSONObject("stats");
                    long recs = st == null ? 0 : st.optLong("records");
                    JSONArray tl = root.optJSONArray("timeline");
                    long startT = Long.MIN_VALUE, endT = Long.MAX_VALUE;
                    boolean s0 = false, e0 = false;
                    if (tl != null) {
                        for (int i = 0; i < tl.length(); i++) {
                            JSONObject e = tl.optJSONObject(i);
                            if (e == null || !"TASK".equals(e.optString("kind"))) continue;
                            String tx = e.optString("text");
                            if (tx.startsWith("任务开始 " + n + " ") || tx.startsWith("任务重做 " + n + " ")) {
                                startT = e.optLong("t");
                                s0 = true;
                                e0 = false;
                                endT = Long.MAX_VALUE;
                            } else if (s0 && tx.startsWith("任务结束 " + n + " ")) {
                                endT = e.optLong("t");
                                e0 = true;
                            }
                        }
                    }
                    java.util.TreeMap<String, Integer> kinds = new java.util.TreeMap<>();
                    int events = 0;
                    if (s0 && tl != null) {
                        for (int i = 0; i < tl.length(); i++) {
                            JSONObject e = tl.optJSONObject(i);
                            if (e == null) continue;
                            long tt = e.optLong("t");
                            String k = e.optString("kind");
                            if (tt < startT || tt > endT || NOT_EVENT.contains(k)) continue;
                            int add = Math.max(1, e.optInt("n"));
                            events += add;
                            Integer c0 = kinds.get(k);
                            kinds.put(k, (c0 == null ? 0 : c0) + add);
                        }
                    }
                    d.append("进程 ").append(proc).append("：记录 ").append(recs)
                            .append("，收到开始标记 ").append(s0 ? "是" : "否").append("，结束标记 ").append(e0 ? "是" : "否");
                    if (s0) d.append("，这件事期间事件 ").append(events).append(kinds.isEmpty() ? "" : " " + kinds);
                    d.append('\n');
                    if (s0) gotStart = true;
                    if (e0) gotEnd = true;
                    if (s0 && events > bestEvents) bestEvents = events;
                    if (recs > bestRecs) {
                        bestRecs = recs;
                        JSONObject cj = root.optJSONObject("config");
                        cfgInfo = cj == null ? "" : cj.toString();
                        warn = root.optString("warn", "").trim();
                        if (st != null) {
                            cfgInfo += "\n统计 " + st.toString();
                        }
                    }
                } catch (Throwable e) {
                    d.append("解析快照出错 ").append(e).append('\n');
                }
            }
        }
        String why = null;
        if (!anySnap) {
            why = "读不到任何快照：目标 App 没运行过 / 模块没在 LSPosed 里勾选它 / 没 root / 数据目录里还没有 textrec 目录";
        } else if (!gotStart) {
            why = "目标进程没收到「任务开始 " + n + "」标记：目标 App 当时没在运行，或进程是在标记发出之后才启动的";
        } else if (!t.baseline && bestEvents == 0) {
            why = "这件事期间目标进程一个事件都没记到：操作没触发已设的钩子 / 采集开关或过滤（页面、线程、触发窗口）挡掉了 / 做的不是这件事";
        }
        if (why == null) {
            res.ok = true;
            return res;
        }
        d.append("原因：").append(why).append('\n');
        if (!gotEnd && gotStart) d.append("（结束标记也没收到，可能快照还没写出来）\n");
        if (!cfgInfo.isEmpty()) d.append("目标进程采集配置与统计：").append(cfgInfo).append('\n');
        if (!warn.isEmpty()) d.append("目标进程警告：").append(warn).append('\n');
        res.ok = false;
        res.full = d.toString();
        res.summary = "任务 " + n + "「" + t.name + "」没记录到\n" + why + "\n点「复制原因」发给 Claude，不用做下一件。";
        return res;
    }


    private void tickDown() {
        if (!baseRunning) return;
        if (remain <= 0) {
            baseRunning = false;
            marker("基线结束");
            tvTimer.setText("基线完成");
            tvHint.setText("点 ▶ 下一件");
            buzz();
            return;
        }
        tvTimer.setText(remain + " 秒");
        remain--;
        ui.postDelayed(() -> tickDown(), 1000);
    }

    private void closeAll(boolean mark) {
        stopTimer();
        if (mark && !ready && !finished && idx >= 0) marker("任务中止（在第 " + (idx + 1) + " 件）");
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
        running = false;
        // 等标记发完再停（发送线程里是 root 调用，几百毫秒）
        sender.execute(() -> ui.post(() -> stopSelf()));
    }

    @Override
    public void onDestroy() {
        running = false;
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
        sender.shutdown();
        super.onDestroy();
    }
}
