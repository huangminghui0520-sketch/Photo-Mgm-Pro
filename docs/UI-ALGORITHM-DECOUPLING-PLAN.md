# UI 层 algorithm.* 依赖清理方案（暂缓执行 · 只出方案）

> 状态：**方案**（不改代码）。触发执行的前提见 §5。
> 关联：`REFACTOR-EXECUTION-PLAN.md` §21.7「UI 层 4 处 `algorithm.*` 依赖的真正清理」。
> 日期：2026-11-03

---

## 1. 目标与现状

P5 门禁（`scripts/check-shared-purity.py`）把「UI 层直接 import `algorithm.*`」锁在
**4 种去重类型**的棘轮上限上。这 4 种是：

| 类型 | 归属 | 能否消除 | 说明 |
| --- | --- | --- | --- |
| `algorithm.model.Photo` | 算法层模型 | **能** | UI 只读 `id/sourceRef/displayName/captureTimeMs`，`app.api.PhotoInfo` 全具备 |
| `algorithm.model.LogEvent` | 算法层模型 | **能** | UI 只读 `id/eventType/location/description/startTimeMs/endTimeMs`，`app.api.EventSnapshot` 全具备 |
| `algorithm.DedupKeepMode` | 算法层枚举 | **不应** | `app.api.Settings.dedupMode` / `AppState.dedupMode` 直接暴露此枚举，是契约的一部分 |
| `algorithm.AppZone` | 算法层枚举 | **不应** | UI 用它做「业务时区」格式化（`Instant.atZone(AppZone.ID)`）；`app.api.Settings.patrolDate` 也依赖它 |

**清理目标**：把前两种（`Photo` / `LogEvent`）替换为 `app.api` 投影类型，使棘轮从 4 降到 2。
后两种（`DedupKeepMode` / `AppZone`）是 `app.api` 契约**主动暴露**的算法类型，消除它们需要
在 `app.api` 再包一层枚举投影（见 §4.2），**不在本方案范围**——那是另一个独立决策。

---

## 2. 为什么「机械替换」行不通（核心风险）

第一反应是「把 `UiState.photos: List<Photo>` 改成 `List<PhotoInfo>`、`parsed.events` 换成
`EventSnapshot`」，但这会踩中 **T11 刻意回避的投影层时序一致性难题**。具体有两个坑：

### 2.1 `photos` 的时序语义陷阱（最危险）

当前数据流：

```
PhotoSession.photos  ──►  UiState.photos（直接取引用，零转换）
    （会话级：clearPhotos() 在 EXIF 精读前 / setPhotos() 在精读后）

AppState.photos  ──►  ViewPublisher.buildPhotoInfos(session) 在「状态发布」时生成
    （状态级：publish / publishScan 两个入口）
```

两者**更新时机不同步**：

- `session.photos` 是「会话内部」在 EXIF 精读**完成**后由 `setPhotos()` 填充、精读**前**由
  `clearPhotos()` 清空的——它是**同步的、确定性的**。
- `app.photos` 是 `ViewPublisher.publish` 在「发布 AppState 快照」那一刻才 `buildPhotoInfos` 的
  投影产物，走的是**异步状态发布**。

`UiProjection.kt` 文件头明确记录了为什么不能换：

> ① Compose 的 `remember(key)` 依赖引用相等；每次投影新建 List 会让所有事件卡片每帧重算。
> ② `ThumbnailRefreshTest` 断言「清缓存那一刻 `state.value.photos.size == 0`」，它从主线程读
>    `state.value`；任何异步投影都会让它读到上一帧的旧值。

**`ThumbnailRefreshTest` 的两个时序断言**（`android/app/src/test/.../ThumbnailRefreshTest.kt`）：

1. `clear()` 必须发生在 EXIF 精读之前 → `photosAtClear == 0`；
2. `preload()` 必须发生在照片读取之后 → `photosAtPreload > 0`。

这两个断言靠的是 `session.photos` 的会话级时序。若 `UiState.photos` 改取 `app.photos`，
时序变成「状态发布级」，断言会**随机假失败**（读到上一帧旧快照）。

### 2.2 `events` 的源头不在 `UiState` 里

UI 层消费事件走的是 `state.parsed`（`ParsedLog?`），而 `parsed.events: List<LogEvent>`。
`UiState` 里**根本没有** `events: List<EventSnapshot>` 字段——`AppState.events` 存在，但
`UiState` 投影层没把它搬过来。所以替换 `LogEvent` 需要：

1. `UiState` 新增 `events: List<EventSnapshot>` 字段；
2. 所有读 `state.parsed.events` 的地方（`UnifiedScreen` / `LogSection`）改读 `state.events`；
3. 但 `state.parsed` 还被 UI 读 `skippedLines` / `inspectors` / `vehicle` / `notices` 等**解析级**
   字段，这些 `EventSnapshot` 没有——所以 `parsed` 字段不能整个删掉，只能**并存**：
   `parsed`（解析摘要 + skippedLines）与 `events`（事件快照）各司其职。

这意味着替换 `LogEvent` 不是「换个类型」，而是**拆开 `parsed` 的两个职责**（解析摘要 vs 事件列表）。

---

## 3. 分两步的正确路径

### 第 3.1 步（低风险）：`LogEvent` → `EventSnapshot`

**范围**：只动 `events` 这一条链，不碰 `photos` 的时序陷阱。

1. `UiState` 新增 `val events: List<EventSnapshot> = emptyList()`；
   `UiProjection.projectUiState` 里 `events = app.events`（`AppState.events` 是现成的）。
2. UI 层读事件列表的地方改读 `state.events`：
   - `UnifiedScreen.kt`：`state.parsed?.events?.size` → `state.events.size`；
     `parsed.events.find { it.id == eventId }` → `state.events.find { it.id == eventId }`；
     `events = parsed.events`（传给 Sheet）→ `events = state.events`。
   - `LogSection.kt`：`parsed.events.size / filter / forEach` → `state.events`。
   - `EventCard` / `EventPhotosSheet` / `UnmatchedPhotosSheet` / `MoveTargetDialogWithSearch`
     的 `events: List<LogEvent>` 参数类型 → `List<EventSnapshot>`。
3. 字段访问保持不变（`id/eventType/location/description/startTimeMs/endTimeMs` 两边同名）。
   注意：`LogSection` 里 `e.location ?: ""` / `e.description ?: ""` 是**冗余空安全**
   （`LogEvent.location`/`description` 是 `String` 非空），换成 `EventSnapshot` 后应去掉 `?: ""`。
4. 验证：编译 + `:app:testDebugUnitTest` + 门禁（P5 应显示「降至 3 种」）。

**风险**：低。`EventSnapshot` 字段集是 `LogEvent` 的**超集对齐**（多出 `photoIds/isPending/hints`，
UI 用不到也不影响）。唯一的连带改动是 `LogSection` 里 `parsed.skippedLines/inspectors/vehicle`
仍走 `state.parsed`（保留），所以 `parsed` 字段**继续存在**，只是「事件列表」这条数据源换了。

### 第 3.2 步（中风险）：`Photo` → `PhotoInfo`

**范围**：动 `photos` 这条链，**必须先解决 §2.1 的时序一致性**。

1. **前置：把 `UiState.photos` 的时序语义对齐到 `session.photos`**，而不是简单改类型。两种做法：
   - **方案 A（推荐）**：让 `PhotoSession` 额外维护一份 `PhotoInfo` 视图，随 `setPhotos` 同步更新
     （在 `setPhotos` 里同时 `photosInfo = list.map { ... }`），`UiState.photos` 取这份同步视图。
     这样保留了「会话级时序」，`ThumbnailRefreshTest` 的时序断言不破。
     —— 代价：`PhotoInfo` 投影逻辑（现在在 `ViewPublisher.buildPhotoInfos`）要下沉到 `PhotoSession`，
     或者让 `PhotoSession` 暴露一个 `fun photoInfos(): List<PhotoInfo>` 供投影层调用。
   - **方案 B**：让 `UiState.photos` 改取 `app.photos`，**同时**重写 `ThumbnailRefreshTest` 的
     两个时序断言（改为断言「AppState 发布后的最终态」，不再依赖「主线程同步读到中间态」）。
     —— 代价：这是**削弱护栏**，违背该测试存在的初衷（防「清缓存晚于读照片」的回归），不推荐。
2. 把 `UiState.photos: List<Photo>` → `List<PhotoInfo>`，UI 层 5 处签名同步改：
   `EventViewer` / `PhotoGrid` / `PhotoTile` / `EventCard.photoThumbs` / `PhotosSheet`。
3. `preloadThumbnailCache(photos: List<Photo>)`（AppViewModel）同步改 `List<PhotoInfo>`。
4. 字段访问不变（`id/sourceRef/displayName/captureTimeMs` 两边同名）。
5. 验证：编译 + 单测 + 门禁（P5 应显示「降至 2 种」）+ **真机回归**（冷启动、事件卡片、
   照片网格、全屏查看器、未匹配池、批量移动）。

**风险**：中。核心是 §2.1 的时序一致性。**必须在方案 A 落地、`ThumbnailRefreshTest` 仍绿的前提下**
才能继续，否则就是在削弱护栏。

---

## 4. 棘轮降到 2 之后（不在本方案，记录备查）

### 4.1 `DedupKeepMode` / `AppZone` 为何不消

这两个枚举是 `app.api` 契约**主动暴露**的：

- `Settings.dedupMode: DedupKeepMode`、`AppState.dedupMode: DedupKeepMode`；
- `Settings.patrolDate = LocalDate.now(AppZone.ID)`。

UI 读它们是「读契约暴露的类型」，不是「越层直读算法内部」。P5 门禁的棘轮把它们计入
「UI 层 algorithm.* 依赖」，但它们**不是违规**，只是「契约里恰好用了算法层的枚举做字段类型」。

要真正清零，得在 `app.api` 再造一层枚举投影（例如 `app.api.DedupMode` 映射到
`algorithm.DedupKeepMode`），并在 `PhotoMgmCore` 门面做双向转换。这是**契约层的大改**，
收益是「门禁数字好看」，代价是「多一层映射 + 两端转换」，**不值得**——除非未来算法层枚举
频繁变动、需要隔离。当前记入备查，不排期。

### 4.2 `AppZone` 的替代

UI 层用 `AppZone.ID` 做 `Instant.atZone(...)` 格式化（`EventViewer` / `PhotoGrid` / `LogSection`）。
这个 zone 是「业务时区」，本身是业务概念。若 `app.api` 暴露一个 `patrolZoneId()` 或直接
`Settings.zone: ZoneId`，UI 就能读契约而非算法。但 `ZoneId` 是 `java.time` 类型，进共享契约
需谨慎（当前 `Settings` 只放 `LocalDate`，无 `ZoneId` 字段）。记入备查。

---

## 5. 触发执行的前提（什么时候回来做）

1. **第 3.1 步**（LogEvent→EventSnapshot）：随时可做，风险低，可作为独立小提交先行。
2. **第 3.2 步**（Photo→PhotoInfo）：需先落地 §3.2 的方案 A（`PhotoSession` 同步维护
   `PhotoInfo` 视图），并确认 `ThumbnailRefreshTest` 的时序护栏**不改断言仍绿**。建议等
   当前版本的 `UiState` 形状稳定一轮（与 T15 的「一个版本周期」同一节奏）再动。
3. **T15（`:ui` 模块拆分）**：触发条件 `PhotoUiState` / `PhotoActions` 稳定运行一个版本周期，
   而这两个类型**当前代码中不存在**（是计划设想的未来契约）。因此 T15 仍**不排期**，
   见下节。

---

## 6. 与 T15 的关系

T15 的目标是「把 `android/app/ui` 提成 `:ui` 模块，用 Gradle 依赖规则让 UI 无法 import `:data`
变成编译错误」。它和本方案的「algorithm 依赖清理」是**正交的两件事**：

- 本方案消的是 **UI → algorithm** 的越层（P5 棘轮）；
- T15 消的是 **UI → data** 的越层（Gradle 模块边界）。

两者都指向同一个方向（让 UI 只依赖 `app.api` 契约），但 T15 的触发条件
（`PhotoUiState`/`PhotoActions` 稳定）**尚未满足**——这两个类型在代码中不存在，计划也明确
「本计划不排期」。因此 T15 **跳过**，待契约稳定后再单独评估，不并入本方案。

---

## 7. 结论

| 事项 | 结论 | 依据 |
| --- | --- | --- |
| 第 3.1 步 LogEvent→EventSnapshot | 可做，低风险 | 字段超集对齐，仅拆 `parsed` 双职责 |
| 第 3.2 步 Photo→PhotoInfo | 暂缓，需先解时序 | `ThumbnailRefreshTest` 时序护栏 + `UiProjection` 引用相等 |
| DedupKeepMode/AppZone | 不消 | 是 `app.api` 契约暴露，非越层 |
| T15 `:ui` 拆分 | 跳过 | 触发条件未满足，计划不排期 |
