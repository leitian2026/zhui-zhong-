# TextRec 写配置手册（给 AI 读的，先读这份）

你的任务：根据用户想研究的东西，写出一整块**可直接粘贴**的 TextRec 配置（采集配置 + 任务清单）。
这份手册里的每个示例都经过机器校验：标 `textrec` 的示例没有任何错误和警告；标 `textrec-bad` 的示例是常见错误，下面都给了对应的正确写法。
需要了解某个功能的细节时，再读《完整参考》（`ai_guide.md`）；写配置只需要这一份。

## 1. 先做这三件事

1. **想清楚用户要找什么**，在第 2 节的预设表里选一个 `preset`。拿不准就问用户一个问题，不要猜。
2. **只写你需要的字段。** 配置有上百个字段，绝大多数不用碰：用 `preset` 套好一组，再只写和预设不同的几项。没写的字段用默认值。
3. **类名、方法名只能从用户给你的导出记录里逐字复制。** 不要凭印象写，不要自己编，不要抄本手册里的示例类名（`com.demo.*` 都是示例，真实 App 里没有）。用户还没有导出记录时，先给他一份只抓文字的配置（`preset = text` + `keywords`），让他抓完把导出发给你，再写方法追踪。

## 2. 预设（`preset = 名字`）

选一个预设，它会展开成右边那一组字段；你在预设下面显式写的字段会覆盖它。

<!-- GEN:presets -->

| 预设 | 用来找什么 | 展开成 |
|---|---|---|
| `text` | 文字是谁设置 / 谁画的 / 什么颜色 | `traceSet=true` `traceDraw=true` `traceColor=true` `autoParams=true` `traceIcons=false` `traceSetOnly=false` `traceState=false` `stackDepth=12` |
| `icons` | 图标 / 按钮图片是谁设的 | `traceSet=false` `traceDraw=false` `traceColor=false` `autoParams=false` `traceSetOnly=false` `traceState=false` `traceIcons=true` |
| `state` | 控件透明度 / 可见 / 选中是谁改的 | `traceSet=false` `traceDraw=false` `traceColor=false` `autoParams=false` `traceIcons=false` `traceSetOnly=false` `traceState=true` |
| `trace` | 某个方法被谁调用、参数和返回值 | `traceSet=false` `traceDraw=false` `traceColor=false` `autoParams=false` `traceIcons=false` `traceSetOnly=false` `traceState=false` |
| `statehook` | 找「判断状态」的钩子 / 谁改了某个字段 | `traceSet=false` `traceDraw=false` `traceColor=false` `autoParams=false` `traceIcons=false` `traceSetOnly=false` `traceState=false` `treeOn=true` `watchAuto=true` `annotOwners=true` `annotSweep=true` `annotFrames=true` `excludeInfra=true` `quietCount=50` `quietSpanMs=3000` |
| `viewtree` | 一块界面里有哪些控件、谁在谁里面 | `viewDump=true` `traceSet=true` `traceDraw=false` `traceColor=false` `autoParams=false` `traceIcons=false` `traceSetOnly=false` `traceState=false` |
| `tap` | 不知道控件叫什么，只知道它在屏幕哪里 | `viewTap=true` `traceSet=true` `traceDraw=false` `traceColor=false` `autoParams=false` `traceIcons=false` `traceSetOnly=false` `traceState=false` `traceExclusive=false` |
| `all` | 全部打开（最全，最卡） | `traceSet=true` `traceDraw=true` `traceColor=true` `autoParams=true` `traceIcons=true` `traceSetOnly=true` `traceState=true` `stackDepth=30` |
| `hook` | 找钩子（最窄） | `windowMs=1500` `windowAll=false` `excludeInfra=true` `threadMode=0` `valueClock=false` `quietCount=50` `quietSpanMs=3000` |
| `nofilter` | 关掉全部过滤 | `windowMs=0` `windowAll=false` `excludeInfra=false` `threadMode=0` `valueClock=false` `quietCount=0` `threadFilter=` `pageFilter=` `viewFilter=` `excludeList=` `valueFilter=` |

<!-- /GEN:presets -->

## 3. 写法（块格式，值里不要任何转义）

整块放在**一个代码块**里，按段写：

```textrec
[config]
preset = text
keywordMode = true

[keywords]
福利
红包

[tasks]
@基线|什么都不要操作，让视频正常播放|10
划到下一个视频|向上划一次，等新视频开始播放||
点进评论再返回|点评论图标，等评论出来，再按返回||
```

规则（一共 10 条，每条都要照做）：

1. **只能用这些段名**：`[config]` `[traceMethods]` `[keywords]` `[watch]` `[excludeList]` `[threadFilter]` `[pageFilter]` `[viewFilter]` `[valueFilter]` `[viewDumpKey]` `[dumpPrefixes]` `[tasks]`。段名单独占一行，后面不要写别的。第一个段名之前的行算 `[config]`。
2. `[config]` 里**一行一个** `key = 值`。只认英文等号，不认冒号。`#` 开头的整行是注释，**不能在值后面写行内注释**。
3. 开关只能写 `true` 或 `false`（小写英文）；数字只写纯数字，不带单位、不带小数、不带逗号。
4. **列表型的项各开一个段，一行一项**：`traceMethods`、`keywords`、`watch`、`excludeList`、`threadFilter`、`pageFilter`、`viewFilter`、`valueFilter`、`viewDumpKey`、`dumpPrefixes`。一个段只出现一次；不要用逗号把多项写在一行里。
5. 列表段里 `//` 开头的整行是注释（`[keywords]` 段没有注释语法）。注释不能跟在内容后面。
6. **不写 `\n`，不写行尾反斜杠，正则不要双反斜杠。** 这些都是旧的 Properties 写法，容易出错；块格式里写什么就是什么，`\d+` 就写 `\d+`。
7. `keywords` 写了，就必须同时写 `keywordMode = true`；关键字是正则时再写 `regex = true`，并且每行一个。
8. 方法追踪的每一行是 `类全名#方法名`，行尾可以加以 `@` 开头的选项（见第 5 节）；构造方法写 `<init>`；整个类写 `类全名#*`。
9. 子开关不能单独起作用：它的父开关关着时，写了也没用（见第 4 节字段表里的「父开关」）。
10. 配置项名只能用第 4 节字段表里有的，一个字母都不能错，区分大小写。

## 4. 常用字段表

没写的字段用默认值。不在表里的字段是专家项，需要时查《完整参考》第 8 章。

<!-- GEN:fields-common -->

**目标与总开关**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `enabled` | true/false | `true` |  | 总开关；false = 目标 App 暂停记录 |

**记录内容（文字 / 图标 / 状态）**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `traceSet` | true/false | `true` |  | 记录「文字是谁 setText 的」（总开关） |
| `traceDraw` | true/false | `true` |  | 记录「文字是谁画的」（总开关） |
| `traceColor` | true/false | `true` |  | 记录文字颜色：画笔颜色和 setTextColor 位置（总开关） |
| `traceIcons` | true/false | `false` |  | 记录 ImageView 图片和 contentDescription（找图标用，记录量大） |
| `autoParams` | true/false | `true` |  | 自动观察 Compose 文字绘制参数（颜色、描边 / 填充）（前提：`traceDraw` 开着） |
| `traceSetOnly` | true/false | `false` |  | 也记「只被 setText、从未绘制」的文字（拖动才出现的文字）（前提：`traceSet` 开着） |
| `traceState` | true/false | `false` |  | 记录 alpha / visibility / selected 的变化（总开关） |

**记录范围（关键字）**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `keywordMode` | true/false | `false` |  | true = 只记文字命中 keywords 的位置；false = 记全部文字（会拖慢目标 App） |
| `keywords` | 文字 | 空 |  | 关键字列表；普通模式逗号或换行分隔，正则模式只能换行分隔（前提：`keywordMode` 开着） |
| `regex` | true/false | `false` |  | keywords 按正则匹配（只在 keywordMode=true 时有效）（前提：`keywordMode` 开着） |

**方法追踪**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `traceMethods` | 文字 | 空 |  | 方法追踪清单；每行一个「类全名#方法名」，行尾可加 @选项 |
| `traceDump` | true/false | `false` |  | 方法追踪时定期记录 this 周围的字段值（需要写了 `traceMethods`） |
| `treeOn` | true/false | `true` |  | 方法追踪命中时把 this / 参数 / 返回值展开成数据树 |
| `watch` | 文字 | 空 |  | 观察点清单；每行「类#方法:路径」，方法必须同时写在 traceMethods 里 |
| `traceExclusive` | true/false | `true` |  | 填了方法追踪时，自动把文字 / 图标 / 颜色 / 状态记录当成关闭 |

**采集过滤**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `windowMs` | 整数 | `0` | 0～600000 | 触发窗口：最近一次触摸后多少毫秒内才记；0 = 不限制（需要写了 `traceMethods`） |
| `excludeList` | 文字 | 空 |  | 方法追踪排除名单；每行 类#方法 / 类#* / #方法 / 类名或包名前缀（需要写了 `traceMethods`） |
| `valueFilter` | 文字 | 空 |  | 值匹配；参数或返回值包含其中任意一个值才记（需要写了 `traceMethods`） |
| `excludeInfra` | true/false | `false` |  | 方法追踪排除 LiveData、Fragment 生命周期等通用基础设施（需要写了 `traceMethods`） |
| `threadMode` | 整数 | `0` | 0～2 | 线程：0 全部，1 只主线程，2 只后台线程 |
| `valueClock` | true/false | `false` |  | 方法追踪只记参数或返回值里带 00:00 这类时间的调用（需要写了 `traceMethods`） |
| `quietCount` | 整数 | `0` | 0～10000000 | 压低高频方法：同一位置超过这个次数且持续一段时间后只计数；0 = 关（需要写了 `traceMethods`） |

**视图树与点哪查哪**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `viewDump` | true/false | `false` |  | 任务开始 / 结束 / 点标记时导出视图树 |
| `viewTap` | true/false | `false` |  | 点哪查哪：点屏幕时列出这个点上所有控件 |

**时间线与任务**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `tlOn` | true/false | `true` |  | 时间线总开关 |
| `recOutside` | true/false | `false` |  | 任务外也记录（单独一桶）；不写 = 只记任务内 |

**链路追踪**

| 字段 | 类型 | 默认 | 范围 | 说明 |
|---|---|---|---|---|
| `chainOn` | true/false | `false` |  | 链路追踪总开关（会拖慢目标 App，用完关掉）（需要写了 `traceMethods`） |

<!-- /GEN:fields-common -->

## 5. 方法追踪的行尾选项

选项跟在 `类#方法` 后面，**前面必须有空格**，多个选项用空格隔开：`com.demo.player.PlayerView#seekTo @args @stack=4`。

| 选项 | 含义 |
|---|---|
| `@count` | 只计数，不抓栈 |
| `@args` | 只记参数和返回值 |
| `@stack=N` | 这一条单独的栈层数 |
| `@depth=N` | 数据树展开层数 |
| `@every=N` | 这一条单独的抽样间隔 |
| `@tag=名字` | 给这一条起个标签 |
| `@seq` | 按先后顺序记每一次调用；**只在任务里记，必须同时写任务清单** |

写了 `traceMethods` 后，文字 / 图标 / 颜色 / 状态这些记录默认会被自动关掉（`traceExclusive = true`），记录里只有方法追踪。想一起记就写 `traceExclusive = false`。

## 6. 任务清单（`[tasks]` 段）

一行一件事，格式 `名称|提示|秒数|选项`（后三段可省）。

| 写法 | 含义 |
|---|---|
| `名称\|提示\|` | 普通任务：用户做完，浮窗自动判断结束 |
| `名称\|提示\|40` | 到点自动结束（适合「别操作，让它自己滚 40 秒」） |
| `@基线\|提示\|10` | 基线：什么都不操作，记背景噪声；放在第一件 |
| `名称\|提示\|\|空` | 对照任务：允许记到 0 条（比如「只单击一次，不点赞」） |
| `名称\|提示\|\|x3` | 同一件事连做 3 遍 |
| `// 注释` | 整行忽略 |

分隔符是**半角**竖线 `|`。提示要写成用户照着做的动作（「点一下视频暂停，停 3 秒」，不要写「测试暂停」）。要研究的操作有两个以上时，**必须写任务清单**；只有一个操作时，在回复里说明「这次只有一个操作，所以没写任务清单」。

## 7. 常见错误 → 正确写法

下面每组：先是错的，再是对的。

忘了 `keywordMode`（关键字不会被用，会记录全部文字，很卡）：

```textrec-bad
# expect: keywordMode
[keywords]
福利
```

```textrec
[config]
keywordMode = true

[keywords]
福利
```

预设名拼错（等于没写）：

```textrec-bad
# expect: text
[config]
preset = txt
```

```textrec
[config]
preset = text
```

多个方法写在一行（一行只能一个）：

```textrec-bad
# expect: 逗号
[traceMethods]
com.demo.player.PlayerView#seekTo, com.demo.player.PlayerView#pause
```

```textrec
[traceMethods]
com.demo.player.PlayerView#seekTo
com.demo.player.PlayerView#pause
```

行尾写了注释（会被当成方法名的一部分）：

```textrec-bad
# expect: 多余的文字
[traceMethods]
com.demo.player.PlayerView#seekTo // 拖动进度条
```

```textrec
[traceMethods]
// 拖动进度条
com.demo.player.PlayerView#seekTo
```

漏了 `#`（类和方法之间用 `#`，不是点）：

```textrec-bad
# expect: 缺少
[traceMethods]
com.demo.player.PlayerView.seekTo
```

段名拼错（整段内容被忽略）：

```textrec-bad
# expect: traceMethods
[traceMethod]
com.demo.player.PlayerView#seekTo
```

开关写成了别的词（只认 `true` / `false`，其他都当 `false`）：

```textrec-bad
# expect: true
[config]
traceSet = yes
```

数字带了单位：

```textrec-bad
# expect: 单位
[config]
flushSec = 3s
```

```textrec
[config]
flushSec = 3
```

任务清单里用了全角竖线：

```textrec-bad
# expect: 半角竖线
[tasks]
暂停｜点一下视频暂停｜
```

编了一个不存在的类名，或者抄了示例类名（检查器直接判错）：

```textrec-bad
# expect: 占位类名
[traceMethods]
com.a.B#foo
```

只追踪方法时又写了关键字（方法追踪不看关键字，写了没用；要找文字就去掉 `traceMethods`）：

```textrec-bad
# expect-warn: 只会记方法追踪
[config]
preset = trace
keywordMode = true

[keywords]
福利

[traceMethods]
com.demo.player.PlayerView#seekTo
```

追踪的方法同时被排除了（永远记不到）：

```textrec-bad
# expect-warn: 排除了
[traceMethods]
com.demo.player.PlayerView#seekTo

[excludeList]
com.demo.player.PlayerView#*
```

## 8. 三个完整示例

**例 1：找某段文字是谁设置 / 画的（最常用）**

```textrec
[config]
preset = text
keywordMode = true

[keywords]
本场点赞

[tasks]
@基线|什么都不要操作，让直播间正常播放|10
刷新一次|下拉刷新，等文字出现||
```

**例 2：找某个方法被谁调用（已有导出记录，类名从里面复制）**

```textrec
[config]
preset = trace
windowMs = 3000

[traceMethods]
com.demo.player.PlayerView#seekTo @args
com.demo.player.PlayerView#onPause @seq

[tasks]
@基线|什么都不要操作，让视频正常播放|10
拖进度条|按住进度条往左拖一段再松手||
暂停|点一下视频暂停，停 3 秒||
```

**例 3：找「判断状态」的钩子（比如是否已点赞）**

```textrec
[config]
preset = statehook
windowMs = 3000

[traceMethods]
com.demo.feed.LikeHelper#updateLike

[tasks]
对照（单击）|单击一次视频，别点赞||空
点赞|双击视频点赞||
取消赞|再双击一次取消赞||
```

## 9. 回复格式和收到错误报告时怎么办

- 回复里放**一个**代码块，里面是完整的配置 + 任务清单，用户整块复制，粘贴进首页「导入 / 导出」的框里。
- 提醒用户：粘贴后只是回填表单，要点底部「应用并重新记录」才生效（需要 root）；要记冷启动阶段，先强杀目标 App 再打开；应用后可以点首页「目标 App 实际生效的配置」里的读取按钮，确认配置真的生效、钩点真的装上了。
- 没在真机上验证过的功能，如实说，不要说成「肯定能用」。本模块只读，不会修改目标 App。
- **收到 TextRec 的校验报告时：只改被点到的行，其余保持原样，然后把完整的配置重新发一遍（同一个代码块）。** 不要借机改别的，不要只发改动的几行。报告里的行号对应用户粘贴的原文。
- 不确定的内容（类名、字段值、用户想要什么）：问用户，或者先给一份更宽的配置去抓记录，不要猜。
