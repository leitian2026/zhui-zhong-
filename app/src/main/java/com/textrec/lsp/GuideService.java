package com.textrec.lsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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

        Task(String name, String hint, int secs, boolean baseline) {
            this.name = name;
            this.hint = hint;
            this.secs = secs;
            this.baseline = baseline;
        }
    }

    static List<Task> parse(String text) {
        ArrayList<Task> out = new ArrayList<>();
        for (String line : text.split("[\\r\\n]+")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            String[] p = t.split("\\|", -1);
            String name = p[0].trim();
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
            out.add(new Task(name, hint, secs, base));
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
            go(0, "任务开始");
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

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
        bl.setMargins(dp(3), dp(8), dp(3), 0);
        row.addView(btn("✕ 关闭", v -> closeAll(true)), bl);
        row.addView(btn("↺ 重做", v -> redo()), bl);
        row.addView(btn("◀ 上一件", v -> go(idx - 1, "任务上一件")), bl);
        row.addView(btn("▶ 下一件", v -> go(idx + 1, "任务开始")), bl);
        box.addView(row);

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
    private void go(int to, String startLabel) {
        if (to < 0) {
            buzz();
            return;
        }
        if (idx >= 0 && idx < tasks.size() && !finished) {
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
        if (finished || idx < 0 || idx >= tasks.size()) return;
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
        if (mark && !finished && idx >= 0) marker("任务中止（在第 " + (idx + 1) + " 件）");
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
