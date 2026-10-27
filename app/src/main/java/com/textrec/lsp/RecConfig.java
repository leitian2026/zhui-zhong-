package com.textrec.lsp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 配置：模块界面写出，目标 App 里的 Hook 读取。两边共用同一个类。
 * {@link #toText()} / {@link #fromText(String)} 是标准 Java Properties 格式（key=value，一行一个），
 * 字段名就是下面这些 public 字段的名字，完整列表和含义见仓库 README「采集配置」一节——
 * 首页顶栏「粘贴配置」接受的就是这段文本，直接 Properties.load() 解析，没写的字段用 new RecConfig() 的默认值。
 */
public final class RecConfig {
    public boolean enabled = true;
    /** false = 全量记录（默认）；true = 只记录命中关键字的文字 */
    public boolean keywordMode = false;
    public String keywords = "";
    public boolean regex = false;
    /** 已废弃：不再有任何作用（旧配置里的这个键会被忽略）。想只记方法追踪，把文字 / 图标 / 颜色 / 状态这几个记录开关都关掉即可。 */
    public boolean traceOnly = false;
    public boolean traceSet = true;
    public boolean traceDraw = true;
    /** 记录文字颜色：画笔颜色（所有文字）+ TextView 的 setTextColor 设置位置 */
    public boolean traceColor = true;
    /** 记录图标/按钮图片：ImageView 的 setImage* 与 View.setContentDescription */
    public boolean traceIcons = false;
    /** 自动观察 Compose 文字绘制（MultiParagraph.paint）的参数：颜色、描边/填充等 */
    public boolean autoParams = true;
    /** 记录“只被 setText、从未被绘制”的文字（控件 INVISIBLE/GONE/尺寸为 0/未挂窗口，如拖动才出现的时长） */
    public boolean traceSetOnly = false;
    /** 记录被跟踪 View 的 alpha / visibility / selected 变化（谁在改、改成什么） */
    public boolean traceState = false;
    /** 这类记录的上限（单独计，不占用 maxRecords 的大头） */
    public int maxSetOnly = 500;
    /** 方法追踪：每行 类#方法，记录每次调用的参数/返回值和调用栈（只读）。// 开头为注释 */
    public String traceMethods = "";
    // ---- 采集端过滤（默认全部不过滤；被挡掉的调用不抓栈、不建记录，只计数）----
    /** 触发窗口：最近一次触摸后多少毫秒内才记录；0 = 不限制。默认只管方法追踪。 */
    public int windowMs = 0;
    /** 触发窗口也用于文字 / 图标 / 颜色 / 状态 / 绘制记录 */
    public boolean windowAll = false;
    /** 排除名单（每行或逗号分隔）：类#方法、类#*（整个类）、#方法（任何类里的这个方法）、类名或包名前缀 */
    public String excludeList = "";
    /** 线程白名单：线程名包含任意一个词才记（如 main、LegoHandler）；空 = 不限 */
    public String threadFilter = "";
    /** 页面白名单：页面标签包含任意一个词才记（不区分大小写）；空 = 不限 */
    public String pageFilter = "";
    /** 控件白名单：View 类名或 id 包含任意一个词才记（文字 / 图标 / 状态 / 绘制）；空 = 不限 */
    public String viewFilter = "";
    /** 值匹配：方法追踪的参数或返回值里包含其中任意一个值才记；时长会自动展开成多种写法；空 = 不限 */
    public String valueFilter = "";
    /** 开关：排除通用基础设施（LiveData、Fragment 生命周期、字节的异步包装等，几乎所有功能都会经过的类） */
    public boolean excludeInfra = false;
    /** 线程：0 = 全部，1 = 只主线程，2 = 只后台线程 */
    public int threadMode = 0;
    /** 开关：方法追踪只记参数或返回值里带时间的调用（形如 00:00、1:37） */
    public boolean valueClock = false;
    /** 开关：方法追踪时，每隔 traceDumpEvery 次调用把 this 周围的字段值记一份（找进度 / 时长这类状态存在哪里），同时统计第一个数值参数的 last / min / max */
    public boolean traceDump = false;
    public int traceDumpEvery = 25;
    /** 方法追踪的字段快照：一份最多多少行（默认 40） */
    public int traceDumpLines = 40;
    /** 方法追踪的字段快照：往子对象里最多展开几层，0 = 只列对象自己的数值和文字字段（默认 3） */
    public int traceDumpDepth = 3;
    /**
     * 视图树导出（1.27.0）：任务开始 / 任务结束 / 点「标记」时，找屏幕上类名或 id 含 viewDumpKey 的控件（默认视频文案的两个类），
     * 沿父级往上 viewDumpUp 层，把那一层容器的整棵子树（往下 viewDumpDepth 层、最多 viewDumpLines 行）列出来。用来弄清一块界面里各个控件的父子关系和布局类型。
     */
    public boolean viewDump = false;
    public String viewDumpKey = "ScrollMentionTextView,MentionTextView";
    public int viewDumpUp = 5;
    public int viewDumpDepth = 5;
    public int viewDumpLines = 400;
    /** 视图树里的文字控件，带上「这段文字最近是谁 setText 的」（要开着「文字是谁设置的」，且不被方法追踪独占关掉）。 */
    public boolean viewDumpSet = true;
    /** 点哪查哪：每次点屏幕（按下抬起位置差不多、不是滑动），把这个点上所有控件（从外层到最上层）列出来，不需要知道控件叫什么。 */
    public boolean viewTap = false;
    /**
     * 标注（1.29.0）：给记录加「语义标签」，让找钩子更快更准。
     * annotFrames：调用栈每一帧标分层 / 角色 / 稳定度，标出入口帧、推荐钩点、异步边界；钩点评级、类角色。
     * annotOwners：方法追踪命中时，反查 this / 参数（如 Aweme）被哪个容器（ViewHolder / Fragment / ViewModel / View 标签）持有。
     * annotSweep：任务开始 / 结束 / 点标记时扫一遍「容器 → 持有的数据实体」。
     * annotBudgetMs：一次反查最多花多少毫秒（后台线程）；annotPerKey：每个追踪方法前几次命中做反查，之后每 300 次一次。
     */
    public boolean annotFrames = true;
    /**
     * 数据树（1.31.0）：方法追踪命中时把 this / 参数 / 返回值沿字段展开成树，记每个节点在每个任务里的取值。
     * treeOn：开关；treeDepth：往下展开几层；treeNodes：一次最多记多少个节点；treeEvery：每个方法前 3 次命中和之后每隔几次展开一次
     * （进入新任务 / 新一遍 / 有新的用户输入时也会展开）。
     * watch：手写观察点，每行「类#方法:路径」（路径见导出里的数据树，如 com.a.B#foo:arg0.mAweme.statistics.diggCount）；
     * watchAuto：自动把值变过的叶子也加成观察点（最多 24 个），找出是哪个方法改的它。
     */
    public boolean treeOn = true;
    public int treeDepth = 4;
    public int treeNodes = 200;
    public int treeEvery = 10;
    /** treeViews（1.32.1）：打开后数据树也展开控件（View）自己的业务字段；控件里再碰到的别的控件仍只记一行，不往下走。默认关。 */
    public boolean treeViews = false;
    public String watch = "";
    public boolean watchAuto = true;
    public boolean annotOwners = true;
    public boolean annotSweep = true;
    public int annotBudgetMs = 40;
    public int annotPerKey = 6;
    /** 链路追踪：以方法追踪的方法为锚点，串起上游（异步来源）和下游（被调用的方法、界面效果）。总开关 + 三个子开关 */
    /** 时间线：用户操作 / 页面 / 各位置的记录样本，按毫秒排成一条；标记和基线从记录页发来 */
    public boolean tlOn = true;
    public boolean tlOps = true;
    public boolean tlSamples = true;
    /** 任务外也记录（默认关）：任务浮窗的流程进行中，只有「任务开始 → 任务结束」之间才建记录；打开后任务外也记，但单独一桶存（各自上限、各自文件）。没用任务浮窗时不受影响，照常全记。 */
    public boolean recOutside = false;
    /**
     * 方法追踪独占（默认开）：「方法追踪」里填了方法时，运行时自动把文字 / 图标 / 颜色 / 状态 / 绘制 / Compose 观察这些记录开关全部当成关闭，
     * 记录里只剩方法追踪（更干净，也更省性能）。只改目标 App 里读到的配置副本，不改界面上的开关和配置文件；方法清单清空后立刻恢复。
     * 关掉它 = 方法追踪和其他记录可以同时开。见 {@link #applyTraceExclusive()}。
     */
    public boolean traceExclusive = true;
    /** Compose 文字绘制（MultiParagraph.paint*） */
    public boolean traceCompose = true;
    /** Compose 记 MultiParagraph 对象身份 */
    public boolean composeObj = true;
    /** Compose 展开 MultiParagraph 字段（含文字） */
    public boolean composeDump = true;
    /** 时间线：触摸 */
    public boolean tlTouch = true;
    /** 时间线：按键 */
    public boolean tlKey = true;
    /** 时间线：页面切换 */
    public boolean tlPage = true;
    /** 时间线：控件增删 */
    public boolean tlViewTree = true;
    /** 样本：设文字/清空 */
    public boolean smpText = true;
    /** 样本：状态变化 */
    public boolean smpState = true;
    /** 样本：图标 */
    public boolean smpIcon = true;
    /** 样本：方法追踪调用 */
    public boolean smpTrace = true;
    /** 方法追踪保存的调用栈最大层数 */
    public int traceStackDepth = 30;
    /** 每种记录类型（TextView/ICON/STATE/TRACE…）各自最多多少条，一种灌满不会挤掉别的种类 */
    public int maxPerKind = 500;

    // ---- 1.24 更细的开关 / 每种记录各自的上限 / 每种栈各自的深度 / 分文件存 ----
    /** 文字是谁设置的：setText / append / setHint（总开关 traceSet 开着时才起作用，下同） */
    public boolean setText = true;
    /** 文字被清空（setText 空串 / null） */
    public boolean setClear = true;
    /** 文字是谁画的：TextView 的绘制 */
    public boolean drawTextView = true;
    /** 文字是谁画的：自绘（非 Compose 的 Canvas） */
    public boolean drawCanvas = true;
    /** 文字是谁画的：Compose（这个关掉，Compose 文字的绘制记录就不记；traceCompose 管的是 paint 参数的观察） */
    public boolean drawCompose = true;
    /** 文字颜色：画笔颜色 */
    public boolean colorPaint = true;
    /** 文字颜色：TextView 的 setTextColor 设置位置 */
    public boolean colorSet = true;
    /** 图标：ImageView 的 setImage* */
    public boolean iconImage = true;
    /** 图标：View.setContentDescription */
    public boolean iconDesc = true;
    /** 状态变化：透明度 / 可见性 / 选中态各自一个 */
    public boolean stateAlpha = true;
    public boolean stateVisible = true;
    public boolean stateSelected = true;
    /** 时间线：方法追踪的钩点什么时候可用 */
    public boolean tlClass = true;
    /** 链路追踪的上游：协程调度（kotlinx CoroutineScheduler）。尽力而为，类被混淆或没用协程时找不到 */
    public boolean chainCoroutine = true;
    /** Compose 字段展开：一条记录里，第一次之后每隔多少次再展开一份（一条记录最多 6 份） */
    public int composeDumpEvery = 50;
    /** Compose 字段展开：一份最多多少行 */
    public int composeDumpLines = 60;
    /** 字段展开默认只进 App 自己的类；这里列出的包 / 类名前缀也进（逗号或换行分隔）。默认放开 Compose 文字类 */
    public String dumpPrefixes = "androidx.compose.ui.text.";
    // 每种记录各自的上限（互不挤占）。maxPerKind 已废弃，只给没有单独上限的种类用
    public int capTextView = 800;
    public int capCanvas = 300;
    public int capCompose = 400;
    public int capClear = 300;
    public int capIcon = 300;
    public int capState = 300;
    public int capTrace = 600;
    // 每种栈各自的深度；0 = 用 stackDepth（方法追踪另有 traceStackDepth）
    public int depthSet = 0;
    public int depthDraw = 0;
    public int depthColor = 0;
    public int depthIcon = 0;
    public int depthState = 0;
    /** 分文件存：每种记录一个快照文件、时间线一个文件，各自独立；关掉 = 回到一个文件 + 总上限 maxRecords */
    public boolean splitFiles = true;

    /** 某种记录的上限。 */
    int capFor(String kind) {
        if (Rec.TEXTVIEW.equals(kind)) return capTextView;
        if (Rec.CANVAS.equals(kind)) return capCanvas;
        if (Rec.COMPOSE.equals(kind)) return capCompose;
        if (Rec.TEXTCLEAR.equals(kind)) return capClear;
        if (Rec.TEXTSET.equals(kind)) return maxSetOnly;
        if (Rec.STATE.equals(kind)) return capState;
        if (Rec.ICON.equals(kind)) return capIcon;
        if (Rec.TRACE.equals(kind)) return capTrace;
        return maxPerKind;
    }

    /** 某种栈的深度：set / draw / color / icon / state；方法追踪用 traceStackDepth。 */
    int depthFor(String which) {
        int d;
        if ("set".equals(which)) d = depthSet;
        else if ("draw".equals(which)) d = depthDraw;
        else if ("color".equals(which)) d = depthColor;
        else if ("icon".equals(which)) d = depthIcon;
        else if ("state".equals(which)) d = depthState;
        else d = 0;
        return d > 0 ? clamp(d, 1, 60) : stackDepth;
    }
    public boolean chainOn = false;
    public boolean chainAsync = true;
    public boolean chainEffects = true;
    public boolean chainDown = true;
    /** 前 5 次调用每次都建链，之后每隔 chainEvery 次建一次 */
    public int chainEvery = 20;
    /** 一次链里最多列多少个被调用的方法 */
    public int chainMaxDown = 120;
    /** 持续型自动降频：同一位置调用超过这个次数、且持续超过 quietSpanMs，之后只计数不抓栈；0 = 关 */
    public int quietCount = 0;
    public int quietSpanMs = 3000;

    /** 每条记录里保存的调用栈最大帧数 */
    public int stackDepth = 12;
    /** 同一个“位置”前几次要抓栈 */
    public int perKeyStacks = 3;
    /** 之后每隔多少次再抽样抓一次（发现同一控件的其他调用路径） */
    public int sampleEvery = 200;
    /** 全局每秒最多抓多少次栈（兜底） */
    public int stacksPerSec = 1000;
    public int maxRecords = 2000;
    /** 落盘节流（秒） */
    public int flushSec = 3;
    /** 变了就清空已有记录重新开始 */
    public long epoch = 0;

    /** keywords 的拆分：正则模式只按换行拆（正则里可能有逗号），普通模式按逗号/换行拆 */
    static List<String> split(String s, boolean regexMode) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(regexMode ? "[\\n\\r]+" : "[,\\n\\r]+")) {
            String t = regexMode ? p : p.trim();
            if (!t.trim().isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 【给其他 AI / 维护者】首页顶部「粘贴」框里的一份文本同时装采集配置和任务清单，这里把它拆成两段：返回 {配置文本, 任务清单文本}。
     * 规则（逐行判断，不需要分隔标记）：
     *  - 以 `字母或下划线开头的标识符 =` 开头的行是配置行（Properties 的 key=value）；行尾是奇数个反斜杠表示下一行是它的续行，续行也归配置。
     *  - 其余的非空行、非 `#` 注释行是任务清单行（格式「名称|提示|秒数」，见 GuideService 的类注释），`//` 开头的任务注释原样保留给 GuideService 去处理。
     *  - 也可以显式写一行 `[任务清单]` / `[tasks]`，之后的所有行都算任务清单（任务名恰好像 key= 时用）。
     * 配置段为空 = 这次没带配置，界面开关保持不动；任务段为空 = 没带任务清单，保留原来存的清单。
     */
    public static String[] splitPaste(String text) {
        return PasteBlocks.split(text);
    }

    private static boolean endsWithOddBackslash(String s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
        return n % 2 == 1;
    }

    /**
     * 【给其他 AI / 维护者】方法追踪独占：只在目标 App 的 Recorder.reload() 里、读完配置后调用一次。
     * 条件：traceExclusive 开着，并且 traceMethods 里至少有一个有效目标（非空、非 // 注释）。
     * 效果：把下面这些“其他记录”的开关在内存副本里全部置 false，让记录里只剩 kind=TRACE。
     * 为什么在这里改而不是在界面改：界面上用户勾的开关要原样保留，清空方法清单后就自动恢复；
     * 各个 hook 在每次调用时都会读 cfg 里的这些字段（见 Recorder 里 c.traceIcons / c.traceState 等判断），所以改完立即生效，不用重装钩子。
     * 不受影响的：时间线的用户操作 / 页面 / 标记（它们不是“记录”）、链路追踪（以方法追踪为锚点）、任务浮窗的任务内 / 任务外逻辑（Timeline.skip）。
     * 注意：这个方法会改 this，所以只能对「刚读出来、还没发布给 Hook 用」的实例调用。
     */
    public void applyTraceExclusive() {
        if (!traceExclusive || splitTargets(traceMethods).isEmpty()) return;
        traceSet = false;
        traceDraw = false;
        traceColor = false;
        traceIcons = false;
        autoParams = false;
        traceSetOnly = false;
        traceState = false;
        traceCompose = false;
    }

    /** 方法追踪目标：只按换行拆，去掉参数括号和 // 注释。 */
    static List<String> splitTargets(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[\\n\\r]+")) {
            String t = p.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int at = optStart(t);
            if (at > 0) t = t.substring(0, at).trim(); // 去掉行尾的 @选项
            int i = t.indexOf('(');
            if (i > 0) t = t.substring(0, i).trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** 方法追踪每一行可以带的选项。 */
    static final class TraceOpt {
        /** 只计数：不记参数变体、不抓栈 */
        boolean countOnly;
        /** 只记参数 / 返回值变体：不抓栈 */
        boolean argsOnly;
        /** 这一行单独的栈深度，0 = 用全局的 */
        int depth;
        /** 这一行单独的抽样间隔，0 = 用全局的 */
        int every;
        /** @tag=N：第 N 个参数（从 0 数）当作“当前标识”（如视频 id）；@tag=ret 用返回值 */
        int tagArg = -2;
        /** @seq：任务里每一次调用都按先后记一条序列事件（毫秒、线程、参数 / 返回值、栈顶几帧），不合并、不抽样 */
        boolean seq;
        /** @seq=N：这个方法每个任务阶段最多记 N 条，0 = 用默认（Timeline.SEQ_PER_DEFAULT） */
        int seqMax;
    }

    /** 行里第一个“空白 + @”的空白位置；没有返回 -1。 */
    private static int optStart(String t) {
        for (int i = 1; i < t.length(); i++) {
            if (t.charAt(i) == '@' && Character.isWhitespace(t.charAt(i - 1))) return i - 1;
        }
        return -1;
    }

    /**
     * 方法追踪行尾的选项：「类#方法 @count」「类#方法 @args」「类#方法 @stack=4 @every=10」，
     * 选项之间用空格、逗号或 @ 分隔。key 是去掉选项和参数括号后的「类#方法」。
     */
    static Map<String, TraceOpt> traceOpts(String s) {
        HashMap<String, TraceOpt> out = new HashMap<>();
        if (s == null) return out;
        for (String p : s.split("[\\n\\r]+")) {
            String t = p.trim();
            if (t.isEmpty() || t.startsWith("//")) continue;
            int at = optStart(t);
            if (at < 0) continue;
            String opts = t.substring(at).trim();
            String target = t.substring(0, at).trim();
            int i = target.indexOf('(');
            if (i > 0) target = target.substring(0, i).trim();
            if (target.isEmpty()) continue;
            TraceOpt o = new TraceOpt();
            for (String w : opts.split("[@,\\s]+")) {
                if (w.isEmpty()) continue;
                if (w.equals("count")) {
                    o.countOnly = true;
                } else if (w.equals("args")) {
                    o.argsOnly = true;
                } else if (w.equals("seq") || w.startsWith("seq=")) {
                    o.seq = true;
                    if (w.startsWith("seq=")) {
                        int v = intOf(w.substring(4));
                        if (v > 0) o.seqMax = clamp(v, 1, 1000);
                    }
                } else if (w.startsWith("stack=") || w.startsWith("depth=")) {
                    int v = intOf(w.substring(w.indexOf('=') + 1));
                    if (v > 0) o.depth = clamp(v, 1, 60);
                } else if (w.startsWith("tag=")) {
                    String tv = w.substring(4);
                    o.tagArg = tv.equals("ret") ? -1 : Math.max(0, intOf(tv));
                } else if (w.startsWith("every=")) {
                    int v = intOf(w.substring(w.indexOf('=') + 1));
                    if (v > 0) o.every = clamp(v, 1, 100000);
                }
            }
            out.put(target, o);
        }
        return out;
    }

    private static int intOf(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    static List<String> splitLines(String s) {
        ArrayList<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split("[,\\n\\r]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public String summary() {
        return "enabled=" + enabled + " mode=" + (keywordMode ? "keyword" : "all")
                + " regex=" + regex + " set=" + traceSet + " draw=" + traceDraw
                + " color=" + traceColor + " icons=" + traceIcons + " auto=" + autoParams
                + " setOnly=" + traceSetOnly + "/" + maxSetOnly + " state=" + traceState
                + " traces=" + splitTargets(traceMethods).size()
                + " window=" + windowMs + (windowAll ? "/all" : "") + " quiet=" + quietCount + "/" + quietSpanMs
                + " infra=" + excludeInfra + " thrMode=" + threadMode + " clock=" + valueClock
                + " thr=" + splitLines(threadFilter).size() + " page=" + splitLines(pageFilter).size()
                + " view=" + splitLines(viewFilter).size() + " excl=" + splitLines(excludeList).size()
                + " val=" + splitLines(valueFilter).size()
                + " depth=" + stackDepth + " perKey=" + perKeyStacks + " sample=" + sampleEvery
                + " perSec=" + stacksPerSec + " max=" + maxRecords + " flush=" + flushSec;
    }

    public String toText() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", String.valueOf(enabled));
            p.setProperty("keywordMode", String.valueOf(keywordMode));
            p.setProperty("keywords", keywords);
            p.setProperty("regex", String.valueOf(regex));
            p.setProperty("traceOnly", String.valueOf(traceOnly));
            p.setProperty("traceSet", String.valueOf(traceSet));
            p.setProperty("traceDraw", String.valueOf(traceDraw));
            p.setProperty("traceColor", String.valueOf(traceColor));
            p.setProperty("traceIcons", String.valueOf(traceIcons));
            p.setProperty("autoParams", String.valueOf(autoParams));
            p.setProperty("traceSetOnly", String.valueOf(traceSetOnly));
            p.setProperty("traceState", String.valueOf(traceState));
            p.setProperty("maxSetOnly", String.valueOf(maxSetOnly));
            p.setProperty("traceMethods", traceMethods);
            p.setProperty("windowMs", String.valueOf(windowMs));
            p.setProperty("windowAll", String.valueOf(windowAll));
            p.setProperty("excludeInfra", String.valueOf(excludeInfra));
            p.setProperty("threadMode", String.valueOf(threadMode));
            p.setProperty("valueClock", String.valueOf(valueClock));
            p.setProperty("traceDump", String.valueOf(traceDump));
            p.setProperty("traceDumpEvery", String.valueOf(traceDumpEvery));
            p.setProperty("traceDumpLines", String.valueOf(traceDumpLines));
            p.setProperty("traceDumpDepth", String.valueOf(traceDumpDepth));
            p.setProperty("viewDump", String.valueOf(viewDump));
            p.setProperty("viewDumpKey", viewDumpKey);
            p.setProperty("viewDumpUp", String.valueOf(viewDumpUp));
            p.setProperty("viewDumpDepth", String.valueOf(viewDumpDepth));
            p.setProperty("viewDumpLines", String.valueOf(viewDumpLines));
            p.setProperty("viewDumpSet", String.valueOf(viewDumpSet));
            p.setProperty("viewTap", String.valueOf(viewTap));
            p.setProperty("annotFrames", String.valueOf(annotFrames));
            p.setProperty("treeOn", String.valueOf(treeOn));
            p.setProperty("treeDepth", String.valueOf(treeDepth));
            p.setProperty("treeNodes", String.valueOf(treeNodes));
            p.setProperty("treeEvery", String.valueOf(treeEvery));
            p.setProperty("treeViews", String.valueOf(treeViews));
            p.setProperty("watch", watch);
            p.setProperty("watchAuto", String.valueOf(watchAuto));
            p.setProperty("annotOwners", String.valueOf(annotOwners));
            p.setProperty("annotSweep", String.valueOf(annotSweep));
            p.setProperty("annotBudgetMs", String.valueOf(annotBudgetMs));
            p.setProperty("annotPerKey", String.valueOf(annotPerKey));
            p.setProperty("tlOn", String.valueOf(tlOn));
            p.setProperty("tlOps", String.valueOf(tlOps));
            p.setProperty("tlSamples", String.valueOf(tlSamples));
            p.setProperty("recOutside", String.valueOf(recOutside));
            p.setProperty("traceExclusive", String.valueOf(traceExclusive));
            p.setProperty("traceCompose", String.valueOf(traceCompose));
            p.setProperty("composeObj", String.valueOf(composeObj));
            p.setProperty("composeDump", String.valueOf(composeDump));
            p.setProperty("tlTouch", String.valueOf(tlTouch));
            p.setProperty("tlKey", String.valueOf(tlKey));
            p.setProperty("tlPage", String.valueOf(tlPage));
            p.setProperty("tlViewTree", String.valueOf(tlViewTree));
            p.setProperty("smpText", String.valueOf(smpText));
            p.setProperty("smpState", String.valueOf(smpState));
            p.setProperty("smpIcon", String.valueOf(smpIcon));
            p.setProperty("smpTrace", String.valueOf(smpTrace));
            p.setProperty("traceStackDepth", String.valueOf(traceStackDepth));
            p.setProperty("maxPerKind", String.valueOf(maxPerKind));
            p.setProperty("chainOn", String.valueOf(chainOn));
            p.setProperty("chainAsync", String.valueOf(chainAsync));
            p.setProperty("chainEffects", String.valueOf(chainEffects));
            p.setProperty("chainDown", String.valueOf(chainDown));
            p.setProperty("chainEvery", String.valueOf(chainEvery));
            p.setProperty("chainMaxDown", String.valueOf(chainMaxDown));
            p.setProperty("excludeList", excludeList);
            p.setProperty("threadFilter", threadFilter);
            p.setProperty("pageFilter", pageFilter);
            p.setProperty("viewFilter", viewFilter);
            p.setProperty("valueFilter", valueFilter);
            p.setProperty("quietCount", String.valueOf(quietCount));
            p.setProperty("quietSpanMs", String.valueOf(quietSpanMs));
            p.setProperty("stackDepth", String.valueOf(stackDepth));
            p.setProperty("perKeyStacks", String.valueOf(perKeyStacks));
            p.setProperty("sampleEvery", String.valueOf(sampleEvery));
            p.setProperty("stacksPerSec", String.valueOf(stacksPerSec));
            p.setProperty("maxRecords", String.valueOf(maxRecords));
            p.setProperty("flushSec", String.valueOf(flushSec));
            p.setProperty("setText", String.valueOf(setText));
            p.setProperty("setClear", String.valueOf(setClear));
            p.setProperty("drawTextView", String.valueOf(drawTextView));
            p.setProperty("drawCanvas", String.valueOf(drawCanvas));
            p.setProperty("drawCompose", String.valueOf(drawCompose));
            p.setProperty("colorPaint", String.valueOf(colorPaint));
            p.setProperty("colorSet", String.valueOf(colorSet));
            p.setProperty("iconImage", String.valueOf(iconImage));
            p.setProperty("iconDesc", String.valueOf(iconDesc));
            p.setProperty("stateAlpha", String.valueOf(stateAlpha));
            p.setProperty("stateVisible", String.valueOf(stateVisible));
            p.setProperty("stateSelected", String.valueOf(stateSelected));
            p.setProperty("tlClass", String.valueOf(tlClass));
            p.setProperty("chainCoroutine", String.valueOf(chainCoroutine));
            p.setProperty("splitFiles", String.valueOf(splitFiles));
            p.setProperty("composeDumpEvery", String.valueOf(composeDumpEvery));
            p.setProperty("composeDumpLines", String.valueOf(composeDumpLines));
            p.setProperty("capTextView", String.valueOf(capTextView));
            p.setProperty("capCanvas", String.valueOf(capCanvas));
            p.setProperty("capCompose", String.valueOf(capCompose));
            p.setProperty("capClear", String.valueOf(capClear));
            p.setProperty("capIcon", String.valueOf(capIcon));
            p.setProperty("capState", String.valueOf(capState));
            p.setProperty("capTrace", String.valueOf(capTrace));
            p.setProperty("depthSet", String.valueOf(depthSet));
            p.setProperty("depthDraw", String.valueOf(depthDraw));
            p.setProperty("depthColor", String.valueOf(depthColor));
            p.setProperty("depthIcon", String.valueOf(depthIcon));
            p.setProperty("depthState", String.valueOf(depthState));
            p.setProperty("dumpPrefixes", dumpPrefixes);
            p.setProperty("epoch", String.valueOf(epoch));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.store(bo, null);
            // Properties.store 输出纯 ASCII（非 ASCII 转成 \\uXXXX），换行也被转义，可安全放进 shell heredoc
            return new String(bo.toByteArray(), StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            return "";
        }
    }

    public static RecConfig fromText(String text) {
        RecConfig c = new RecConfig();
        if (text == null || text.isEmpty()) return c;
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    public static RecConfig read(File f) {
        RecConfig c = new RecConfig();
        try (FileInputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            fill(c, p);
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static void fill(RecConfig c, Properties p) {
        c.enabled = bool(p, "enabled", c.enabled);
        c.keywordMode = bool(p, "keywordMode", c.keywordMode);
        c.keywords = p.getProperty("keywords", c.keywords);
        c.regex = bool(p, "regex", c.regex);
        c.traceSet = bool(p, "traceSet", c.traceSet);
        c.traceDraw = bool(p, "traceDraw", c.traceDraw);
        c.traceColor = bool(p, "traceColor", c.traceColor);
        c.traceIcons = bool(p, "traceIcons", c.traceIcons);
        c.autoParams = bool(p, "autoParams", c.autoParams);
        c.traceSetOnly = bool(p, "traceSetOnly", c.traceSetOnly);
        c.traceState = bool(p, "traceState", c.traceState);
        c.traceOnly = bool(p, "traceOnly", c.traceOnly);
        c.maxSetOnly = clamp(num(p, "maxSetOnly", c.maxSetOnly), 10, 5000);
        c.traceMethods = p.getProperty("traceMethods", c.traceMethods);
        c.windowMs = clamp(num(p, "windowMs", c.windowMs), 0, 600000);
        c.windowAll = bool(p, "windowAll", c.windowAll);
        c.excludeInfra = bool(p, "excludeInfra", c.excludeInfra);
        c.threadMode = clamp(num(p, "threadMode", c.threadMode), 0, 2);
        c.valueClock = bool(p, "valueClock", c.valueClock);
        c.traceDump = bool(p, "traceDump", c.traceDump);
        c.traceDumpEvery = clamp(num(p, "traceDumpEvery", c.traceDumpEvery), 1, 1000);
        c.traceDumpLines = clamp(num(p, "traceDumpLines", c.traceDumpLines), 10, 2000);
        c.traceDumpDepth = clamp(num(p, "traceDumpDepth", c.traceDumpDepth), 0, 6);
        c.viewDump = bool(p, "viewDump", c.viewDump);
        c.viewDumpKey = p.getProperty("viewDumpKey", c.viewDumpKey);
        c.viewDumpUp = clamp(num(p, "viewDumpUp", c.viewDumpUp), 0, 12);
        c.viewDumpDepth = clamp(num(p, "viewDumpDepth", c.viewDumpDepth), 1, 10);
        c.viewDumpLines = clamp(num(p, "viewDumpLines", c.viewDumpLines), 50, 3000);
        c.viewDumpSet = bool(p, "viewDumpSet", c.viewDumpSet);
        c.viewTap = bool(p, "viewTap", c.viewTap);
        c.annotFrames = bool(p, "annotFrames", c.annotFrames);
        c.treeOn = bool(p, "treeOn", c.treeOn);
        c.treeDepth = clamp(num(p, "treeDepth", c.treeDepth), 1, 8);
        c.treeNodes = clamp(num(p, "treeNodes", c.treeNodes), 20, 600);
        c.treeEvery = clamp(num(p, "treeEvery", c.treeEvery), 1, 100000);
        c.treeViews = bool(p, "treeViews", c.treeViews);
        c.watch = p.getProperty("watch", c.watch);
        c.watchAuto = bool(p, "watchAuto", c.watchAuto);
        c.annotOwners = bool(p, "annotOwners", c.annotOwners);
        c.annotSweep = bool(p, "annotSweep", c.annotSweep);
        c.annotBudgetMs = clamp(num(p, "annotBudgetMs", c.annotBudgetMs), 5, 500);
        c.annotPerKey = clamp(num(p, "annotPerKey", c.annotPerKey), 1, 100);
        c.tlOn = bool(p, "tlOn", c.tlOn);
        c.tlOps = bool(p, "tlOps", c.tlOps);
        c.tlSamples = bool(p, "tlSamples", c.tlSamples);
        c.recOutside = bool(p, "recOutside", c.recOutside);
        c.traceExclusive = bool(p, "traceExclusive", c.traceExclusive);
        c.traceCompose = bool(p, "traceCompose", c.traceCompose);
        c.composeObj = bool(p, "composeObj", c.composeObj);
        c.composeDump = bool(p, "composeDump", c.composeDump);
        c.tlTouch = bool(p, "tlTouch", c.tlTouch);
        c.tlKey = bool(p, "tlKey", c.tlKey);
        c.tlPage = bool(p, "tlPage", c.tlPage);
        c.tlViewTree = bool(p, "tlViewTree", c.tlViewTree);
        c.smpText = bool(p, "smpText", c.smpText);
        c.smpState = bool(p, "smpState", c.smpState);
        c.smpIcon = bool(p, "smpIcon", c.smpIcon);
        c.smpTrace = bool(p, "smpTrace", c.smpTrace);
        c.traceStackDepth = clamp(num(p, "traceStackDepth", c.traceStackDepth), 1, 80);
        c.maxPerKind = clamp(num(p, "maxPerKind", c.maxPerKind), 20, 20000);
        c.chainOn = bool(p, "chainOn", c.chainOn);
        c.chainAsync = bool(p, "chainAsync", c.chainAsync);
        c.chainEffects = bool(p, "chainEffects", c.chainEffects);
        c.chainDown = bool(p, "chainDown", c.chainDown);
        c.chainEvery = clamp(num(p, "chainEvery", c.chainEvery), 1, 100000);
        c.chainMaxDown = clamp(num(p, "chainMaxDown", c.chainMaxDown), 10, 1000);
        c.excludeList = p.getProperty("excludeList", c.excludeList);
        c.threadFilter = p.getProperty("threadFilter", c.threadFilter);
        c.pageFilter = p.getProperty("pageFilter", c.pageFilter);
        c.viewFilter = p.getProperty("viewFilter", c.viewFilter);
        c.valueFilter = p.getProperty("valueFilter", c.valueFilter);
        c.quietCount = clamp(num(p, "quietCount", c.quietCount), 0, 10000000);
        c.quietSpanMs = clamp(num(p, "quietSpanMs", c.quietSpanMs), 100, 3600000);
        c.stackDepth = clamp(num(p, "stackDepth", c.stackDepth), 1, 60);
        c.perKeyStacks = clamp(num(p, "perKeyStacks", c.perKeyStacks), 1, 50);
        c.sampleEvery = clamp(num(p, "sampleEvery", c.sampleEvery), 10, 100000);
        c.stacksPerSec = clamp(num(p, "stacksPerSec", c.stacksPerSec), 1, 5000);
        c.maxRecords = clamp(num(p, "maxRecords", c.maxRecords), 10, 20000);
        c.flushSec = clamp(num(p, "flushSec", c.flushSec), 1, 60);
        c.setText = bool(p, "setText", c.setText);
        c.setClear = bool(p, "setClear", c.setClear);
        c.drawTextView = bool(p, "drawTextView", c.drawTextView);
        c.drawCanvas = bool(p, "drawCanvas", c.drawCanvas);
        c.drawCompose = bool(p, "drawCompose", c.drawCompose);
        c.colorPaint = bool(p, "colorPaint", c.colorPaint);
        c.colorSet = bool(p, "colorSet", c.colorSet);
        c.iconImage = bool(p, "iconImage", c.iconImage);
        c.iconDesc = bool(p, "iconDesc", c.iconDesc);
        c.stateAlpha = bool(p, "stateAlpha", c.stateAlpha);
        c.stateVisible = bool(p, "stateVisible", c.stateVisible);
        c.stateSelected = bool(p, "stateSelected", c.stateSelected);
        c.tlClass = bool(p, "tlClass", c.tlClass);
        c.chainCoroutine = bool(p, "chainCoroutine", c.chainCoroutine);
        c.splitFiles = bool(p, "splitFiles", c.splitFiles);
        c.composeDumpEvery = clamp(num(p, "composeDumpEvery", c.composeDumpEvery), 1, 100000);
        c.composeDumpLines = clamp(num(p, "composeDumpLines", c.composeDumpLines), 5, 300);
        c.capTextView = clamp(num(p, "capTextView", c.capTextView), 10, 20000);
        c.capCanvas = clamp(num(p, "capCanvas", c.capCanvas), 10, 20000);
        c.capCompose = clamp(num(p, "capCompose", c.capCompose), 10, 20000);
        c.capClear = clamp(num(p, "capClear", c.capClear), 10, 20000);
        c.capIcon = clamp(num(p, "capIcon", c.capIcon), 10, 20000);
        c.capState = clamp(num(p, "capState", c.capState), 10, 20000);
        c.capTrace = clamp(num(p, "capTrace", c.capTrace), 10, 20000);
        c.depthSet = clamp(num(p, "depthSet", c.depthSet), 0, 60);
        c.depthDraw = clamp(num(p, "depthDraw", c.depthDraw), 0, 60);
        c.depthColor = clamp(num(p, "depthColor", c.depthColor), 0, 60);
        c.depthIcon = clamp(num(p, "depthIcon", c.depthIcon), 0, 60);
        c.depthState = clamp(num(p, "depthState", c.depthState), 0, 60);
        c.dumpPrefixes = p.getProperty("dumpPrefixes", c.dumpPrefixes);
        try {
            c.epoch = Long.parseLong(p.getProperty("epoch", "0").trim());
        } catch (Throwable ignored) {
        }
    }

    private static boolean bool(Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : Boolean.parseBoolean(v.trim());
    }

    private static int num(Properties p, String k, int d) {
        try {
            return Integer.parseInt(p.getProperty(k, "").trim());
        } catch (Throwable t) {
            return d;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
