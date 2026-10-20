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
                    + "进评论再返回|点评论图标，等评论出来，再按返回||\n"
                    + "弹幕密集 40 秒|进弹幕密集的直播间 / 视频，别操作，让弹幕自己滚 40 秒|40\n";

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

    /**
     * 打开任务浮窗（首页顶部和记录页菜单共用）：先要“显示在其他应用上层”权限，没有就跳到系统设置。
     * 要用最新的任务清单：浮窗启动时从 SharedPreferences 的 PREF_TASKS 读。
     */
    static void open(android.app.Activity a) {
        if (!android.provider.Settings.canDrawOverlays(a)) {
            android.widget.Toast.makeText(a, "先给 TextRec 开「显示在其他应用上层」，开完回来再点一次", android.widget.Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + a.getPackageName())));
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1); // 不给也能用，只是看不到“运行中”通知
        }
        if (isRunning()) {
            android.widget.Toast.makeText(a, "浮窗已经开着了（要用新清单，先在浮窗上点 ✕ 再重新打开）", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        a.startForegroundService(new Intent(a, GuideService.class));
        android.widget.Toast.makeText(a, "浮窗已打开：切到目标 App，按浮窗上的提示做", android.widget.Toast.LENGTH_SHORT).show();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private TextView tvStep, tvTitle, tvHint, tvTimer;
    /** 「正在做」面板（绿色调）：标签 + 名称 + 提示 + 计时；「下一件」面板（灰蓝调）：让用户提前为下一件做准备 */
    private LinearLayout curPanel, nextPanel;
    private TextView tvCurLabel, tvNextLabel, tvNextTitle, tvNextHint;
    private LinearLayout rowRun, rowReady, rowDone, rowPaused;
    private TextView btnPause, btnNext;
    private volatile int token;
    private boolean paused;
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
        // 正在做的这件：绿色底 + 绿色标签；下一件：灰蓝底 + 蓝色标签，一眼分得清
        curPanel = new LinearLayout(this);
        curPanel.setOrientation(LinearLayout.VERTICAL);
        curPanel.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvCurLabel = new TextView(this);
        tvCurLabel.setTextSize(11);
        tvCurLabel.setTypeface(Typeface.DEFAULT_BOLD);
        curPanel.addView(tvCurLabel);
        curPanel.addView(tvTitle);
        curPanel.addView(tvHint);
        curPanel.addView(tvTimer);
        box.addView(curPanel);
        nextPanel = new LinearLayout(this);
        nextPanel.setOrientation(LinearLayout.VERTICAL);
        nextPanel.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable nbg = new GradientDrawable();
        nbg.setColor(0x33FFFFFF);
        nbg.setCornerRadius(dp(8));
        nextPanel.setBackground(nbg);
        tvNextLabel = new TextView(this);
        tvNextLabel.setTextSize(11);
        tvNextLabel.setTypeface(Typeface.DEFAULT_BOLD);
        tvNextLabel.setTextColor(0xFF8FB8FF);
        tvNextTitle = new TextView(this);
        tvNextTitle.setTextColor(0xFFB8C6E0);
        tvNextTitle.setTextSize(14);
        tvNextTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvNextHint = new TextView(this);
        tvNextHint.setTextColor(0xFF8D9BB5);
        tvNextHint.setTextSize(11.5f);
        nextPanel.addView(tvNextLabel);
        nextPanel.addView(tvNextTitle);
        nextPanel.addView(tvNextHint);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.setMargins(0, dp(6), 0, 0);
        box.addView(nextPanel, nlp);
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
        row.addView(btn("▶ 提前结束", v -> next()), bl);
        rowRun = row;
        box.addView(row);

        // 准备阶段只有两个键：关闭、开始。要先切到目标 App 的界面再按开始
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(btn("✕ 关闭", v -> closeAll(false)), bl);
        row2.addView(btn("▶ 开始", v -> startRun()), bl);
        rowReady = row2;
        box.addView(row2);

        // 任务自动结束且记录到了：重做 / 暂停记录 / 下一件
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.addView(btn("✕ 关闭", v -> closeAll(true)), bl);
        row4.addView(btn("↺ 重做", v -> redo()), bl);
        btnPause = btn("⏸ 暂停记录", v -> pauseRec());
        row4.addView(btnPause, bl);
        btnNext = btn("▶ 下一件", v -> nextTask());
        row4.addView(btnNext, bl);
        row4.setVisibility(View.GONE);
        rowDone = row4;
        box.addView(row4);

        // 暂停记录中：目标 App 什么都不记，翻到相关界面再点开始记录
        LinearLayout row5 = new LinearLayout(this);
        row5.setOrientation(LinearLayout.HORIZONTAL);
        row5.addView(btn("✕ 关闭", v -> closeAll(true)), bl);
        row5.addView(btn("▶ 开始记录", v -> resumeRec()), bl);
        row5.setVisibility(View.GONE);
        rowPaused = row5;
        box.addView(row5);

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

    /**
     * 当前面板换颜色 + 标签：绿 = 正在做，黄 = 暂停 / 准备，亮绿 = 已完成。
     * 【给其他 AI】面板颜色只在这里改，调用处只传标签和颜色。
     */
    private void setCur(String label, int labelColor, int tint) {
        tvCurLabel.setText(label);
        tvCurLabel.setTextColor(labelColor);
        GradientDrawable g = new GradientDrawable();
        g.setColor(tint);
        g.setCornerRadius(dp(8));
        curPanel.setBackground(g);
    }

    /** 「下一件」面板：after = 当前下标，显示 after+1 那件；传 -1 表示显示第一件；没有下一件就写“没有了”；hide 时整块隐藏。 */
    private void showNext(int after, boolean hide) {
        if (hide) {
            nextPanel.setVisibility(View.GONE);
            return;
        }
        nextPanel.setVisibility(View.VISIBLE);
        int n = after + 1;
        if (n >= 0 && n < tasks.size()) {
            Task t = tasks.get(n);
            tvNextLabel.setText(after < 0 ? "第一件（先看一眼）" : "下一件 " + (n + 1) + " / " + tasks.size() + "（提前准备）");
            tvNextTitle.setText(t.name);
            tvNextHint.setText(t.hint);
            tvNextHint.setVisibility(t.hint.isEmpty() ? View.GONE : View.VISIBLE);
        } else {
            tvNextLabel.setText("下一件");
            tvNextTitle.setText("没有了，这是最后一件");
            tvNextHint.setVisibility(View.GONE);
        }
    }

    private void showReady() {
        ready = true;
        setCur("准备", 0xFFFFC857, 0x33FFC857);
        showNext(-1, false);
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
        token++;
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
            setCur("全部完成 ✓", 0xFF7EE787, 0x337EE787);
            showNext(0, true);
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
        setCur("● 正在做", 0xFF4ADE80, 0x332E9E5A);
        showNext(idx, false);
        tvStep.setText((idx + 1) + " / " + tasks.size() + "    按住这里可拖动");
        tvTitle.setText(t.name);
        tvHint.setText(t.hint);
        tvTimer.setText("");
        failed = false;
        tvWarn.setVisibility(View.GONE);
        rowErr.setVisibility(View.GONE);
        rowReady.setVisibility(View.GONE);
        rowDone.setVisibility(View.GONE);
        rowPaused.setVisibility(View.GONE);
        rowRun.setVisibility(View.VISIBLE);
        tvTimer.setTextColor(0xFFFFC857);
        // 重做时已经发过“任务重做”，不再重复发“任务开始”
        if (!redoFlag) marker("任务开始 " + (idx + 1) + " " + t.name);
        redoFlag = false;
        buzz();
        if (t.baseline) {
            marker("基线开始 " + t.secs);
            baseRunning = true;
            remain = t.secs;
            tickDown();
        } else {
            runTick0 = System.currentTimeMillis();
            runTick();
        }
        startWatch();
    }

    private long runTick0;

    private void runTick() {
        if (idx < 0 || idx >= tasks.size() || tasks.get(idx).baseline || finished || checking || failed) return;
        tvTimer.setText("记录中 " + ((System.currentTimeMillis() - runTick0) / 1000) + " 秒");
        ui.postDelayed(this::runTick, 1000);
    }

    /** 每 3.5 秒读一次目标进程快照：这件事记到东西了并且安静下来 / 到点了，就自动结束；一直没记到就自动写出原因。 */
    private void startWatch() {
        final int tk = ++token;
        final int n = idx + 1;
        final Task t = tasks.get(idx);
        final long t0 = System.currentTimeMillis();
        final long limitNo = t.secs > 0 ? (t.secs + 10) * 1000L : 25000L;
        Thread th = new Thread(() -> {
            int lastEntries = -1, quiet = 0;
            while (true) {
                try {
                    Thread.sleep(3500);
                } catch (InterruptedException e) {
                    return;
                }
                if (tk != token) return;
                if (t.baseline && baseRunning) continue;
                final long el = System.currentTimeMillis() - t0;
                final Check c = evaluate(n, t);
                if (tk != token) return;
                boolean done = false;
                if (c.ok) {
                    if (t.baseline) done = true;
                    else if (t.secs > 0) done = el >= t.secs * 1000L;
                    else {
                        if (c.entries == lastEntries) quiet++;
                        else quiet = 0;
                        lastEntries = c.entries;
                        done = (quiet >= 2 && el >= 6000) || el >= 90000;
                    }
                } else if (el >= limitNo) {
                    done = true;
                }
                if (done) {
                    ui.post(() -> autoEnd(tk, n, t, c));
                    return;
                }
            }
        });
        th.setDaemon(true);
        th.start();
    }

    private void autoEnd(int tk, int n, Task t, Check c) {
        if (tk != token || root == null || finished || idx != n - 1 || checking) return;
        stopTimer();
        marker("任务结束 " + n + " " + t.name + "（自动）");
        afterCheck(c);
    }

    private void showDone(String info) {
        failed = false;
        tvWarn.setVisibility(View.GONE);
        rowRun.setVisibility(View.GONE);
        rowErr.setVisibility(View.GONE);
        rowPaused.setVisibility(View.GONE);
        rowDone.setVisibility(View.VISIBLE);
        boolean last = idx >= tasks.size() - 1;
        setCur("已完成 ✓", 0xFF7EE787, 0x337EE787);
        showNext(idx, false);
        btnPause.setVisibility(last ? View.GONE : View.VISIBLE);
        btnNext.setText(last ? "▶ 完成" : "▶ 下一件");
        tvStep.setText((idx + 1) + " / " + tasks.size() + "    按住这里可拖动");
        tvTimer.setTextColor(0xFF7EE787);
        tvTimer.setText("已记录完成 ✓");
        tvHint.setText(info + (last ? "" : "\n下一件要翻好几个界面？点「暂停记录」，到了再点「开始记录」；不用翻就直接点「下一件」。"));
        buzz();
    }

    private void nextTask() {
        if (finished || idx < 0) return;
        go(idx + 1, "任务开始", false);
    }

    private void pauseRec() {
        if (finished || idx < 0 || idx >= tasks.size() - 1) return;
        paused = true;
        marker("记录暂停（第 " + (idx + 1) + " 件之后，等翻到下一件相关界面）");
        rowDone.setVisibility(View.GONE);
        rowPaused.setVisibility(View.VISIBLE);
        Task nx = tasks.get(idx + 1);
        setCur("⏸ 暂停中，下一件准备", 0xFFFFC857, 0x33FFC857);
        showNext(idx, true); // 暂停时下一件已经显示在上面的大面板里
        tvStep.setText("已暂停记录    按住这里可拖动");
        tvTimer.setTextColor(0xFFFFC857);
        tvTimer.setText("暂停中，不记录");
        tvTitle.setText("下一件：" + nx.name);
        tvHint.setText(nx.hint + "\n翻到相关界面后点「开始记录」，这时才开始记。");
    }

    private void resumeRec() {
        if (!paused) return;
        paused = false;
        marker("记录继续");
        go(idx + 1, "任务开始", false);
    }

    private boolean redoFlag;

    // ------------------------------------------------------------ 检查这件事有没有记录到

    static final class Check {
        boolean ok;
        String summary = "";
        String full = "";
        int entries;
        String okText = "";
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
            showDone(c.okText);
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
        showDone("（这件没记到，已忽略）");
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
        int bestEvents = -1, bestEntries = 0;
        String bestKinds = "";
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
                    int events = 0, entries = 0;
                    if (s0 && tl != null) {
                        for (int i = 0; i < tl.length(); i++) {
                            JSONObject e = tl.optJSONObject(i);
                            if (e == null) continue;
                            long tt = e.optLong("t");
                            String k = e.optString("kind");
                            if (tt < startT || tt > endT) continue;
                            if (!"TASK".equals(k) && !"BASE".equals(k) && !"MARK".equals(k) && !"CLASS".equals(k)) entries++;
                            if (NOT_EVENT.contains(k)) continue;
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
                    if (s0 && entries > bestEntries) bestEntries = entries;
                    if (s0 && events >= bestEvents) bestKinds = kinds.toString();
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
            res.entries = bestEntries;
            res.okText = t.baseline ? "基线已记录" : "这件事期间记到 " + bestEvents + " 个事件 " + bestKinds;
            return res;
        }
        d.append("原因：").append(why).append('\n');
        if (!gotEnd && gotStart) d.append("（结束标记也没收到，可能快照还没写出来）\n");
        if (!cfgInfo.isEmpty()) d.append("目标进程采集配置与统计：").append(cfgInfo).append('\n');
        if (!warn.isEmpty()) d.append("目标进程警告：").append(warn).append('\n');
        res.ok = false;
        res.entries = bestEntries;
        res.full = d.toString();
        res.summary = "任务 " + n + "「" + t.name + "」没记录到\n" + why + "\n点「复制原因」发给 Claude，不用做下一件。";
        return res;
    }


    private void tickDown() {
        if (!baseRunning) return;
        if (remain <= 0) {
            baseRunning = false;
            marker("基线结束");
            tvTimer.setText("基线完成，检查中…");
            buzz();
            return;
        }
        tvTimer.setText(remain + " 秒");
        remain--;
        ui.postDelayed(() -> tickDown(), 1000);
    }

    private void closeAll(boolean mark) {
        stopTimer();
        boolean wasPaused = paused;
        paused = false;
        if (mark && !ready && !finished && idx >= 0) marker("任务中止（在第 " + (idx + 1) + " 件）");
        // 流程开始过（不管有没有暂停）都发一条，目标进程靠它退出「任务流程」状态，之后照常全记
        if (wasPaused || !ready && idx >= 0) marker("记录继续（浮窗关闭）");
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
