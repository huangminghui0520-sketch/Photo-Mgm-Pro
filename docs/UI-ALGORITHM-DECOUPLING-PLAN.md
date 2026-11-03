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
`EventSnapshot`」，但这里有两个真实的坑。**经 2026-11-03 对代码的逐行复查，下面这个版本
修正了初稿里一处不准确的判断**（见 §2.3 的「修正记录」）。

### 2.1 `photos` 的引用相等陷阱（真正的障碍）

当前数据流（**关键：两条链在 `store.withLock` 内同步，不是异步**）：

```
PhotoSession.photos  ──►  UiState.photos（直接取引用，零转换）
    （会话级：clearPhotos() 在 EXIF 精读前 / setPhotos() 在精读后）

AppState.photos  ──►  ViewPublisher.buildPhotoInfos(session) 在 publish/publishScan 时生成
    （状态级：每次发布都 NEW 一个 List<PhotoInfo>，即使照片集合没变）
```

真正的障碍不是「时序」，而是**引用相等性**：

- `session.photos` 是 `List<Photo>`，它的引用**只在真正换照片时变**（`setPhotos` / `clearPhotos`）。
  两次 `setPhotos` 之间，无论切三态、移动照片、标记台账，引用都稳定不变。
- `app.photos` 是 `List<PhotoInfo>`，它的引用在**每次 `publish` / `publishScan` 时都变**——
  `buildPhotoInfos(session)` 每次都 `session.photos.map { ... }` 生成**新 List**。而 `publish`
  会被「切三态（`setDedupMode`）」「移动照片（`movePhotos`）」「标记台账」等**照片集合未变**
  的操作触发。

`UiProjection.kt` 文件头理由①的精确含义：

> ① Compose 的 `remember(key)` 依赖引用相等；每次投影新建 List 会让所有事件卡片每帧重算
>   （`LogSection` 里 `remember(state.photos) { associateBy }` 会彻底失效）。

`LogSection.kt:146` 的 `remember(state.photos) { state.photos.associateBy { it.id } }` 以
`state.photos` 的**引用**为 key。若改取 `app.photos`，则「切三态」这种照片一张没变的操作，
也会因为 `app.photos` 是新 List 而触发 `associateBy` 重算（O(n)，照片上万时是真实损耗）。
功能仍正确，但**丢失了「照片没变就不重算」的优化**。

**`ThumbnailRefreshTest` 的时序断言（复查结论：改取 `app.photos` 不会破坏）**：

- 断言② `clear()` 时 `photos == 0`：`AppViewModel.setDate` 里 `invalidateAnalysis()` →
  `clearThumbnailCache()`。而 `AndroidCore.invalidateAnalysis`（第 312-330 行）在同一个
  `store.withLock` 内**同时** `session.clearPhotos()` 和 `store.publish { photos = emptyList() }`。
  所以 `clearThumbnailCache()` 那一刻，无论 `state.photos` 取 `session.photos` 还是 `app.photos`，
  都是 0。✓
- 断言③ `preload()` 时 `photos > 0`：`AnalysisPipeline.scanPhotos`（第 208-253 行）整个函数体
  在 `store.withLock` 内，`session.setPhotos(finalPhotos)` 与 `ViewPublisher.publishScan`
  （`buildPhotoInfos`）**同步原子**完成。`preloadThumbnailCache` 在流水线返回后调用，此时
  `app.photos` 已填充。✓

所以初稿「改取 `app.photos` 会让时序断言随机假失败」的担忧**不成立**——两条链在锁内是同步的。

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

### 2.3 重新评估（2026-11-03，逐行复查后）

初稿把 `Photo`→`PhotoInfo` 的障碍归因于「时序不同步」，**这个判断不准确**，已在上文修正。
复查后重新定位的障碍优先级：

| # | 障碍 | 性质 | 严重度 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | `app.photos` 引用不稳定（每次 publish 都 NEW） | 性能退化（非正确性） | 中 | 需 `PhotoSession` 缓存 PhotoInfo 视图（方案 A） |
| 2 | `parsed` 双职责需拆分 | 结构改动 | 低 | `events` 与 `parsed` 并存即可 |
| 3 | `PhotoInfo` 投影逻辑（`buildPhotoInfos`）位置 | 依赖下沉 | 低 | 可下沉到 `PhotoSession` 或保留在 `ViewPublisher` |
| 4 | 测试护栏 | 需验证 | 低 | 时序断言**不破**（已证），只需跑通 |

**核心判断修正**：`Photo`→`PhotoInfo` 是**可行**的，主要成本是「让 `PhotoSession` 同步维护一份
`PhotoInfo` 视图以保住引用相等优化」，而非「解时序陷阱」。风险从「中」降为「中低」。

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

### 第 3.2 步（中低风险）：`Photo` → `PhotoInfo`

**范围**：动 `photos` 这条链。核心是**保住 `remember(state.photos)` 的引用相等优化**（§2.1），
而非解时序（时序经复查已确认无碍）。

1. **前置（方案 A，推荐）：让 `PhotoSession` 缓存一份 `PhotoInfo` 视图**，随 `setPhotos` 同步更新：
   - 在 `PhotoSession` 加 `var photosInfo: List<PhotoInfo> = emptyList()`（private set），
     `setPhotos(list)` 里同时 `photosInfo = list.map { PhotoInfo(...) }`；
   - 把 `ViewPublisher.buildPhotoInfos` 的投影逻辑（含 `isDerived = isDerivedPhotoName(...)`）
     下沉为 `PhotoSession` 的一个方法（或让 `ViewPublisher` 调它），消除「两份投影逻辑」；
   - `UiState.photos` 改取 `session.photosInfo`，`preloadThumbnailCache` 同步改 `List<PhotoInfo>`。
   - 收益：引用相等语义与 `session.photos` 完全一致（只在真换照片时变），
     `ThumbnailRefreshTest` 时序断言不破，`LogSection.remember` 优化保留。
   - 代价：`PhotoSession`（共享层）要 import `app.api.PhotoInfo`——**这是合理的**，因为
     `PhotoInfo` 是共享契约类型，`PhotoSession` 本来就在 `app.state` 层（同属 `shared/app`），
     不存在越层问题（P5 门禁只盯 `app.api` 之外的越界，`app.state`→`app.api` 是层内）。
2. **方案 B（备选，不推荐）**：`UiState.photos` 直接改取 `app.photos`，接受「切三态/移动照片」
   时 `remember(state.photos)` 失效重算。功能正确、测试护栏也不破（时序已证同步），
   但有 O(n) 的 `associateBy` 重算损耗，违背 `UiProjection.kt` 理由①的初衷。
3. 把 `UiState.photos: List<Photo>` → `List<PhotoInfo>`，UI 层 5 处签名同步改：
   `EventViewer` / `PhotoGrid` / `PhotoTile` / `EventCard.photoThumbs` / `PhotosSheet`。
4. `preloadThumbnailCache(photos: List<Photo>)`（AppViewModel）同步改 `List<PhotoInfo>`。
5. 字段访问不变（`id/sourceRef/displayName/captureTimeMs` 两边同名）。
6. 验证：编译 + 单测（`ThumbnailRefreshTest` 的时序断言**不应改**，直接跑）+ 门禁
   （P5 应显示「降至 2 种」）+ **真机回归**（冷启动、事件卡片、照片网格、全屏查看器、
   未匹配池、批量移动）。

**风险**：中低。核心是方案 A 的「投影逻辑下沉到 `PhotoSession`」这一步要**消除与
`ViewPublisher.buildPhotoInfos` 的重复**（否则又造一份「必须人肉同步」的投影，正是本重构
要消灭的东西）。必须在方案 A 落地、`ThumbnailRefreshTest` 不改断言仍绿的前提下继续。

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
   `PhotoInfo` 视图、投影逻辑单点下沉），并确认 `ThumbnailRefreshTest` **不改断言**仍绿。
   经复查，时序护栏**不会破**（两条链在 `store.withLock` 内同步），真正的成本是「引用相等
   优化」的保留。建议等当前 `UiState` 形状稳定一轮再动（与 T15 的「一个版本周期」同节奏）。
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
| 第 3.2 步 Photo→PhotoInfo | 可做，中低风险 | 时序已证同步；核心是引用相等优化，用方案 A 保住 |
| DedupKeepMode/AppZone | 不消 | 是 `app.api` 契约暴露，非越层 |
| T15 `:ui` 拆分 | 跳过 | 触发条件未满足，计划不排期 |

---

## 8. 替代方案与调整建议（2026-11-03 复查新增）

针对用户「是否存在更符合现有架构的替代方案」的追问，复查后有两点调整建议，比初稿更贴合
现有分层：

### 8.1 `Photo`→`PhotoInfo` 其实「零字段损失」，且方向恰好正确

复查确认：**安卓 UI 层当前完全不消费 `isDerived`**（全仓库只有 `desktop/ui/EventCard.kt:425`
用它画白点）。而 `Photo` 与 `PhotoInfo` 的前 6 个字段（`id/sourceRef/displayName/captureTimeMs/
latitude/longitude`）**完全同名同型**，`PhotoInfo` 只是多了 `isDerived`。

因此「UI 改读 `PhotoInfo`」不是「换个近似类型」，而是**精确对齐**：UI 要的字段一个不少、
一个不多，还顺带为「安卓将来也画白点」留好了 `isDerived`。这比初稿「`PhotoInfo` 是 `Photo`
的超集、UI 用不到多余字段」的表述更积极——它其实是**恰好正确的目标类型**，且与桌面端
（已经读 `PhotoInfo`）保持一致，是「两端 UI 消费同一份契约投影」的收敛。

### 8.2 投影逻辑下沉的落点建议：`ViewPublisher` 而非 `PhotoSession`

初稿方案 A 建议把 `buildPhotoInfos` 下沉到 `PhotoSession`。复查后更贴合的落点是**保留在
`ViewPublisher`、只把「引用相等缓存」放进 `PhotoSession`**：

- `PhotoSession` 只加一个**缓存字段**：`var photosInfo: List<PhotoInfo>`（随 `setPhotos` 失效），
  但不持有投影逻辑——它仍是「数据容器」的定位（见 `PhotoSession.kt` 文件头「它只是一个数据容器」）。
- 投影逻辑 `buildPhotoInfos` **留在 `ViewPublisher`**（它本就是「派生视图组装」的唯一地方），
  只改为「写完 `session.photosInfo` 供后续复用」。
- 这样职责边界不变：`PhotoSession` 存状态、`ViewPublisher` 算投影，与现有分层完全一致，
  不引入「`PhotoSession` 直接 import `app.api.PhotoInfo`」的定位偏移（虽然这个 import 本身
  不越层，但会让 `PhotoSession` 从「纯 algorithm 对象容器」变成「混合契约类型容器」）。

**调整后的方案 A'（推荐）**：
1. `PhotoSession` 加 `var photosInfo: List<PhotoInfo>`（private set，`setPhotos` 里置空/失效）；
2. `ViewPublisher.buildPhotoInfos` 投影后，把结果写回 `session.photosInfo`（缓存）；
3. `UiState.photos` 改取 `session.photosInfo`（引用相等语义与 `session.photos` 一致）；
4. 其余（UI 签名、`preloadThumbnailCache`）同 §3.2 步骤 3-6。

这样既保住了 `remember(state.photos)` 的引用相等优化，又不改变 `PhotoSession` 的职责定位。

### 8.3 一个「更小步」的替代：先只做 `LogEvent`，`Photo` 观察一轮再动

若担心 `Photo` 链牵动 5 个 UI 文件 + `preloadThumbnailCache`，可**只先落地 §3.1**
（`LogEvent`→`EventSnapshot`，动 4 个文件、无引用相等陷阱、纯加字段），让 P5 从 4 降到 3，
观察一个版本周期，确认 `EventSnapshot` 投影在真机上稳定后，再以同一模式做 `Photo`。
这与「T15 等一个版本周期」的节奏一致，风险最低。
