package com.textrec.lsp;

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

    /** 依次尝试的 su 启动方式：普通 su 看到的可能是本 App 自己的挂载空间（看不到别的应用的数据目录），
     *  后面几种会进入全局挂载空间。成功过的那种会被记住，下次直接用。 */
    private static final String[][] ALTS = {
            {"su"},
            {"su", "--mount-master"},
            {"su", "-mm"},
            {"su", "-c", "nsenter -t 1 -m -- sh"}
    };
    private static volatile String[] best = ALTS[0];

    /** pm 能查到包，但当前 su 看不到数据目录 = 挂载空间问题。 */
    private static boolean needsNs(Result r) {
        return r.out.contains("__NOPKG__") && r.out.contains("package:");
    }

    static Result run(String script, long timeoutSec) {
        Result r = exec(best, script, timeoutSec);
        if (!needsNs(r)) return r;
        for (String[] c : ALTS) {
            if (c == best) continue;
            Result r2 = exec(c, script, timeoutSec);
            if (r2.code == 0 && !needsNs(r2)) {
                best = c;
                return r2;
            }
        }
        return r;
    }

    private static Result exec(String[] cmd, String script, long timeoutSec) {
        Result r = new Result();
        try {
            Process p = new ProcessBuilder(cmd).start();
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

    /** 找出该包在所有用户空间下的数据目录（主空间 / 应用分身 / 多用户都能覆盖），结果放进 $DIRS。 */
    private static String findDirs(String pkg) {
        return "DIRS=\"\"\n"
                + "for d in /data/user/*/" + pkg + "; do [ -d \"$d\" ] && DIRS=\"$DIRS $d\"; done\n"
                + "if [ -z \"$DIRS\" ] && [ -d /data/data/" + pkg + " ]; then DIRS=/data/data/" + pkg + "; fi\n"
                + "if [ -z \"$DIRS\" ]; then echo __NOPKG__; pm path " + pkg + " 2>&1 | head -1; exit 0; fi\n";
    }

    /** 根据 __NOPKG__ 后面 pm path 的输出，给出准确的失败原因。 */
    static String nopkgMsg(String out) {
        if (out.contains("package:")) return "已安装，但 root 看不到它的数据目录（已自动尝试全局挂载空间仍失败）。"
                + "请在 Magisk/KernelSU/APatch 的 root 设置里，把本 App 的「挂载命名空间」改成全局(Global)后重试";
        return "系统里没有这个包名，请核对包名（抖音可能是别的版本，比如极速版 com.ss.android.ugc.aweme.lite）";
    }

    /** 把配置写进目标 App 自己的数据目录，并把属主/SELinux 标签改成该 App 的，这样目标进程才读得到。 */
    static Result pushConfig(String pkg, String configText) {
        String script = findDirs(pkg)
                + "for D in $DIRS; do\n"
                + "U=$(stat -c %u $D)\n"
                + "F=$D/files\n"
                + "T=$F/textrec\n"
                + "if [ ! -d $F ]; then mkdir -p $F; chown $U:$U $F; chmod 771 $F; restorecon $F 2>/dev/null; fi\n"
                + "mkdir -p $T\n"
                + "cat > $T/config.properties <<'TEXTREC_EOF'\n"
                + configText + "\n"
                + "TEXTREC_EOF\n"
                + "chown -R $U:$U $T\n"
                + "chmod 700 $T\n"
                + "chmod 600 $T/config.properties\n"
                + "restorecon -R $T 2>/dev/null\n"
                + "done\n"
                + "echo __OK__\n";
        return run(script, 30);
    }

    /** 读取所有进程的快照（每个进程一个 snapshot-<进程名>.json，JSON 是单行）。每个文件前有一行 "__FILE__ 路径"。 */
    static Result pullSnapshots(String pkg) {
        String script = findDirs(pkg)
                + "for D in $DIRS; do\n"
                + "for f in $D/files/textrec/snapshot-*.json; do\n"
                + "[ -f \"$f\" ] || continue\n"
                + "echo \"__FILE__ $f\"\n"
                + "cat \"$f\"\n"
                + "echo\n"
                + "done\n"
                + "done\n"
                + "true\n";
        return run(script, 60);
    }

    /** 给目标进程发一条标记（时间线 / 基线用）：往 markers.log 追加一行「序号|发送时刻|内容」；目标进程读走新增的行。 */
    static Result pushMarker(String pkg, long seq, String label) {
        StringBuilder lb = new StringBuilder();
        for (String line : label.split("[\\r\\n]+")) if (!line.trim().equals("TEXTREC_EOF")) lb.append(line).append(' ');
        String script = findDirs(pkg)
                + "for D in $DIRS; do\n"
                + "T=$D/files/textrec\n"
                + "[ -d $T ] || continue\n"
                + "U=$(stat -c %u $D)\n"
                + "cat >> $T/markers.log <<'TEXTREC_EOF'\n"
                + seq + "|" + System.currentTimeMillis() + "|" + lb.toString().trim().replace("|", "/") + "\n"
                + "TEXTREC_EOF\n"
                + "chown $U:$U $T/markers.log\n"
                + "chmod 600 $T/markers.log\n"
                + "restorecon $T/markers.log 2>/dev/null\n"
                + "done\n"
                + "echo __OK__\n";
        return run(script, 20);
    }

    static Result clearSnapshots(String pkg) {
        String script = findDirs(pkg)
                + "for D in $DIRS; do rm -f $D/files/textrec/snapshot-*.json $D/files/textrec/snapshot-*.json.tmp; done\n"
                + "echo __OK__\n";
        return run(script, 20);
    }
}
