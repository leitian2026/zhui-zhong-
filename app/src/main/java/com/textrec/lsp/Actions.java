package com.textrec.lsp;

import android.content.SharedPreferences;

import java.util.List;

/** 配置页和记录页共用：保存配置、通过 root 推送、清空记录。 */
final class Actions {
    private Actions() {}

    static final String PREFS = "textrec";
    static final String DEFAULT_PKG = "com.ss.android.ugc.aweme";

    static RecConfig loadCfg(SharedPreferences sp) {
        return RecConfig.fromText(sp.getString("cfg", ""));
    }

    static List<String> pkgs(SharedPreferences sp) {
        return RecConfig.splitLines(sp.getString("pkgs", DEFAULT_PKG));
    }

    /** 把配置推送给每个包；clear=true 时同时删掉已有快照（配合新的 epoch，目标进程内存里的记录也会被清空）。 */
    static String push(List<String> pkgs, RecConfig c, boolean clear) {
        StringBuilder sb = new StringBuilder();
        String text = c.toText();
        for (String pkg : pkgs) {
            if (!RootShell.validPkg(pkg)) {
                sb.append("✗ ").append(pkg).append("：包名格式不对\n");
                continue;
            }
            RootShell.Result r = RootShell.pushConfig(pkg, text);
            if (r.out.contains("__OK__")) {
                if (clear) RootShell.clearSnapshots(pkg);
                sb.append("✓ ").append(pkg).append(clear ? "：已推送并清空记录\n" : "：已推送\n");
            } else if (r.out.contains("__NOPKG__")) {
                sb.append("✗ ").append(pkg).append("：").append(RootShell.nopkgMsg(r.out)).append('\n');
            } else {
                sb.append("✗ ").append(pkg).append("：失败(").append(r.code).append(") ").append(r.err.trim()).append('\n');
            }
        }
        return sb.toString();
    }
}
