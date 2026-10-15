package com.textrec.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 抓调用栈、清洗、算“位置签名”。 */
final class Stacks {
    private Stacks() {}

    /** 一份清洗后的调用栈。 */
    static final class Stk {
        /** 用于显示的帧（最多 depth 个） */
        final String[] lines;
        /** 被截掉的帧数 */
        final int more;
        /** 位置签名：只由前几个稳定帧算出，用于归并 */
        final int sig;
        /** 最靠前的几个 App 帧 {类名, 方法名}，用来反射查可 hook 的方法签名 */
        final String[][] top;
        final boolean compose;

        Stk(String[] lines, int more, int sig, String[][] top, boolean compose) {
            this.lines = lines;
            this.more = more;
            this.sig = sig;
            this.top = top;
            this.compose = compose;
        }
    }

    static boolean isFramework(String cn) {
        return cn.startsWith("android.") || cn.startsWith("java.") || cn.startsWith("javax.")
                || cn.startsWith("dalvik.") || cn.startsWith("com.android.internal.")
                || cn.startsWith("libcore.") || cn.startsWith("sun.");
    }

    static boolean isInfra(String cn) {
        return cn.startsWith("de.robv.android.xposed.") || cn.startsWith("org.lsposed.")
                || cn.startsWith("LSPHooker_") || cn.startsWith("com.textrec.lsp.");
    }

    /** 选 hook 目标时跳过的语言库帧 */
    private static boolean isLangLib(String cn) {
        return cn.startsWith("kotlin.") || cn.startsWith("kotlinx.");
    }

    /**
     * 抓当前调用栈：去掉 Hook 框架自身的帧（含它生成的跳板类、intercept/proceed 调用链）；
     * dropLeading=true 时再去掉最前面的系统帧，直接露出 App 代码。
     */
    static List<StackTraceElement> capture(boolean dropLeading) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        // 1) Hook 回调帧（类名可能被混淆，认方法名）：它及它之前的全是框架自身
        int start = 0;
        for (int i = 0; i < st.length && i < 16; i++) {
            String m = st[i].getMethodName();
            if (m.equals("callBeforeHookedMethod") || m.equals("callAfterHookedMethod")) start = i + 1;
        }
        // 2) 紧跟的 intercept/proceed 链，以及没有行号的“系统类”（Hook 框架生成的跳板）
        while (start < st.length) {
            StackTraceElement e = st[start];
            String m = e.getMethodName();
            boolean trampoline = e.getLineNumber() < 0 && !e.isNativeMethod() && e.getClassName().startsWith("android.");
            if (m.equals("intercept") || m.equals("proceed") || trampoline) start++;
            else break;
        }
        ArrayList<StackTraceElement> out = new ArrayList<>(st.length);
        boolean leading = dropLeading;
        for (int i = start; i < st.length; i++) {
            StackTraceElement e = st[i];
            String cn = e.getClassName();
            if (isInfra(cn)) continue;
            if (leading && isFramework(cn)) continue;
            leading = false;
            out.add(e);
        }
        if (out.isEmpty() && dropLeading) return capture(false);
        return out;
    }

    private static final Pattern LAMBDA1 = Pattern.compile("\\$\\$ExternalSyntheticLambda\\d+");
    private static final Pattern LAMBDA2 = Pattern.compile("\\$\\$Lambda[$/][\\w$]+");
    private static final Pattern LAMBDA_M = Pattern.compile("(lambda\\$[\\w$]*?)(\\$\\d+)+$");

    /** 去掉 lambda 编号等不稳定后缀，保证同一个位置不会因为细微差异裂成多条。 */
    private static String norm(String cn, String mn) {
        String c = LAMBDA1.matcher(cn).replaceAll("\\$\\$L");
        c = LAMBDA2.matcher(c).replaceAll("\\$\\$L");
        String m = LAMBDA_M.matcher(mn).replaceFirst("$1");
        return c + "." + m;
    }

    /**
     * 清洗并构造。
     * - 尾部的系统帧（Handler/Looper/ActivityThread/Zygote 等）只保留紧邻最后一个 App 帧的 1 个。
     * - appOnlySig=true 时签名只看 App 帧（用于绘制栈：TextView.onDraw 这类通用系统帧不参与区分）。
     */
    static Stk build(List<StackTraceElement> raw, int depth, boolean appOnlySig) {
        int lastApp = -1;
        for (int i = 0; i < raw.size(); i++) {
            if (!isFramework(raw.get(i).getClassName())) lastApp = i;
        }
        List<StackTraceElement> fr = raw;
        if (lastApp >= 0 && lastApp + 2 < raw.size()) fr = raw.subList(0, lastApp + 2);

        int n = Math.min(depth, fr.size());
        String[] lines = new String[n];
        for (int i = 0; i < n; i++) lines[i] = fr.get(i).toString();

        // 签名只算到最后一个 App 帧为止：尾部保留的那个系统帧只用于显示，不参与签名
        // （否则 Handler.handleCallback / dispatchMessage 这类调度差异会让同一位置裂成多条）。
        // 整条栈都是系统帧时，才用系统帧算签名。
        int sigEnd = lastApp >= 0 ? lastApp + 1 : fr.size();
        int h = 17, used = 0, idx = 0;
        boolean compose = false;
        ArrayList<String[]> top = new ArrayList<>(3);
        for (StackTraceElement e : fr) {
            boolean inSig = idx++ < sigEnd;
            String cn = e.getClassName();
            if (cn.startsWith("androidx.compose.ui.")) compose = true;
            boolean fw = isFramework(cn);
            if (inSig && used < 8 && !(appOnlySig && fw)) {
                String nm = norm(cn, e.getMethodName());
                h = h * 31 + nm.hashCode();
                used++;
            }
            if (top.size() < 3 && !fw && !isLangLib(cn)) {
                top.add(new String[]{cn, e.getMethodName()});
            }
        }
        if (used == 0) h = h * 31 + 0x5f3759df; // 纯系统栈：给一个固定签名
        return new Stk(lines, fr.size() - n, h, top.toArray(new String[0][]), compose);
    }
}
