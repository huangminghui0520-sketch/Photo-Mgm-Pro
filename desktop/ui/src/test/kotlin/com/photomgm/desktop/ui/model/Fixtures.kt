// ui/test/Fixtures.kt —— 用例用的最小快照构造
package com.photomgm.desktop.ui.model

import com.photomgm.desktop.core.api.AppState
import com.photomgm.desktop.core.api.CoreStatus
import com.photomgm.desktop.core.api.EventSnapshot
import com.photomgm.desktop.core.api.ExportResult
import com.photomgm.desktop.core.api.PhotoInfo
import com.photomgm.desktop.core.api.Settings
import java.nio.file.Paths

fun photo(
    id: Long,
    name: String = "IMG_$id.jpg",
    timeMs: Long? = 1_700_000_000_000 + id * 60_000,
    lat: Double? = 30.25,
    lng: Double? = 120.5,
    derived: Boolean = false,
): PhotoInfo = PhotoInfo(
    id = id,
    displayName = name,
    sourceRef = "/src/$name",
    captureTimeMs = timeMs,
    latitude = lat,
    longitude = lng,
    isDerived = derived,
)

fun event(
    id: Int,
    type: String = "护栏损坏",
    location: String = "K138+700",
    description: String = "发现护栏损坏，已拍照取证",
    start: Long? = 1_700_000_000_000,
    end: Long? = null,
    photoIds: List<Long> = emptyList(),
    pending: Boolean = false,
    hints: List<String> = emptyList(),
): EventSnapshot = EventSnapshot(
    id = id,
    eventType = type,
    location = location,
    description = description,
    startTimeMs = if (pending) null else start,
    endTimeMs = end,
    photoIds = photoIds,
    isPending = pending,
    hints = hints,
)

fun exportResult(dir: String = "/out/PhotoMgm_export_20261101_120000"): ExportResult = ExportResult(
    exportDir = Paths.get(dir),
    ledgerFile = Paths.get("$dir/ledger.xlsx"),
    logFile = Paths.get("$dir/export.log"),
    manifestFile = Paths.get("$dir/export-manifest.json"),
    totalTargets = 10,
    copied = 10,
    skipped = 0,
    failed = 0,
)

fun appState(
    events: List<EventSnapshot> = emptyList(),
    photos: List<PhotoInfo> = emptyList(),
    eventPhotoIds: Map<Int, List<Long>> = emptyMap(),
    unmatched: List<Long> = emptyList(),
    status: CoreStatus = CoreStatus.READY,
    lastExport: ExportResult? = null,
    outputDir: String? = "/out",
): AppState = AppState(
    settings = Settings(outputDir = outputDir),
    status = status,
    events = events,
    photos = photos,
    eventPhotoIds = eventPhotoIds,
    unmatchedPhotoIds = unmatched,
    lastExport = lastExport,
)
