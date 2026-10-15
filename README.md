# TextRec — 屏幕文字位置记录（LSPosed 模块，只读）

记录目标 App 屏幕上**每段文字**是“谁设置的”（`setText` 调用栈）和“谁画的”（`drawText` 调用栈），
并给出能直接拿去 hook 的方法签名。**只观察，不修改任何内容**（改颜色/隐藏文字另做模块）。

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
- 限流：先用廉价“代理 key”（控件类 + id + 文字形状，数字折叠成 `#`，`00:12`≡`00:13`）判断是不是同一位置，
  同一位置只抓前 3 次栈，之后只计数；每 200 次抽样一次；全局每秒最多抓 50 次栈兜底。
- 栈清洗：去 Hook 框架帧、尾部系统帧；签名只看到最后一个 App 帧，lambda 编号等不稳定后缀被归一。
- 首次命中时后台线程反射查栈顶 3 个 App 帧的方法签名（`类#方法(参数类型):返回类型`，重载全部列出）。
- 落盘：事件触发 + 单次延迟（默认 3 秒），写 `files/textrec/snapshot-<进程名>.json`；没有轮询、没有常驻定时器。
  配置热更新同样只在事件里顺带检查文件时间（≤2 秒一次）。

## 导出
- txt：给人看。
- JSON：字段固定（`schema:1`，每条记录含 kind/view/viewId/activity/parents/drawCount/setCount/setStack/drawStack/sigs），
  可直接给后续的 hook 模块用。

## 已知限制
- Compose 没有 `setText`：只有绘制栈，“谁算出这段文字”要靠盯参数找。
- 代理 key 相同（同类、同 id、同形状）但来自不同代码路径的，前 3 次之后靠每 200 次抽样才能发现。
- `Editable.append` 这类不经过 `setText` 的文本变化不会被记录。
- 全量模式会拖慢目标 App，只在调试时开。
- 抖音更新后混淆名会变，每条快照带 App 版本号。
- 本版暂未包含：颜色追踪、指定类（`类#方法`）参数/返回值追踪。
