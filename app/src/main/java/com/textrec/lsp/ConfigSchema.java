package com.textrec.lsp;

/**
 * 配置字段表（自动生成，不要手改）。
 * 来源：RecConfig.java（类型 / 默认值 / 范围）+ tools/field_meta.txt（分组 / 父开关 / 说明）。
 * 重新生成：python3 tools/gen_schema.py；CI 里用 --check 保证它和源码一致。
 */
final class ConfigSchema {
    private ConfigSchema() {}

    static final class F {
        final String key, type, def, group, parent, requires, title, desc, help;
        final boolean hasRange, parentInverse;
        final long min, max;
        final int level;

        F(String key, String type, String def, boolean hasRange, long min, long max, String group, int level, String parent, String requires, String title, String desc, String help) {
            this.key = key;
            this.type = type;
            this.def = def;
            this.hasRange = hasRange;
            this.min = min;
            this.max = max;
            this.group = group;
            this.level = level;
            this.parentInverse = parent.endsWith("!");
            this.parent = parent.endsWith("!") ? parent.substring(0, parent.length() - 1) : parent;
            this.requires = requires;
            this.title = title;
            this.desc = desc;
            this.help = help;
        }
    }

    /** 全部配置字段（按分组、常用程度排序）。type：bool / int / long / string。 */
    static final F[] ALL = {
            new F("enabled", "bool", "true", false, 0L, 0L, "target", 1, "", "", "启用记录", "总开关；false = 目标 App 暂停记录", "关闭后目标 App 暂停记录，不会再产生新的记录"),
            new F("traceSet", "bool", "true", false, 0L, 0L, "content", 1, "", "", "文字是谁设置的", "记录「文字是谁 setText 的」（总开关）", "记下每段文字由哪行代码 setText 出来（最常用）"),
            new F("traceDraw", "bool", "true", false, 0L, 0L, "content", 1, "", "", "文字是谁画的", "记录「文字是谁画的」（总开关）", "记下每段文字由哪行代码画到屏幕上；自绘和 Compose 的文字只能靠它"),
            new F("traceColor", "bool", "true", false, 0L, 0L, "content", 1, "", "", "文字颜色", "记录文字颜色：画笔颜色和 setTextColor 位置（总开关）", "记下画笔颜色，以及谁调用了 setTextColor"),
            new F("traceIcons", "bool", "false", false, 0L, 0L, "content", 1, "", "", "图标和按钮图片", "记录 ImageView 图片和 contentDescription（找图标用，记录量大）", "记下 ImageView 设置的图片和内容描述，用来找图标按钮；记录量会明显增加"),
            new F("autoParams", "bool", "true", false, 0L, 0L, "content", 2, "", "", "Compose 文字参数", "自动观察 Compose 文字绘制参数（颜色、描边 / 填充）", "自动抓取 Compose 文字的颜色、描边 / 填充（如弹幕的黑边 + 白字）"),
            new F("traceSetOnly", "bool", "false", false, 0L, 0L, "content", 2, "", "", "没显示出来的文字", "也记「只被 setText、从未绘制」的文字（拖动才出现的文字）", "也记下只被设置、从未画出的文字，如拖动进度条才出现的时长"),
            new F("traceState", "bool", "false", false, 0L, 0L, "content", 2, "", "", "状态变化", "记录 alpha / visibility / selected 的变化（总开关）", "记下透明度 / 可见性 / 选中态的变化，研究“选中”效果怎么实现的。默认只记已经记过文字或图标的控件；在「采集过滤」里填了「控件包含」，匹配的任何控件都记"),
            new F("maxSetOnly", "int", "500", true, 10L, 5000L, "content", 3, "traceSetOnly", "", "“没显示出来的文字”上限", "「没显示出来的文字」记录上限", "这类记录单独计数。默认 500"),
            new F("setText", "bool", "true", false, 0L, 0L, "content", 3, "traceSet", "", "", "子项：setText / append / setHint", ""),
            new F("setClear", "bool", "true", false, 0L, 0L, "content", 3, "traceSet", "", "", "子项：文字被清空", ""),
            new F("drawTextView", "bool", "true", false, 0L, 0L, "content", 3, "traceDraw", "", "", "子项：TextView 的绘制", ""),
            new F("drawCanvas", "bool", "true", false, 0L, 0L, "content", 3, "traceDraw", "", "", "子项：自绘（非 Compose）的绘制", ""),
            new F("drawCompose", "bool", "true", false, 0L, 0L, "content", 3, "traceDraw", "", "", "子项：Compose 的绘制", ""),
            new F("colorPaint", "bool", "true", false, 0L, 0L, "content", 3, "traceColor", "", "", "子项：画笔颜色", ""),
            new F("colorSet", "bool", "true", false, 0L, 0L, "content", 3, "traceColor", "", "", "子项：setTextColor 设置位置", ""),
            new F("iconImage", "bool", "true", false, 0L, 0L, "content", 3, "traceIcons", "", "", "子项：ImageView 设置图片", ""),
            new F("iconDesc", "bool", "true", false, 0L, 0L, "content", 3, "traceIcons", "", "", "子项：setContentDescription", ""),
            new F("stateAlpha", "bool", "true", false, 0L, 0L, "content", 3, "traceState", "", "", "子项：透明度", ""),
            new F("stateVisible", "bool", "true", false, 0L, 0L, "content", 3, "traceState", "", "", "子项：可见性", ""),
            new F("stateSelected", "bool", "true", false, 0L, 0L, "content", 3, "traceState", "", "", "子项：选中态", ""),
            new F("keywordMode", "bool", "false", false, 0L, 0L, "range", 1, "", "", "", "true = 只记文字命中 keywords 的位置；false = 记全部文字（会拖慢目标 App）", ""),
            new F("keywords", "string", "", false, 0L, 0L, "range", 1, "keywordMode", "", "", "关键字列表；普通模式逗号或换行分隔，正则模式只能换行分隔", ""),
            new F("regex", "bool", "false", false, 0L, 0L, "range", 2, "keywordMode", "", "", "keywords 按正则匹配（只在 keywordMode=true 时有效）", ""),
            new F("traceMethods", "string", "", false, 0L, 0L, "trace", 1, "", "", "", "方法追踪清单；每行一个「类全名#方法名」，行尾可加 @选项", ""),
            new F("traceDump", "bool", "false", false, 0L, 0L, "trace", 2, "", "traceMethods", "记录对象字段值", "方法追踪时定期记录 this 周围的字段值", "方法追踪时，每隔几次调用把被追踪对象（this）周围的字段值记一份：找进度、总时长存在哪时用，用完关掉（只对方法追踪生效）"),
            new F("treeOn", "bool", "true", false, 0L, 0L, "trace", 2, "", "", "展开数据树", "方法追踪命中时把 this / 参数 / 返回值展开成数据树", "默认开。导出里有「数据树」「值差异」两节：树上每个节点在每个任务里的值，值随任务不同的节点（✦）往往就是判断状态的字段"),
            new F("watch", "string", "", false, 0L, 0L, "trace", 2, "", "", "", "观察点清单；每行「类#方法:路径」，方法必须同时写在 traceMethods 里", ""),
            new F("traceExclusive", "bool", "true", false, 0L, 0L, "trace", 2, "", "", "方法追踪时只记方法追踪", "填了方法追踪时，自动把文字 / 图标 / 颜色 / 状态记录当成关闭", "默认开：填了要追踪的方法时，文字 / 图标 / 颜色 / 状态 / 绘制这些记录开关在目标 App 里自动当成关闭（界面上的开关不会被改，方法清单清空后恢复），记录里只剩方法追踪，更干净。想一起记就关掉它"),
            new F("traceDumpEvery", "int", "25", true, 1L, 1000L, "trace", 3, "traceDump", "", "", "字段快照：每隔几次调用记一份", ""),
            new F("traceDumpLines", "int", "40", true, 10L, 2000L, "trace", 3, "traceDump", "", "", "字段快照：一份最多多少行", ""),
            new F("traceDumpDepth", "int", "3", true, 0L, 6L, "trace", 3, "traceDump", "", "", "字段快照：往子对象里展开几层", ""),
            new F("annotFrames", "bool", "true", false, 0L, 0L, "trace", 3, "", "", "调用栈每一帧标分层 / 角色 / 稳定度", "调用栈每一帧标分层 / 角色 / 稳定度", "默认开。每帧后面标〔业务·ViewHolder·稳〕这样的标签，并标出 ★入口帧、◎推荐钩点、↯异步边界"),
            new F("treeDepth", "int", "4", true, 1L, 8L, "trace", 3, "treeOn", "", "", "数据树往下展开几层", ""),
            new F("treeNodes", "int", "200", true, 20L, 600L, "trace", 3, "treeOn", "", "", "数据树一次最多多少节点", ""),
            new F("treeEvery", "int", "10", true, 1L, 100000L, "trace", 3, "treeOn", "", "", "数据树每隔几次命中展开一次", ""),
            new F("treeViews", "bool", "false", false, 0L, 0L, "trace", 3, "treeOn", "", "数据树也展开控件的字段", "数据树也展开控件（View）自己的业务字段", "默认关。打开后展开控件自己的业务字段，字段多，建议把 treeNodes 调到 600"),
            new F("watchAuto", "bool", "true", false, 0L, 0L, "trace", 3, "treeOn", "", "自动给「值变过的叶子」加观察点", "自动把值变过的叶子加成观察点", "默认开，最多 24 个；导出「观察点变化」里写出变化发生在哪个方法执行期间"),
            new F("annotOwners", "bool", "true", false, 0L, 0L, "trace", 3, "", "", "方法追踪命中时反查「谁持有这个对象」", "方法追踪命中时反查「谁持有这个对象」", "默认开。从当前页面的 Activity / Fragment / ViewModel / 视图树里反向找出持有它的容器，在后台线程做，有时间上限"),
            new F("annotSweep", "bool", "true", false, 0L, 0L, "trace", 3, "", "", "任务开始 / 结束 / 点标记时扫「容器 → 数据实体」", "任务开始 / 结束时扫「容器 → 数据实体」", "默认开。导出里有「对象归属」一节，不需要追踪任何方法就能知道 Aweme 住在哪个容器里"),
            new F("annotBudgetMs", "int", "40", true, 5L, 500L, "trace", 3, "annotOwners", "", "", "一次反查最多多少毫秒", ""),
            new F("annotPerKey", "int", "6", true, 1L, 100L, "trace", 3, "annotOwners", "", "", "每个追踪方法前几次命中做反查", ""),
            new F("traceStackDepth", "int", "30", true, 1L, 80L, "trace", 3, "", "traceMethods", "方法追踪栈层数", "方法追踪保存的调用栈层数", "方法追踪的调用栈保存多少层。默认 30"),
            new F("windowMs", "int", "0", true, 0L, 600000L, "filter", 2, "", "traceMethods", "触发窗口（毫秒）", "触发窗口：最近一次触摸后多少毫秒内才记；0 = 不限制", "只记最近一次触摸之后这段时间内发生的调用；0 = 不限。常用 1500 / 3000 / 5000。适合“做一次操作看一组记录”，后台刷新基本都被挡掉。方法追踪默认受它管"),
            new F("excludeList", "string", "", false, 0L, 0L, "filter", 2, "", "traceMethods", "额外排除", "方法追踪排除名单；每行 类#方法 / 类#* / #方法 / 类名或包名前缀", "只对方法追踪生效，每行一项：类#方法、类#*（整个类）、#方法（任何类里的这个方法）、或类名 / 包名前缀"),
            new F("valueFilter", "string", "", false, 0L, 0L, "filter", 2, "", "traceMethods", "额外值匹配", "值匹配；参数或返回值包含其中任意一个值才记", "只对方法追踪生效：参数或返回值里包含其中任意一个值才记。时长会自动展开，填 01:37 会同时匹配 97000"),
            new F("excludeInfra", "bool", "false", false, 0L, 0L, "filter", 2, "", "traceMethods", "排除通用基础设施", "方法追踪排除 LiveData、Fragment 生命周期等通用基础设施", "不记 LiveData、Fragment 生命周期、字节的异步包装等几乎所有功能都会经过的类（只对方法追踪生效）"),
            new F("threadMode", "int", "0", true, 0L, 2L, "filter", 2, "", "", "", "线程：0 全部，1 只主线程，2 只后台线程", ""),
            new F("valueClock", "bool", "false", false, 0L, 0L, "filter", 2, "", "traceMethods", "只记带时间的调用", "方法追踪只记参数或返回值里带 00:00 这类时间的调用", "方法追踪只记参数或返回值里带 00:00 这类时间的调用，找视频时长用"),
            new F("quietCount", "int", "0", true, 0L, 10000000L, "filter", 2, "", "traceMethods", "压低高频方法（次数）", "压低高频方法：同一位置超过这个次数且持续一段时间后只计数；0 = 关", "同一位置调用超过这个次数且持续一段时间后只计数、不再抓栈，进度条刷新之类就不会淹没记录；0 = 关，常用 50（只对方法追踪生效）"),
            new F("windowAll", "bool", "false", false, 0L, 0L, "filter", 3, "windowMs", "", "触发窗口也用于文字 / 图标 / 状态", "触发窗口也管文字 / 图标 / 状态 / 绘制记录（默认只管方法追踪）", "默认只管方法追踪；打开后文字、颜色、图标、状态变化、绘制记录也只在触摸后的窗口内记"),
            new F("threadFilter", "string", "", false, 0L, 0L, "filter", 3, "", "", "线程名包含", "线程名白名单；线程名包含其中任意一个词才记", "线程名包含其中任意一个词才记，逗号或换行分隔，如 LegoHandler"),
            new F("pageFilter", "string", "", false, 0L, 0L, "filter", 3, "", "", "页面包含", "页面白名单；页面标签包含其中任意一个词才记", "页面标签（如 MainActivity > FeedItemFragment）包含其中任意一个词才记，不区分大小写"),
            new F("viewFilter", "string", "", false, 0L, 0L, "filter", 3, "", "", "控件包含", "控件白名单；View 类名或 id 包含其中任意一个词才记", "只对文字 / 图标 / 状态 / 绘制记录生效：View 类名或 id 包含其中任意一个词才记。自绘和 Compose 没有 View，填了就不记"),
            new F("quietSpanMs", "int", "3000", true, 100L, 3600000L, "filter", 3, "quietCount", "", "", "压低高频方法的持续时间（毫秒）", ""),
            new F("viewDump", "bool", "false", false, 0L, 0L, "view", 2, "", "", "任务开始 / 结束 / 点标记时导出视图树", "任务开始 / 结束 / 点标记时导出视图树", "弄清一块界面里各个控件的父子关系和布局类型。默认关，用完关掉"),
            new F("viewTap", "bool", "false", false, 0L, 0L, "view", 2, "", "", "点哪查哪", "点哪查哪：点屏幕时列出这个点上所有控件", "默认关。开了以后，每次点一下屏幕，把这个点上所有控件列出来：类名、id、可见性、坐标、文字，从外层排到最上层"),
            new F("viewDumpKey", "string", "ScrollMentionTextView,MentionTextView", false, 0L, 0L, "view", 3, "viewDump", "", "以哪个控件为准", "视图树以哪个控件为准（类名或 id 含的词，逗号分隔）", "屏幕上类名或 id 含这些词（逗号分隔）的控件当作「主角」，取可见面积最大的一个。默认是抖音视频文案的两个类"),
            new F("viewDumpUp", "int", "5", true, 0L, 12L, "view", 3, "viewDump", "", "", "从主角控件往上几层当容器", ""),
            new F("viewDumpDepth", "int", "5", true, 1L, 10L, "view", 3, "viewDump", "", "", "容器往下列几层", ""),
            new F("viewDumpLines", "int", "400", true, 50L, 3000L, "view", 3, "viewDump", "", "", "视图树一份最多多少行", ""),
            new F("viewDumpSet", "bool", "true", false, 0L, 0L, "view", 3, "viewDump", "", "", "视图树里的文字控件带「设置自」", ""),
            new F("tlOn", "bool", "true", false, 0L, 0L, "timeline", 2, "", "", "时间线（总开关）", "时间线总开关", "把用户操作、页面切换、各位置的记录样本按毫秒排成一条时间线；开销很小，默认开"),
            new F("recOutside", "bool", "false", false, 0L, 0L, "timeline", 2, "", "", "任务外也记录", "任务外也记录（单独一桶）；不写 = 只记任务内", "默认关：用任务浮窗录的时候，只有「任务开始 → 任务结束」之间才建记录。打开后任务外也记，但单独一桶存"),
            new F("tlOps", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "记用户操作", "时间线：记用户操作", "点击、滑动、返回键，带坐标和点中的控件"),
            new F("tlSamples", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "记各位置的样本", "时间线：记各位置的样本", "每个位置前 5 次、之后每 100 次进一次时间线"),
            new F("tlTouch", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOps", "", "", "时间线：触摸", ""),
            new F("tlKey", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOps", "", "", "时间线：按键", ""),
            new F("tlPage", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "", "时间线：页面切换", ""),
            new F("tlViewTree", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "", "时间线：控件增删", ""),
            new F("smpText", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "", "样本：setText / 清空", ""),
            new F("smpState", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "", "样本：状态变化", ""),
            new F("smpIcon", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "", "样本：图标", ""),
            new F("smpTrace", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "", "样本：方法追踪调用", ""),
            new F("tlClass", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "", "时间线：钩点可用", ""),
            new F("chainOn", "bool", "false", false, 0L, 0L, "chain", 2, "", "traceMethods", "链路追踪", "链路追踪总开关（会拖慢目标 App，用完关掉）", "以方法追踪里的方法为落脚点，记下它的上游（谁调用它、谁异步投递）和下游（它执行期间调用了哪些方法、改了哪些界面）。会拖慢目标 App，用完关掉"),
            new F("chainCoroutine", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "", "链路上游：协程调度", ""),
            new F("chainAsync", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "上游：异步来源", "链路上游：异步来源（Handler / 线程池）", "钩 Handler 消息队列和线程池，记下异步任务是谁投递的"),
            new F("chainEffects", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "下游：界面效果", "链路下游：界面效果", "锚点执行期间发生的 setText / 清空 / 图标 / 状态变化"),
            new F("chainDown", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "下游：被调用的方法", "链路下游：被调用的方法", "锚点所在的类（最多 8 个类、300 个方法）会被临时钩住，记下锚点执行期间它们被调用的先后和嵌套"),
            new F("chainEvery", "int", "20", true, 1L, 100000L, "chain", 3, "chainOn", "", "", "链路采样间隔", ""),
            new F("chainMaxDown", "int", "120", true, 10L, 1000L, "chain", 3, "chainOn", "", "", "链里最多列多少个被调用的方法", ""),
            new F("traceCompose", "bool", "true", false, 0L, 0L, "compose", 3, "", "", "Compose 文字绘制", "观察 Compose 的 paint 参数", "钩 MultiParagraph.paint*（颜色 / 描边 / 填充），Compose 才有"),
            new F("composeObj", "bool", "true", false, 0L, 0L, "compose", 3, "traceCompose", "", "Compose：记对象身份", "Compose 记录带 MultiParagraph 对象身份", "每条 Compose 记录记下 MultiParagraph 对象的身份哈希（看是同一个对象反复画还是每次新建）"),
            new F("composeDump", "bool", "true", false, 0L, 0L, "compose", 3, "traceCompose", "", "Compose：展开对象字段", "Compose 记录展开对象字段", "每条 Compose 记录展开一次 MultiParagraph 的字段（含文字）"),
            new F("composeDumpEvery", "int", "50", true, 1L, 100000L, "compose", 3, "composeDump", "", "", "Compose 字段展开的间隔", ""),
            new F("composeDumpLines", "int", "60", true, 5L, 300L, "compose", 3, "composeDump", "", "", "Compose 字段展开一份最多多少行", ""),
            new F("dumpPrefixes", "string", "androidx.compose.ui.text.", false, 0L, 0L, "compose", 3, "composeDump", "", "额外放行的类", "字段展开额外放行的包 / 类名前缀", "字段展开默认只进 App 自己的类。这里的包名 / 类名前缀也会进，逗号或换行分隔"),
            new F("capTextView", "int", "800", true, 10L, 20000L, "limits", 3, "", "", "", "TextView 文字记录上限", ""),
            new F("capCanvas", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "", "自绘文字记录上限", ""),
            new F("capCompose", "int", "400", true, 10L, 20000L, "limits", 3, "", "", "", "Compose 文字记录上限", ""),
            new F("capClear", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "", "文字清空记录上限", ""),
            new F("capIcon", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "", "图标记录上限", ""),
            new F("capState", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "", "状态变化记录上限", ""),
            new F("capTrace", "int", "600", true, 10L, 20000L, "limits", 3, "", "", "", "方法追踪记录上限", ""),
            new F("depthSet", "int", "0", true, 0L, 60L, "limits", 3, "", "", "", "设置栈深度；0 = 用 stackDepth", ""),
            new F("depthDraw", "int", "0", true, 0L, 60L, "limits", 3, "", "", "", "绘制栈深度；0 = 用 stackDepth", ""),
            new F("depthColor", "int", "0", true, 0L, 60L, "limits", 3, "", "", "", "颜色设置栈深度；0 = 用 stackDepth", ""),
            new F("depthIcon", "int", "0", true, 0L, 60L, "limits", 3, "", "", "", "图标设置栈深度；0 = 用 stackDepth", ""),
            new F("depthState", "int", "0", true, 0L, 60L, "limits", 3, "", "", "", "状态变化栈深度；0 = 用 stackDepth", ""),
            new F("splitFiles", "bool", "true", false, 0L, 0L, "limits", 3, "", "", "分文件存", "分文件存；关掉 = 一个文件 + 总上限 maxRecords", "每种记录一个快照文件、时间线一个文件，各自独立、各自有上限；关掉 = 一个文件 + 总上限"),
            new F("stackDepth", "int", "12", true, 1L, 60L, "limits", 3, "", "", "调用栈保存层数", "每条记录保存的调用栈层数", "每条记录保存多少层调用栈。越大越完整，文件也越大。默认 12"),
            new F("perKeyStacks", "int", "3", true, 1L, 50L, "limits", 3, "", "", "同一位置先抓几次栈", "同一位置前几次抓栈", "同一处代码前几次完整记录调用栈，之后只计数。默认 3"),
            new F("sampleEvery", "int", "200", true, 10L, 100000L, "limits", 3, "", "", "之后每隔多少次再抽样", "之后每隔多少次抽样抓一次", "用来发现同一控件的其他调用路径。默认 200"),
            new F("stacksPerSec", "int", "1000", true, 1L, 5000L, "limits", 3, "", "", "每秒最多抓栈次数", "全局每秒最多抓栈次数", "防止拖垮目标 App 的总闸。冷启动文字很多，设太小会丢文字。默认 1000"),
            new F("maxRecords", "int", "2000", true, 10L, 20000L, "limits", 3, "splitFiles!", "", "最多保存记录数", "记录总上限（只在 splitFiles=false 时有效）", "超过后新记录会被丢弃（分文件存关掉时才用）。默认 2000"),
            new F("flushSec", "int", "3", true, 1L, 60L, "limits", 3, "", "", "", "落盘节流（秒）", ""),
            new F("traceOnly", "bool", "false", false, 0L, 0L, "internal", 3, "", "", "", "已废弃，写了也忽略", ""),
            new F("maxPerKind", "int", "500", true, 20L, 20000L, "internal", 3, "", "", "", "已废弃的每类上限，只给没有单独上限的种类用", ""),
            new F("epoch", "long", "0", false, 0L, 0L, "internal", 3, "", "", "", "变了就清空已有记录；一般不手写", ""),
    };

    static F get(String key) {
        for (F f : ALL) if (f.key.equals(key)) return f;
        return null;
    }
}
