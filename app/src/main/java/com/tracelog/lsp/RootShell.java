package com.tracelog.lsp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 通过 su 执行脚本（脚本从 stdin 喂进去）。仅在用户点按钮时调用，不做任何后台轮询。 */
final class RootShell {
    private RootShell() {}

    static final class Result {
        int code = -1;
        String out = "";
        String err = "";
    }

    static boolean validPkg(String p) {
        return p != null && p.matches("[A-Za-z0-9_.]+");
    }

    static Result run(String script, long timeoutSec) {
        Result r = new Result();
        try {
            Process p = new ProcessBuilder("su").start();
            final ByteArrayOutputStream so = new ByteArrayOutputStream();
            final ByteArrayOutputStream se = new ByteArrayOutputStream();
            Thread t1 = pump(p.getInputStream(), so);
            Thread t2 = pump(p.getErrorStream(), se);
            OutputStream os = p.getOutputStream();
            os.write((script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            if (p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                r.code = p.exitValue();
            } else {
                p.destroy();
                r.code = -2;
                r.err = "超时（没有授予 root？）";
            }
            t1.join(3000);
            t2.join(3000);
            r.out = new String(so.toByteArray(), StandardCharsets.UTF_8);
            if (r.err.isEmpty()) r.err = new String(se.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            r.code = -1;
            r.err = "无法执行 su：" + t.getMessage();
        }
        return r;
    }

    private static Thread pump(final InputStream in, final ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 把配置写进目标 App 自己的数据目录，并把属主/SELinux 标签改成该 App 的，这样目标进程才读得到。 */
    static Result pushConfig(String pkg, String configText) {
        String d = "/data/data/" + pkg;
        String script =
                "if [ ! -d " + d + " ]; then echo __NOPKG__; exit 0; fi\n"
                + "U=$(stat -c %u " + d + ")\n"
                + "F=" + d + "/files\n"
                + "T=$F/tracelog\n"
                + "if [ ! -d $F ]; then mkdir -p $F; chown $U:$U $F; chmod 771 $F; restorecon $F 2>/dev/null; fi\n"
                + "mkdir -p $T\n"
                + "cat > $T/config.properties <<'TRACELOG_EOF'\n"
                + configText + "\n"
                + "TRACELOG_EOF\n"
                + "chown -R $U:$U $T\n"
                + "chmod 700 $T\n"
                + "chmod 600 $T/config.properties\n"
                + "restorecon -R $T 2>/dev/null\n"
                + "echo __OK__\n";
        return run(script, 30);
    }

    static Result pullLog(String pkg) {
        String d = "/data/data/" + pkg;
        String script =
                "if [ ! -d " + d + " ]; then echo __NOPKG__; exit 0; fi\n"
                + "T=" + d + "/files/tracelog\n"
                + "cat $T/trace.log.1 2>/dev/null\n"
                + "cat $T/trace.log 2>/dev/null\n"
                + "true\n";
        return run(script, 40);
    }

    static Result clearLog(String pkg) {
        String t = "/data/data/" + pkg + "/files/tracelog";
        return run("rm -f " + t + "/trace.log " + t + "/trace.log.1\necho __OK__\n", 20);
    }
}
