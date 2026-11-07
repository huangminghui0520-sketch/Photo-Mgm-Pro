// desktop/core/sync/UsbDriveScanner.kt —— UMS 盘符列举（裁定 F4-A1）
//
// ★ 明确不做原生 MTP：Windows 的 MTP 设备走 Shell 命名空间，**不分配盘符**，
//   java.nio.file / File.listRoots() 根本访问不到。零新库前提下不可能可靠读取，
//   所以 A 方式只覆盖两类：
//     ① 以大容量存储（UMS）出现的盘符；
//     ② 用户手动选择的本机目录（例如已挂载/已导出的 DCIM 副本）。
//   提示文案里必须写清这个限制，不能让用户以为「连上了就能读」。
//
// 本机目录也走同一套 PhoneSource：list 用 Files.list，pull 用 Files.copy，
// 与 ADB 路径在 Executor 眼里完全同构，测试用 @TempDir 就能覆盖全链路。
package com.photomgm.desktop.core.sync

import com.photomgm.desktop.core.platform.PcPhotoSourceProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

internal class UsbDriveScanner(
    private val roots: () -> List<String> = { File.listRoots().orEmpty().map { it.absolutePath } },
    private val isReadableDir: (String) -> Boolean = { p ->
        runCatching { Files.isDirectory(Paths.get(p)) && Files.isReadable(Paths.get(p)) }.getOrDefault(false)
    },
    // 只认可移动卷（UMS）。固定系统盘不是手机，把 C:\ 列进「手机目录」会误导用户。
    // 平台不支持 volume:isRemovable 时按「不是」处理 —— 用户仍可走手动选本机目录（F4-A1）。
    private val isRemovable: (String) -> Boolean = { p ->
        runCatching {
            Files.getFileStore(Paths.get(p)).getAttribute("volume:isRemovable") as? Boolean ?: false
        }.getOrDefault(false)
    },
) {

    // 当前可见的 UMS 盘符根；每个都作为「手机目录」下拉的一项。
    fun visibleDrives(): List<String> = roots().filter { isReadableDir(it) && isRemovable(it) }

    // 用户手动选的本机目录：不校验是不是盘符，能列文件即可。
    fun manualDir(path: String): String = path

    companion object {
        // 常见相机目录，作为盘符下的候选项展示（不存在则跳过）。
        val CAMERA_SUBDIRS = listOf("DCIM/Camera", "DCIM", "Pictures", "Camera", "Photos")

        fun candidateDirs(drive: String): List<String> = CAMERA_SUBDIRS
            .map { sub -> Paths.get(drive, *sub.split('/').toTypedArray()).toString() }
            .filter { p -> runCatching { Files.isDirectory(Paths.get(p)) }.getOrDefault(false) }
    }
}

// 本地目录数据源（UMS 盘符或用户手动选择的本机目录）。
internal class UsbPhoneSource(dir: String) : PhoneSource {

    private val root: Path = Paths.get(dir)

    override val id: String = root.toAbsolutePath().toString()
    override val kind: ConnectionKind = ConnectionKind.USB_DRIVE

    override fun list(): List<RemotePhoto> {
        if (!Files.isDirectory(root)) return emptyList()
        val out = ArrayList<RemotePhoto>()
        runCatching {
            Files.list(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { PcPhotoSourceProvider.isSupportedImage(it.fileName.toString()) }
                    .forEach { p ->
                        out += RemotePhoto(
                            name = p.fileName.toString(),
                            size = runCatching { Files.size(p) }.getOrDefault(0L),
                            modifiedMs = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrNull(),
                        )
                    }
            }
        }
        out.sortBy { it.name }
        return out
    }

    override fun pullTo(photo: RemotePhoto, staging: Path): Boolean = runCatching {
        val from = root.resolve(photo.name)
        Files.copy(from, staging, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)
}
