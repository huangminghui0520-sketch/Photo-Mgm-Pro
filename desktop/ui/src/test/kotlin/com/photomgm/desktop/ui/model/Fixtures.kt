// ui/test/Fixtures.kt —— 用例用的最小快照构造
package com.photomgm.desktop.ui.model

import app.api.AppState
import app.api.CoreStatus
import app.api.EventSnapshot
import app.api.ExportResult
import app.api.PhotoInfo
import app.api.Settings

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

// ★ T10：ExportResult 的路径字段是不透明字符串（桌面=绝对路径，安卓=content URI），
//   所以这里直接给字符串，不再 Paths.get。
fun exportResult(dir: String = "/out/1101_120000"): ExportResult = ExportResult(
    exportDir = dir,
    ledgerFile = "$dir/ledger.xlsx",
    logFile = "$dir/export.log",
    manifestFile = "$dir/export-manifest.json",
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
    logText: String = "",
): AppState = AppState(
    settings = Settings(outputDir = outputDir),
    status = status,
    logText = logText,
    events = events,
    photos = photos,
    eventPhotoIds = eventPhotoIds,
    unmatchedPhotoIds = unmatched,
    lastExport = lastExport,
)
