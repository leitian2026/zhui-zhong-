package com.textrec.lsp;

/**
 * 配置字段表（自动生成，不要手改）。
 * 来源：RecConfig.java（类型 / 默认值 / 范围）+ tools/field_meta.txt（分组 / 父开关 / 说明）。
 * 重新生成：python3 tools/gen_schema.py；CI 里用 --check 保证它和源码一致。
 */
final class ConfigSchema {
    private ConfigSchema() {}

    static final class F {
        final String key, type, def, group, parent, requires, desc;
        final boolean hasRange, parentInverse;
        final long min, max;
        final int level;

        F(String key, String type, String def, boolean hasRange, long min, long max, String group, int level, String parent, String requires, String desc) {
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
            this.desc = desc;
        }
    }

    /** 全部配置字段（按分组、常用程度排序）。type：bool / int / long / string。 */
    static final F[] ALL = {
            new F("enabled", "bool", "true", false, 0L, 0L, "target", 1, "", "", "总开关；false = 目标 App 暂停记录"),
            new F("traceSet", "bool", "true", false, 0L, 0L, "content", 1, "", "", "记录「文字是谁 setText 的」（总开关）"),
            new F("traceDraw", "bool", "true", false, 0L, 0L, "content", 1, "", "", "记录「文字是谁画的」（总开关）"),
            new F("traceColor", "bool", "true", false, 0L, 0L, "content", 1, "", "", "记录文字颜色：画笔颜色和 setTextColor 位置（总开关）"),
            new F("traceIcons", "bool", "false", false, 0L, 0L, "content", 1, "", "", "记录 ImageView 图片和 contentDescription（找图标用，记录量大）"),
            new F("autoParams", "bool", "true", false, 0L, 0L, "content", 2, "", "", "自动观察 Compose 文字绘制参数（颜色、描边 / 填充）"),
            new F("traceSetOnly", "bool", "false", false, 0L, 0L, "content", 2, "", "", "也记「只被 setText、从未绘制」的文字（拖动才出现的文字）"),
            new F("traceState", "bool", "false", false, 0L, 0L, "content", 2, "", "", "记录 alpha / visibility / selected 的变化（总开关）"),
            new F("maxSetOnly", "int", "500", true, 10L, 5000L, "content", 3, "traceSetOnly", "", "「没显示出来的文字」记录上限"),
            new F("setText", "bool", "true", false, 0L, 0L, "content", 3, "traceSet", "", "子项：setText / append / setHint"),
            new F("setClear", "bool", "true", false, 0L, 0L, "content", 3, "traceSet", "", "子项：文字被清空"),
            new F("drawTextView", "bool", "true", false, 0L, 0L, "content", 3, "traceDraw", "", "子项：TextView 的绘制"),
            new F("drawCanvas", "bool", "true", false, 0L, 0L, "content", 3, "traceDraw", "", "子项：自绘（非 Compose）的绘制"),
            new F("drawCompose", "bool", "true", false, 0L, 0L, "content", 3, "traceDraw", "", "子项：Compose 的绘制"),
            new F("colorPaint", "bool", "true", false, 0L, 0L, "content", 3, "traceColor", "", "子项：画笔颜色"),
            new F("colorSet", "bool", "true", false, 0L, 0L, "content", 3, "traceColor", "", "子项：setTextColor 设置位置"),
            new F("iconImage", "bool", "true", false, 0L, 0L, "content", 3, "traceIcons", "", "子项：ImageView 设置图片"),
            new F("iconDesc", "bool", "true", false, 0L, 0L, "content", 3, "traceIcons", "", "子项：setContentDescription"),
            new F("stateAlpha", "bool", "true", false, 0L, 0L, "content", 3, "traceState", "", "子项：透明度"),
            new F("stateVisible", "bool", "true", false, 0L, 0L, "content", 3, "traceState", "", "子项：可见性"),
            new F("stateSelected", "bool", "true", false, 0L, 0L, "content", 3, "traceState", "", "子项：选中态"),
            new F("keywordMode", "bool", "false", false, 0L, 0L, "range", 1, "", "", "true = 只记文字命中 keywords 的位置；false = 记全部文字（会拖慢目标 App）"),
            new F("keywords", "string", "", false, 0L, 0L, "range", 1, "keywordMode", "", "关键字列表；普通模式逗号或换行分隔，正则模式只能换行分隔"),
            new F("regex", "bool", "false", false, 0L, 0L, "range", 2, "keywordMode", "", "keywords 按正则匹配（只在 keywordMode=true 时有效）"),
            new F("traceMethods", "string", "", false, 0L, 0L, "trace", 1, "", "", "方法追踪清单；每行一个「类全名#方法名」，行尾可加 @选项"),
            new F("traceDump", "bool", "false", false, 0L, 0L, "trace", 2, "", "traceMethods", "方法追踪时定期记录 this 周围的字段值"),
            new F("treeOn", "bool", "true", false, 0L, 0L, "trace", 2, "", "", "方法追踪命中时把 this / 参数 / 返回值展开成数据树"),
            new F("watch", "string", "", false, 0L, 0L, "trace", 2, "", "", "观察点清单；每行「类#方法:路径」，方法必须同时写在 traceMethods 里"),
            new F("traceExclusive", "bool", "true", false, 0L, 0L, "trace", 2, "", "", "填了方法追踪时，自动把文字 / 图标 / 颜色 / 状态记录当成关闭"),
            new F("traceDumpEvery", "int", "25", true, 1L, 1000L, "trace", 3, "traceDump", "", "字段快照：每隔几次调用记一份"),
            new F("traceDumpLines", "int", "40", true, 10L, 2000L, "trace", 3, "traceDump", "", "字段快照：一份最多多少行"),
            new F("traceDumpDepth", "int", "3", true, 0L, 6L, "trace", 3, "traceDump", "", "字段快照：往子对象里展开几层"),
            new F("annotFrames", "bool", "true", false, 0L, 0L, "trace", 3, "", "", "调用栈每一帧标分层 / 角色 / 稳定度"),
            new F("treeDepth", "int", "4", true, 1L, 8L, "trace", 3, "treeOn", "", "数据树往下展开几层"),
            new F("treeNodes", "int", "200", true, 20L, 600L, "trace", 3, "treeOn", "", "数据树一次最多多少节点"),
            new F("treeEvery", "int", "10", true, 1L, 100000L, "trace", 3, "treeOn", "", "数据树每隔几次命中展开一次"),
            new F("treeViews", "bool", "false", false, 0L, 0L, "trace", 3, "treeOn", "", "数据树也展开控件（View）自己的业务字段"),
            new F("watchAuto", "bool", "true", false, 0L, 0L, "trace", 3, "treeOn", "", "自动把值变过的叶子加成观察点"),
            new F("annotOwners", "bool", "true", false, 0L, 0L, "trace", 3, "", "", "方法追踪命中时反查「谁持有这个对象」"),
            new F("annotSweep", "bool", "true", false, 0L, 0L, "trace", 3, "", "", "任务开始 / 结束时扫「容器 → 数据实体」"),
            new F("annotBudgetMs", "int", "40", true, 5L, 500L, "trace", 3, "annotOwners", "", "一次反查最多多少毫秒"),
            new F("annotPerKey", "int", "6", true, 1L, 100L, "trace", 3, "annotOwners", "", "每个追踪方法前几次命中做反查"),
            new F("traceStackDepth", "int", "30", true, 1L, 80L, "trace", 3, "", "traceMethods", "方法追踪保存的调用栈层数"),
            new F("windowMs", "int", "0", true, 0L, 600000L, "filter", 2, "", "traceMethods", "触发窗口：最近一次触摸后多少毫秒内才记；0 = 不限制"),
            new F("excludeList", "string", "", false, 0L, 0L, "filter", 2, "", "traceMethods", "方法追踪排除名单；每行 类#方法 / 类#* / #方法 / 类名或包名前缀"),
            new F("valueFilter", "string", "", false, 0L, 0L, "filter", 2, "", "traceMethods", "值匹配；参数或返回值包含其中任意一个值才记"),
            new F("excludeInfra", "bool", "false", false, 0L, 0L, "filter", 2, "", "traceMethods", "方法追踪排除 LiveData、Fragment 生命周期等通用基础设施"),
            new F("threadMode", "int", "0", true, 0L, 2L, "filter", 2, "", "", "线程：0 全部，1 只主线程，2 只后台线程"),
            new F("valueClock", "bool", "false", false, 0L, 0L, "filter", 2, "", "traceMethods", "方法追踪只记参数或返回值里带 00:00 这类时间的调用"),
            new F("quietCount", "int", "0", true, 0L, 10000000L, "filter", 2, "", "traceMethods", "压低高频方法：同一位置超过这个次数且持续一段时间后只计数；0 = 关"),
            new F("windowAll", "bool", "false", false, 0L, 0L, "filter", 3, "windowMs", "", "触发窗口也管文字 / 图标 / 状态 / 绘制记录（默认只管方法追踪）"),
            new F("threadFilter", "string", "", false, 0L, 0L, "filter", 3, "", "", "线程名白名单；线程名包含其中任意一个词才记"),
            new F("pageFilter", "string", "", false, 0L, 0L, "filter", 3, "", "", "页面白名单；页面标签包含其中任意一个词才记"),
            new F("viewFilter", "string", "", false, 0L, 0L, "filter", 3, "", "", "控件白名单；View 类名或 id 包含其中任意一个词才记"),
            new F("quietSpanMs", "int", "3000", true, 100L, 3600000L, "filter", 3, "quietCount", "", "压低高频方法的持续时间（毫秒）"),
            new F("viewDump", "bool", "false", false, 0L, 0L, "view", 2, "", "", "任务开始 / 结束 / 点标记时导出视图树"),
            new F("viewTap", "bool", "false", false, 0L, 0L, "view", 2, "", "", "点哪查哪：点屏幕时列出这个点上所有控件"),
            new F("viewDumpKey", "string", "ScrollMentionTextView,MentionTextView", false, 0L, 0L, "view", 3, "viewDump", "", "视图树以哪个控件为准（类名或 id 含的词，逗号分隔）"),
            new F("viewDumpUp", "int", "5", true, 0L, 12L, "view", 3, "viewDump", "", "从主角控件往上几层当容器"),
            new F("viewDumpDepth", "int", "5", true, 1L, 10L, "view", 3, "viewDump", "", "容器往下列几层"),
            new F("viewDumpLines", "int", "400", true, 50L, 3000L, "view", 3, "viewDump", "", "视图树一份最多多少行"),
            new F("viewDumpSet", "bool", "true", false, 0L, 0L, "view", 3, "viewDump", "", "视图树里的文字控件带「设置自」"),
            new F("tlOn", "bool", "true", false, 0L, 0L, "timeline", 2, "", "", "时间线总开关"),
            new F("recOutside", "bool", "false", false, 0L, 0L, "timeline", 2, "", "", "任务外也记录（单独一桶）；不写 = 只记任务内"),
            new F("tlOps", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "时间线：记用户操作"),
            new F("tlSamples", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "时间线：记各位置的样本"),
            new F("tlTouch", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOps", "", "时间线：触摸"),
            new F("tlKey", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOps", "", "时间线：按键"),
            new F("tlPage", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "时间线：页面切换"),
            new F("tlViewTree", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "时间线：控件增删"),
            new F("smpText", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "样本：setText / 清空"),
            new F("smpState", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "样本：状态变化"),
            new F("smpIcon", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "样本：图标"),
            new F("smpTrace", "bool", "true", false, 0L, 0L, "timeline", 3, "tlSamples", "", "样本：方法追踪调用"),
            new F("tlClass", "bool", "true", false, 0L, 0L, "timeline", 3, "tlOn", "", "时间线：钩点可用"),
            new F("chainOn", "bool", "false", false, 0L, 0L, "chain", 2, "", "traceMethods", "链路追踪总开关（会拖慢目标 App，用完关掉）"),
            new F("chainCoroutine", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "链路上游：协程调度"),
            new F("chainAsync", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "链路上游：异步来源（Handler / 线程池）"),
            new F("chainEffects", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "链路下游：界面效果"),
            new F("chainDown", "bool", "true", false, 0L, 0L, "chain", 3, "chainOn", "", "链路下游：被调用的方法"),
            new F("chainEvery", "int", "20", true, 1L, 100000L, "chain", 3, "chainOn", "", "链路采样间隔"),
            new F("chainMaxDown", "int", "120", true, 10L, 1000L, "chain", 3, "chainOn", "", "链里最多列多少个被调用的方法"),
            new F("traceCompose", "bool", "true", false, 0L, 0L, "compose", 3, "", "", "观察 Compose 的 paint 参数"),
            new F("composeObj", "bool", "true", false, 0L, 0L, "compose", 3, "traceCompose", "", "Compose 记录带 MultiParagraph 对象身份"),
            new F("composeDump", "bool", "true", false, 0L, 0L, "compose", 3, "traceCompose", "", "Compose 记录展开对象字段"),
            new F("composeDumpEvery", "int", "50", true, 1L, 100000L, "compose", 3, "composeDump", "", "Compose 字段展开的间隔"),
            new F("composeDumpLines", "int", "60", true, 5L, 300L, "compose", 3, "composeDump", "", "Compose 字段展开一份最多多少行"),
            new F("dumpPrefixes", "string", "androidx.compose.ui.text.", false, 0L, 0L, "compose", 3, "composeDump", "", "字段展开额外放行的包 / 类名前缀"),
            new F("capTextView", "int", "800", true, 10L, 20000L, "limits", 3, "", "", "TextView 文字记录上限"),
            new F("capCanvas", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "自绘文字记录上限"),
            new F("capCompose", "int", "400", true, 10L, 20000L, "limits", 3, "", "", "Compose 文字记录上限"),
            new F("capClear", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "文字清空记录上限"),
            new F("capIcon", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "图标记录上限"),
            new F("capState", "int", "300", true, 10L, 20000L, "limits", 3, "", "", "状态变化记录上限"),
            new F("capTrace", "int", "600", true, 10L, 20000L, "limits", 3, "", "", "方法追踪记录上限"),
            new F("depthSet", "int", "0", true, 0L, 60L, "limits", 3, "", "", "设置栈深度；0 = 用 stackDepth"),
            new F("depthDraw", "int", "0", true, 0L, 60L, "limits", 3, "", "", "绘制栈深度；0 = 用 stackDepth"),
            new F("depthColor", "int", "0", true, 0L, 60L, "limits", 3, "", "", "颜色设置栈深度；0 = 用 stackDepth"),
            new F("depthIcon", "int", "0", true, 0L, 60L, "limits", 3, "", "", "图标设置栈深度；0 = 用 stackDepth"),
            new F("depthState", "int", "0", true, 0L, 60L, "limits", 3, "", "", "状态变化栈深度；0 = 用 stackDepth"),
            new F("splitFiles", "bool", "true", false, 0L, 0L, "limits", 3, "", "", "分文件存；关掉 = 一个文件 + 总上限 maxRecords"),
            new F("stackDepth", "int", "12", true, 1L, 60L, "limits", 3, "", "", "每条记录保存的调用栈层数"),
            new F("perKeyStacks", "int", "3", true, 1L, 50L, "limits", 3, "", "", "同一位置前几次抓栈"),
            new F("sampleEvery", "int", "200", true, 10L, 100000L, "limits", 3, "", "", "之后每隔多少次抽样抓一次"),
            new F("stacksPerSec", "int", "1000", true, 1L, 5000L, "limits", 3, "", "", "全局每秒最多抓栈次数"),
            new F("maxRecords", "int", "2000", true, 10L, 20000L, "limits", 3, "splitFiles!", "", "记录总上限（只在 splitFiles=false 时有效）"),
            new F("flushSec", "int", "3", true, 1L, 60L, "limits", 3, "", "", "落盘节流（秒）"),
            new F("traceOnly", "bool", "false", false, 0L, 0L, "internal", 3, "", "", "已废弃，写了也忽略"),
            new F("maxPerKind", "int", "500", true, 20L, 20000L, "internal", 3, "", "", "已废弃的每类上限，只给没有单独上限的种类用"),
            new F("epoch", "long", "0", false, 0L, 0L, "internal", 3, "", "", "变了就清空已有记录；一般不手写"),
    };

    static F get(String key) {
        for (F f : ALL) if (f.key.equals(key)) return f;
        return null;
    }
}
