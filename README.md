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
