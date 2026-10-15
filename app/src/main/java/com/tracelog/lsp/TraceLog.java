package com.tracelog.lsp;

import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XposedBridge;

/**
 * 在目标 App 进程里写日志：目标 App 自己的数据目录 files/tracelog/trace.log（自己的目录，一定有写权限）。
 * 模块界面再通过 root 读出来。写入放在单独的后台线程，不阻塞目标 App。
 */
final class TraceLog {
    private static final long MAX_BYTES = 4L << 20;
    private static volatile File dir;
    private static volatile File file;
    private static volatile String proc = "?";
    private static volatile boolean warned;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final ThreadPoolExecutor EX = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(3000),
            r -> {
                Thread t = new Thread(r, "tracelog-writer");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private TraceLog() {}

    static void init(String dataDir, String processName) {
        File d = new File(dataDir, "files/tracelog");
        d.mkdirs();
        dir = d;
        file = new File(d, "trace.log");
        proc = processName == null ? "?" : processName;
    }

    static File configFile() {
        return new File(dir, "config.properties");
    }

    static void async(Runnable r) {
        EX.execute(r);
    }

    static void log(final String tag, final String msg, final String stack) {
        final long ts = System.currentTimeMillis();
        final int pid = Process.myPid();
        final String th = Thread.currentThread().getName();
        EX.execute(() -> write(ts, pid, th, tag, msg, stack));
    }

    private static void write(long ts, int pid, String th, String tag, String msg, String stack) {
        try {
            File f = file;
            if (f.length() > MAX_BYTES) {
                File old = new File(f.getPath() + ".1");
                old.delete();
                f.renameTo(old);
            }
            StringBuilder sb = new StringBuilder(256);
            sb.append('#').append(FMT.format(new Date(ts))).append(" [").append(proc)
                    .append(" p").append(pid).append('/').append(Util.clean(th, 24)).append("] ")
                    .append(tag).append(' ').append(msg).append('\n');
            if (stack != null) sb.append(stack);
            // 每条日志一次 append 写入，多进程同时写也不会交错
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                XposedBridge.log("[TraceLog] write failed: " + t);
            }
        }
    }
}
