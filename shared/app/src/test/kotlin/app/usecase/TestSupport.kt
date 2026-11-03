// shared/app 用例测试的公共夹具（纯 JVM 假端口）
//
// ★ 为什么这些假实现能构成证据：
//   被验证的是**编排**（顺序、清空时机、计数、提示语），而不是 IO。假端口把 IO 拿掉之后，
//   "分析前是否清掉人工调整""取消后状态是 READY 还是 FAILED"这类问题就变成了可断言的事实。
//   真端口的等价性另有黄金样本（桌面端）与真机清单（安卓端）兜底。
//
// ★ 日志文本与桌面端 `CorePipelineTest` 用的是同一段样例（三行、三条事件、两个时间段）：
//   同一个输入在两套实现上得到同一批事件，是 T10 切换调用时最省事的对照。
package app.usecase

import algorithm.AppZone
import algorithm.CandidatePhoto
import algorithm.ExifReader
import algorithm.PhotoSourceProvider
import app.api.Settings
import app.infra.DelayHandle
import app.infra.DelayScheduler
import app.infra.DebouncedTextStore
import app.port.LogTextStorage
import app.port.PhotoIdSource
import app.port.SettingsStorage
import app.state.AppStateStore
import app.state.PhotoSession
import java.time.LocalDate
import java.time.LocalDateTime

/** 夹具：日期、日志文本、时间换算。 */
object Fixtures {

    val PATROL_DATE: LocalDate = LocalDate.of(2026, 8, 14)

    val LOG_TEXT: String = """
        08时00分 与早班巡查人员张某某、李某某在A收费管理中心进行交接班，检查巡查装备齐全完好，车况正常。
        10时15分 巡查至上行K138+700M处，检查交安设施，发现护栏损坏，已拍照取证。
        12时00分 巡查人员张某某、李某某在下行K138+800M处发现标志牌倾斜，已拍照取证。
    """.trimIndent()

    /** 某日某时某分（北京时间）的 epoch 毫秒。 */
    fun at(hour: Int, minute: Int, second: Int = 0, date: LocalDate = PATROL_DATE): Long =
        LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute, second)
            .atZone(AppZone.ID).toInstant().toEpochMilli()

    /** 候选照片（sourceRef 与 displayName 都按 filename 生成，粗筛只看文件名里的日期）。 */
    fun candidate(fileName: String, id: Long = 0L, dir: String = "/photos"): CandidatePhoto =
        CandidatePhoto(id = id, sourceRef = "$dir/$fileName", displayName = fileName)
}

/** 假照片扫描：目录 → 候选清单。可指定"扫描直接失败"。 */
class FakeSourceProvider(
    private val byDir: Map<String, List<CandidatePhoto>> = emptyMap(),
    private val failWith: Throwable? = null,
) : PhotoSourceProvider {
    var calls: Int = 0
        private set

    override fun listCandidates(sourceDir: String): List<CandidatePhoto> {
        calls++
        failWith?.let { throw it }
        return byDir[sourceDir].orEmpty()
    }
}

/** 假 EXIF：sourceRef → 拍摄时间 / GPS（未配置即读不到）。 */
class FakeExif(
    private val captureTimes: Map<String, Long?> = emptyMap(),
    private val gps: Map<String, Pair<Double, Double>?> = emptyMap(),
) : ExifReader {
    override fun readCaptureTimeMs(sourceRef: String): Long? = captureTimes[sourceRef]
    override fun readGps(sourceRef: String): Pair<Double, Double>? = gps[sourceRef]
}

/** 假 id 分配：候选自带 id 就沿用（模拟安卓），否则顺序分配（模拟桌面端）。 */
class FakeIdSource(private var next: Long = 100L) : PhotoIdSource {
    private val assigned = LinkedHashMap<String, Long>()
    var resets: Int = 0
        private set

    override fun reset() {
        assigned.clear()
        resets++
        next = 100L
    }

    override fun allocate(sourceRef: String, candidateId: Long): Long =
        assigned.getOrPut(sourceRef) { if (candidateId > 0) candidateId else next++ }
}

/** 内存版设置存储（记录写入次数，便于断言「确实落盘了」）。 */
class MemorySettingsStorage(initial: Settings = Settings(patrolDate = Fixtures.PATROL_DATE)) : SettingsStorage {
    var current: Settings = initial
        private set
    var saves: Int = 0
        private set

    override fun load(): Settings = current

    override fun save(settings: Settings) {
        current = settings
        saves++
    }
}

/** 内存版日志存储（记录落盘次数与最后一次内容）。 */
class MemoryLogStorage(initial: String = "") : LogTextStorage {
    var persisted: String = initial
        private set
    var saves: Int = 0
        private set

    override fun load(): String = persisted

    override fun persist(text: String) {
        persisted = text
        saves++
    }
}

/**
 * 手控延迟调度器：`onChanged` 只登记，什么时候到期由测试决定。
 *
 * ★ 不用真实定时器：那会让「防抖后是否落盘」变成一条看运气的用例（CI 机器一慢就红）。
 *   本夹具让"到期"成为显式动作，于是"还没到期时不该写盘""取消后到期也不写"都能被钉住。
 */
class ManualScheduler : DelayScheduler {
    private val pending = mutableListOf<() -> Unit>()
    var scheduled: Int = 0
        private set

    override fun schedule(delayMs: Long, action: () -> Unit): DelayHandle {
        scheduled++
        var cancelled = false
        pending += { if (!cancelled) action() }
        return DelayHandle { cancelled = true }
    }

    /** 让所有已登记的任务立即到期。 */
    fun runAll() {
        val snapshot = pending.toList()
        pending.clear()
        snapshot.forEach { it() }
    }
}

/**
 * 编排层测试台：把 store / session / 四个用例按装配根的方式接起来。
 *
 * @param photos 源目录 `/photos` 下的候选照片。
 * @param sourceDirs 设置里的源目录（默认 `/photos`）。
 */
class Harness(
    photos: List<CandidatePhoto> = emptyList(),
    sourceDirs: List<String> = listOf("/photos"),
    exif: ExifReader = FakeExif(),
    sourceProvider: PhotoSourceProvider? = null,
    settings: Settings = Settings(patrolDate = Fixtures.PATROL_DATE, sourceDirs = sourceDirs),
) {
    val session = PhotoSession(zone = AppZone.ID)
    val settingsStorage = MemorySettingsStorage(settings)
    val logStorage = MemoryLogStorage()
    val scheduler = ManualScheduler()
    val logs = DebouncedTextStore(logStorage::persist, scheduler)
    val store = AppStateStore(AppStateStore.initial(settingsStorage.load(), logStorage.load()))

    val provider: PhotoSourceProvider = sourceProvider ?: FakeSourceProvider(
        mapOf("/photos" to photos),
    )
    val ids = FakeIdSource()

    val pipeline = AnalysisPipeline(
        store = store,
        session = session,
        logs = logs,
        sourceProvider = provider,
        exifReader = exif,
        hasher = { null },
        photoIds = ids,
    )
    val adjust = AdjustUseCase(store, session)
    val settingsUseCase = SettingsUseCase(store, session, settingsStorage, logs)

    /** 设置日志并跑一遍分析（等价于用户「粘贴日志 → 点分析」）。 */
    fun analyze(logText: String = Fixtures.LOG_TEXT, isCancelled: () -> Boolean = { false }) {
        settingsUseCase.setLogText(logText)
        pipeline.analyze(listener = null, isCancelled = isCancelled)
    }

    /** 造两张能落进事件 2/3 的照片：10:15（与事件 2 同刻）与 12:00（与事件 3 同刻）。 */
    companion object {
        fun twoPhotos(): List<CandidatePhoto> = listOf(
            Fixtures.candidate("IMG_20260814_101530.jpg"),
            Fixtures.candidate("IMG_20260814_120000.jpg"),
        )

        fun twoPhotoExif(): ExifReader = FakeExif(
            captureTimes = mapOf(
                "/photos/IMG_20260814_101530.jpg" to Fixtures.at(10, 15, 30),
                "/photos/IMG_20260814_120000.jpg" to Fixtures.at(12, 0, 0),
            ),
            gps = mapOf(
                "/photos/IMG_20260814_101530.jpg" to (30.25 to 120.50),
                "/photos/IMG_20260814_120000.jpg" to (30.26 to 120.51),
            ),
        )
    }
}
