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

        /** 设置发生在 XML 布局加载里（layout 里写死的 src/text），栈里没有业务代码，不用于 hook */
        final boolean xmlOnly;

        Stk(String[] lines, int more, int sig, String[][] top, boolean compose) {
            this(lines, more, sig, top, compose, false);
        }

        Stk(String[] lines, int more, int sig, String[][] top, boolean compose, boolean xmlOnly) {
            this.lines = lines;
            this.more = more;
            this.sig = sig;
            this.top = top;
            this.compose = compose;
            this.xmlOnly = xmlOnly;
        }
    }

    /** XML 布局加载链：LayoutInflater / 反射构造 / AppCompat 的 View 工厂。 */
    static boolean isInflation(String cn) {
        return cn.startsWith("android.view.LayoutInflater") || cn.startsWith("java.lang.reflect.Constructor")
                || cn.startsWith("androidx.core.view.LayoutInflaterCompat")
                || cn.startsWith("androidx.appcompat.app.AppCompatViewInflater");
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

    static final String TAG_STABLE = "   [稳定]";
    static final String TAG_WIDGET = "   [稳定·通用控件，hook 后要按 View id/类过滤]";
    static final String TAG_INFRA = "   [稳定·通用基础设施，不建议当 hook 点]";
    static final String TAG_OBF = "   [混淆·换版本会变]";

    /** 通用基础设施：消息分发、LiveData、Fragment 生命周期、异步包装……几乎所有功能都会经过，当 hook 点没有意义。 */
    static boolean isGenericInfra(String cn) {
        return cn.startsWith("com.ss.android.ugc.bytex.") || cn.startsWith("com.bytedance.qmi.")
                || cn.startsWith("androidx.lifecycle.") || cn.startsWith("androidx.fragment.app.")
                || cn.startsWith("androidx.core.") || cn.startsWith("androidx.recyclerview.")
                || cn.startsWith("androidx.appcompat.app.") || cn.startsWith("androidx.arch.")
                || cn.startsWith("androidx.activity.") || cn.startsWith("androidx.collection.");
    }

    /** 通用控件：hook 它会影响同类的所有控件，必须再按 View id / 类 / 父级过滤。 */
    static boolean isGenericWidget(String cn) {
        return cn.startsWith("androidx.appcompat.widget.") || cn.startsWith("androidx.constraintlayout.")
                || cn.startsWith("com.bytedance.dux.") || cn.startsWith("com.bytedance.ies.dmt.ui.");
    }

    static String tagFor(String cn, String mn) {
        if (isObfuscated(cn, mn)) return TAG_OBF;
        if (isGenericInfra(cn)) return TAG_INFRA;
        if (isGenericWidget(cn)) return TAG_WIDGET;
        return TAG_STABLE;
    }
    /** 字节的构建把方法名改成 LJ / LIZ / LJIIIIZZ 这类；R8 会把名字缩成 a、b、aa… */
    private static final Pattern OBF_METHOD = Pattern.compile("L[A-Z]{1,24}|[A-Za-z]");
    /** 两个字符的方法名里真实存在的（其余的如 qb / Ea / J9 / K9 当成 R8 缩出来的混淆名）。 */
    private static final java.util.Set<String> SHORT_OK = new java.util.HashSet<>(java.util.Arrays.asList(
            "on", "of", "to", "is", "id", "up", "un", "at", "by", "or"));

    /** 混淆名：类名 X.xxx、方法名 LJxxx、或类名只有 1~2 个字符。这类名字 App 一更新就会变。 */
    static boolean isObfuscated(String cn, String mn) {
        if (cn.startsWith("X.")) return true;
        if (mn != null && OBF_METHOD.matcher(mn).matches()) return true;
        if (mn != null && mn.length() == 2 && !SHORT_OK.contains(mn.toLowerCase(java.util.Locale.ROOT))) return true;
        int i = cn.lastIndexOf('.');
        String simple = i >= 0 ? cn.substring(i + 1) : cn;
        int d = simple.indexOf('$');
        if (d > 0) simple = simple.substring(0, d);
        return simple.length() <= 2;
    }

    /** 稳定：不是系统/语言库/本模块，也没被混淆（如 androidx.compose.ui.text.MultiParagraph.paint-LG529CI） */
    static boolean isStable(String cn, String mn) {
        return !isFramework(cn) && !isLangLib(cn) && !isInfra(cn) && !isObfuscated(cn, mn);
    }

    private static boolean noise(String mn) {
        return mn.contains("$default") || mn.contains("lambda$") || mn.contains("$lambda") || mn.equals("<clinit>");
    }

    private static boolean hasFrame(List<String[]> top, String cn, String mn) {
        for (String[] f : top) if (f[0].equals(cn) && f[1].equals(mn)) return true;
        return false;
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

    /**
     * TextView 的绘制栈里，TextView.onDraw 往后全是祖先控件的 dispatchDraw/drawChild，
     * 那只反映控件在布局树里的位置，不是这段文字本身，还会让同一类文字被拆成多条。
     * 这里只保留到“画这个 View 的 View.draw / updateDisplayListIfDirty”为止，
     * 其中的 App 帧就是子类重写的 onDraw（如 AntiBurnDuxTextView.onDraw）。
     * 传入的栈应当用 capture(false) 抓（含最前面的系统帧）。
     */
    static List<StackTraceElement> trimToViewDraw(List<StackTraceElement> full) {
        int i0 = -1;
        for (int i = 0; i < full.size(); i++) {
            StackTraceElement e = full.get(i);
            if (e.getClassName().equals("android.widget.TextView") && e.getMethodName().equals("onDraw")) {
                i0 = i;
                break;
            }
        }
        if (i0 < 0) return full;
        int j = i0 + 1;
        while (j < full.size()) {
            StackTraceElement e = full.get(j);
            if (e.getClassName().equals("android.view.View")
                    && (e.getMethodName().equals("draw") || e.getMethodName().equals("updateDisplayListIfDirty"))) break;
            j++;
        }
        return new ArrayList<>(full.subList(0, j));
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
            String c0 = raw.get(i).getClassName();
            // 尾部的消息分发/LiveData/异步包装这类通用基础设施帧不算“业务帧”，从它们开始截掉
            if (!isFramework(c0) && !isGenericInfra(c0)) lastApp = i;
        }
        List<StackTraceElement> fr = raw;
        if (lastApp >= 0 && lastApp + 2 < raw.size()) fr = raw.subList(0, lastApp + 2);

        // 显示行：连续的 XML 布局加载帧（LayoutInflater / Constructor.newInstance…）折叠成一行
        ArrayList<String> disp = new ArrayList<>();
        ArrayList<Integer> cover = new ArrayList<>();
        for (int i = 0; i < fr.size(); ) {
            if (isInflation(fr.get(i).getClassName())) {
                int j = i;
                while (j < fr.size() && isInflation(fr.get(j).getClassName())) j++;
                disp.add("[XML 布局加载 " + (j - i) + " 帧：Constructor.newInstance … LayoutInflater.inflate]");
                cover.add(j - i);
                i = j;
            } else {
                disp.add(fr.get(i).toString());
                cover.add(1);
                i++;
            }
        }
        int n = Math.min(depth, disp.size());
        String[] lines = new String[n];
        int covered = 0;
        for (int i = 0; i < n; i++) {
            lines[i] = disp.get(i);
            covered += cover.get(i);
        }

        // 签名只算到最后一个 App 帧为止：尾部保留的那个系统帧只用于显示，不参与签名
        // （否则 Handler.handleCallback / dispatchMessage 这类调度差异会让同一位置裂成多条）。
        // 整条栈都是系统帧时，才用系统帧算签名。
        int sigEnd = lastApp >= 0 ? lastApp + 1 : fr.size();
        int h = 17, used = 0, idx = 0;
        boolean compose = false;
        ArrayList<String[]> top = new ArrayList<>(6);
        int extras = 0, scanned = 0, appLayer = 0; // appLayer：只数 App 帧（不含系统和 kotlin 库帧）
        for (StackTraceElement e : fr) {
            boolean inSig = idx++ < sigEnd;
            String cn = e.getClassName();
            String mn = e.getMethodName();
            if (cn.startsWith("androidx.compose.ui.")) compose = true;
            boolean fw = isFramework(cn);
            if (inSig && used < 8 && !(appOnlySig && fw)) {
                String nm = norm(cn, e.getMethodName());
                h = h * 31 + nm.hashCode();
                used++;
            }
            if (!fw && !isLangLib(cn)) {
                appLayer++;
                if (top.size() < 3) {
                    // 连续的构造方法链 / 重载链会出现同一个“类#方法”好几次，只留一个
                    if (!hasFrame(top, cn, mn)) top.add(new String[]{cn, mn, String.valueOf(appLayer)});
                } else if (extras < 3 && appLayer <= 14 && !noise(mn) && isStable(cn, mn) && !isGenericInfra(cn)
                        && !hasFrame(top, cn, mn)) {
                    // 前 3 个常常是混淆帧；再多带最多 3 个“没混淆”的帧，换版本后还能用（通用基础设施帧不要）
                    top.add(new String[]{cn, mn, String.valueOf(appLayer)});
                    extras++;
                }
            }
            scanned++;
        }
        if (used == 0) h = h * 31 + 0x5f3759df; // 纯系统栈：给一个固定签名

        // XML 里写死的设置：布局加载链之前只有构造方法 / 系统帧 / 通用控件帧，没有任何业务代码。
        boolean xmlOnly = false;
        int fi = -1;
        for (int i = 0; i < fr.size(); i++) {
            if (isInflation(fr.get(i).getClassName())) {
                fi = i;
                break;
            }
        }
        if (fi >= 0) {
            xmlOnly = true;
            for (int i = 0; i < fi; i++) {
                StackTraceElement e = fr.get(i);
                String c1 = e.getClassName();
                if (!(e.getMethodName().equals("<init>") || isFramework(c1) || isGenericWidget(c1))) {
                    xmlOnly = false;
                    break;
                }
            }
        }
        if (xmlOnly) top.clear(); // 没有可 hook 的业务点，不浪费签名解析和导出体积
        return new Stk(lines, fr.size() - covered, h, top.toArray(new String[0][]), compose, xmlOnly);
    }
}
