# TextRec — 屏幕文字位置记录（LSPosed 模块，只读）

记录目标 App 屏幕上**每段文字 / 每个图标**是“谁设置的”（`setText` / `setImage*` 调用栈）和“谁画的”（`drawText` 调用栈），
并给出能直接拿去 hook 的方法签名（标明 **[稳定]** 还是 **[混淆]**，优先推荐稳定的）。**只观察，不修改任何内容**（改颜色/隐藏文字另做模块）。

## 用法
1. 构建：GitHub → Actions → Build Release APK (manual) → Run workflow（和旧项目一样）。
2. 安装后在 LSPosed 启用，作用域勾目标 App（默认抖音 `com.ss.android.ugc.aweme`）。
3. 打开本 App：设置 → 「保存并推送配置」（需要 root）→ 「清空记录并重新开始」。
4. **强杀目标 App 再冷启动**（Hook 在进程最早期装好，启动阶段的文字也会被记下）。
5. 回本 App → 「查看记录」→ 搜文字/类名/栈帧 → 点进详情。

## 采集原理
- `TextView.setText` 最底层汇聚方法 → 记**设置栈**，挂在该 TextView 上（只留最近一次）。
- `TextView.onDraw` 用 ThreadLocal 标记当前在画哪个 TextView；`Canvas.drawText/drawTextRun` 里取出它的设置栈，
  与**绘制栈**合成一条记录。没有 TextView 上下文的（自绘 / Compose）只有绘制栈，界面明确标注“无设置栈”。
- 归并 key = 设置栈签名 + 绘制栈签名 + View 类/id，**文字内容不进 key**，时长每秒变也只有一行，计数增长。
  每条记录会列出这个位置下**出现过的所有不同文字**（按文字形状去重，最多 40 种），所以整排标签合并成一行后仍能看到每个字，
  搜索也覆盖这些文字。
- **屏幕可见性**：采集阶段不过滤（预加载的内容也记，否则当前可见内容绑定时的设置栈会丢），
  写快照时才判断：每条记录弱引用最多 40 个 View（TextView 或图标），询问“已挂在窗口、可见、alpha 非 0、可见区域与窗口有交集”，
  通过的取它们**当前**的文字和屏幕坐标。列表里 ● 在屏幕上 / ○ 不在 / ? 未知（自绘、Compose 没有 View 可查，
  3 秒内画过算 ●，否则 ?，且不会被“只看屏幕上的”过滤掉）。该开关默认打开。
- TextView 的绘制栈只保留“这个 View 自己”的一段（到 View.draw 为止），祖先控件的 dispatchDraw 属于布局位置噪声，不参与签名。
- 限流：先用廉价“代理 key”（控件类 + id + 文字形状，数字折叠成 `#`，`00:12`≡`00:13`）判断是不是同一位置，
  同一位置只抓前 3 次栈，之后只计数；每 200 次抽样一次；全局每秒最多抓 1000 次栈兜底（冷启动一秒内文字很多，设太小会丢；被限流丢弃的次数会显示在列表顶部和导出文件里）。
- 栈清洗：去 Hook 框架帧、尾部系统帧；签名只看到最后一个 App 帧，lambda 编号等不稳定后缀被归一。
- 首次命中时后台线程反射查栈顶 3 个 App 帧的方法签名（`类#方法(参数类型):返回类型`，重载全部列出），
  再多带最多 3 个**没混淆**的帧（如 `androidx.compose.ui.text.MultiParagraph#paint-LG529CI`）。每行末尾标
  `[稳定]`（名字没被混淆，换版本大概率还能用）或 `[混淆·换版本会变]`；详情页和“只复制可hook签名”会先列稳定的，
  并给出一行“推荐 hook 点”。判断规则：类名 `X.xxx`、方法名 `LJxxx` / 单字母、类名只有 1~2 个字符 = 混淆。

## 1.3 新增
- **画笔 / 参数**：每次画文字都记画笔的颜色、字号、描边或填充（`Paint(color=0x.. size=.. style=STROKE|FILL)`），
  按变体去重（最多 16 种）。Compose 文字额外**自动观察** `MultiParagraph.paint*` 的参数，并和随后发生的 `drawText` 配对，
  记录里直接看到“这段文字 = 先描边 0x97000000、再填充 0xFFFFFFFF”。Compose Color 的打包 long 会自动标出 ARGB。
- **文字颜色设置位置**：hook `TextView.updateTextColors`（`setTextColor` / 样式 / XML 属性的汇聚点），记最近一次的颜色值和设置栈。
- **图标记录（kind=ICON）**：hook `ImageView.setImageDrawable/Resource/Bitmap/Icon/URI` 和 `View.setContentDescription`，
  记图标是谁设置的（资源名、Drawable 类名、位图尺寸、内容描述）。按钮上的图片不是文字，`drawText` 看不到，找“弹”这类按钮靠它。
- **方法追踪（kind=TRACE）**：配置里每行填 `类#方法`，只读地记录每次调用的**参数→返回值变体**和**调用栈（谁调用了它）**；
  不同调用位置各成一条记录。类还没加载时进入相关页面后自动重试，装不上的原因显示在列表顶部的 ⚠ 里。
- **更全的文字入口**：新增 `TextView.append`、`setHint`、`Canvas.drawTextOnPath`；`drawTextRun(MeasuredText…)` 以前会被悄悄丢掉，现在至少留一条记录。
- **自绘 / Compose 画布内坐标**（最近一次 drawText 的 x,y，不是屏幕坐标）。
- 文字截断从 60 字放宽到 200 字；每条记录跟踪的 View 从 16 个放宽到 40 个（评论列表等不再漏）。
- **导出文件头写明筛选条件**：“只看屏幕上”默认隐藏了多少条、编号是什么，免得看文件的人以为是漏记。
- 落盘：事件触发 + 单次延迟（默认 3 秒），写 `files/textrec/snapshot-<进程名>.json`；没有轮询、没有常驻定时器。
  配置热更新同样只在事件里顺带检查文件时间（≤2 秒一次）。

## 1.4 修正（根据 1.3 的真机导出）
- **推荐 hook 点不再乱推**：签名分四类——`[稳定]`（业务代码里没混淆的）、`[稳定·通用控件]`（`AppCompatImageView` / `DmtTextView` 这类，
  hook 后必须按 View 类/id 过滤）、`[稳定·通用基础设施]`（消息分发、LiveData、异步包装，永远不推荐）、`[混淆]`。
  每条签名带 `@栈第N层`（只数 App 帧），推荐只取离设置/绘制点 8 层以内的业务稳定帧；设置侧如果更近的只有混淆帧会加 ⚠ 提醒。
  设置侧、绘制/调用侧分别推荐；只有混淆名时再给一条**系统方法兜底**（`TextView#setText` / `View#setContentDescription` / `ImageView#setImageDrawable`，
  永远不会被混淆，但要按 View 类/id 过滤）。
- **图标资源名**：`AppCompatImageView.setImageResource` 是覆盖实现，不走 `ImageView.setImageResource`，1.3 里只看到
  `setImageDrawable drawable=BitmapDrawable 50x50`；现在单独 hook 它，记录里有 `res=资源名(0x资源id)`。
- 栈顶签名去重（构造方法链、重载链不再把同一个方法列两遍）。

## 1.6 精简与补充（根据 1.5 的真机导出）
17:58 那份 406 条的导出里 59% 的记录是“同一位置只是 View id 不同”的重复，14% 是 XML 布局加载栈，签名块重复拷贝占 23%。这版处理：
- **按位置合并**：归并 key 不再带 View id。同一段代码设置/绘制的一批控件合成一条，记录里列出全部 id（最多 60 个）。
  方法追踪的方法名、状态变化的方法名仍然进 key。
- **折叠 XML 布局加载栈**：连续的 `LayoutInflater` / `Constructor.newInstance` 帧折成一行；布局加载之前只有控件构造方法的，
  标为“XML 布局里写死的设置”，不再解析、列出可 hook 签名（没有业务代码可 hook）。
- **栈尾裁剪**：尾部的 Handler / QmiHandler / bytex 异步包装这类通用基础设施帧从第一个这类帧开始截掉。
- **导出去重**：整份导出里，同一份调用栈、同一份签名块只完整写一次，后面的记录写“与 #N 完全相同，不重复列出”。
  记录详情页仍然显示完整内容。
- **状态变化记录（kind=STATE）**：hook `View.setAlpha / setVisibility / setSelected`，只记被跟踪的 View（画过/设置过文字，
  或设置过图标）。记谁改的（设置栈）和取值；变体里带上 View 当前的文字，所以四个标签“首页/朋友/消息/我”能分开看。
  alpha 按 0.1 一档去重，动画不会刷出几十个变体。
- **方法追踪带 `this`**：变体里加上调用对象，View 显示为 `类#id「文字」`，追 `View#setAlpha` 这类系统方法时能看出是哪个 View。

## 1.10：页面按名称分类，导出可选页面或按页面分组

- 页面选择框里：点选 = 只看 / 只导出这个页面；长按 = 给页面命名（如“消息”）。多个页面起同一个名字会合成一类，名字保存在模块里。
- 内置了 MessagesFragment3=消息、FeedRecommendFragment=推荐、FeedFollowFragment=关注，其余自己命名。
- 选“全部页面”导出时，txt 里按页面分组，每组有“████ 页面：xxx ████”标题；JSON 每条记录带 pageLabel。

## 1.9：页面标签只取真正的页面

- 内部组件 Fragment（名字不带 Fragment，或就叫 "Fragment"，如 WidgetManager）不再改页面标签。
- 容器 Fragment 显示出来时，标签落到它里面此刻可见的子页面上，不再停在 MainFragment 这类容器名。

## 1.8：按页面分别记录，导出可选页面

- 每条记录带“页面”（Activity > Fragment）。同一个控件换了页面再出现，另记一条，不再互相覆盖；不需要为换页面去清空。
- 页面靠 hook androidx `Fragment.onResume / onHiddenChanged / setUserVisibleHint` 判断；App 不用 Fragment 时只按 Activity 区分。
- 记录列表新增“页面：…”按钮，选中后列表和导出（txt / JSON）都只含该页面；导出头部会写明页面。
- 记录按“记录那一刻所处的页面”归属：切页面时预加载的内容会算在当时的页面下，标记为 ○ 不在屏幕上。

## 1.7：清空记录后，屏幕上已经显示的内容也能被记录
- **原因**：记录是事件驱动的（setText / drawText / setImage*）。硬件加速下，静态不变的文字不会再被绘制，所以点“清空记录”之后，
  底部的首页/朋友/消息/我、顶部的同城/游戏等标签没有任何事件，一直是空的，点一下（触发重绘）才冒出来。
  另外旧版读到“清空”还要等下一次 hook 事件，并且会把“View → 设置栈”的对应一起清掉，重绘后的记录也没有设置栈。
- **配置监听**：目标进程用 inotify（`FileObserver`）监听 `config.properties`，模块一改配置（含“清空记录”）立刻生效，不轮询，不用等事件。
- **清空后重新采集**：在界面线程遍历当前窗口——可见的文字控件 `invalidate()` 重画一次（走正常的 drawText 记录）；
  图标 / 带内容描述的控件直接补一条“屏幕扫描”记录。只读，不改内容。
- **清空只清记录**：不再清掉“View → 设置栈 / 文字颜色栈”的对应，重画后的记录仍然带设置栈。
- 图标记录里带“屏幕扫描”且没有设置栈的，说明设置发生在模块启动之前（冷启动、没被抓到）。

## 导出
- txt：给人看。
- JSON：字段固定（`schema:1`，每条记录含 kind/view/viewId/activity/parents/drawCount/setCount/setStack/drawStack/sigs；1.3 新增字段 paints/pos/textColor/colorStack/drawNote 均为追加，不影响旧字段），
  可直接给后续的 hook 模块用。

## 已知限制
- Compose 没有 `setText`：只有绘制栈，“谁算出这段文字”要靠盯参数找。
- 代理 key 相同（同类、同 id、同形状）但来自不同代码路径的，前 3 次之后靠每 200 次抽样才能发现。
- 直接改 `Editable` 的文本变化（输入框里敲字）不会被记录。
- 图标只覆盖 ImageView 和有 contentDescription 的 View；纯 Canvas 画的位图/矢量（`drawBitmap`、Compose `Image`）暂不记录。
- Compose 的自动参数观察只针对 `MultiParagraph.paint*`；其他 Compose 内部方法用“方法追踪”手动加。
- 全量模式会拖慢目标 App，只在调试时开。
- 抖音更新后混淆名会变，每条快照带 App 版本号。
- 方法追踪若追踪每秒调用上千次的方法会拖慢目标 App（超过 3000 次后自动降为每 10 次取样一次）。
- 本模块只记录，不修改颜色或隐藏；那些交给单独的改色/隐藏模块。


## 1.5：记录“只设置、从未绘制”的文字
- 背景：TextRec 之前只在**绘制**时建记录。控件为 INVISIBLE / GONE / 尺寸为 0 / 未挂窗口 / 祖先不可见时，
  系统不会调用它的 `onDraw`，即使 App 每秒都在 `setText`（例如拖动进度条才显示的时长），也不会留下任何记录。
- 现在 `setText` 时就登记“这个位置设置过文字”（类型 **仅设置**，按 View 类 + id + 设置栈归并，每个位置一条）。
- 是否“从未绘制”在**写快照时**才判断：该位置跟踪的 TextView 里，`TextView.onDraw` 从没执行过的才算。
  控件后来被画了，这条记录就自动消失（已有对应的 TextView 记录），不会和它重复。
- 记录里给出这些控件**当前**的文字（时长会实时变）和**没被画的原因**：自身 GONE / INVISIBLE、
  祖先 xx 为 GONE、未挂到窗口、尺寸为 0、alpha≈0，或“可见且已布局但还没画”。
- 这类记录单独限额（默认 500，配置页可调），满额只计丢弃数，不影响其他记录；概况行会提示。
- 它们默认被“只看屏幕上的文字”隐藏（本来就不在屏幕上）。在列表里把类型切到“仅设置”就能直接看到，
  此时不再套“只看屏幕上”。用法：搜 `re:\d{1,2}:\d{2}` 找时长。
- 已知限制：子类如果重写 `onDraw` 却不调用 `super.onDraw`，TextRec 看不到它的绘制，会把它当成“从未绘制”，
  此时原因一栏会显示“可见且已布局，但还没被绘制……”。
