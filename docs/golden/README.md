# 黄金样本回归基线（golden）

> 这一套东西只解决一个问题：**改完了，怎么证明没改坏。**
> 没有它，跨端重构的每一步都是"看起来没问题"；有它，每一步都能给你一句"产物逐单元格全等"。

---

## 1. 组成

| 文件 | 作用 |
| --- | --- |
| `make_sample.py` | 生成/校验样本素材：66 张照片（含重复组、派生命名、3 种 EXIF、无 EXIF、跨日期、误筛文件）+ 416 行巡查日志 |
| `run_desktop.sh` | 跑一次 PC 端真实导出（`cli` 无头模式），产出快照 `snapshot.json` |
| `snapshot_xlsx.py` | `ledger.xlsx` → JSON：单元格文本 + 样式号 + 行高 + 列宽 + 嵌入图片字节 + 图片锚点 |
| `snapshot_zip.py` | Android 导出 ZIP → JSON：条目清单 + 每条 sha256 + 目录结构；`--normalize` 折叠导出时刻、`--exclude` 剔除跨端不可比条目 |
| `snapshot_dir.py` | PC 导出目录 → **与 ZIP 同构**的快照，供跨端比对（目录条目也输出，否则两次快照长度永远差 N） |
| `make_android_prefs.py` | 生成 Android 的两个 SharedPreferences XML（settings / logs），含 XML 转义与回读自校验 |
| `run_android.sh` | 在模拟器上跑一次 Android 导出并拍快照（环境自检 → 装包 → 灌素材 → 注 prefs → 解析 → 导出 → 拉取） |
| `find_node.py` | 从 `uiautomator dump` 按文本定位可点坐标（Compose 的「父 clickable + 子 text」两层结构） |
| `compare.py` | 两份快照逐路径 diff，定位到"哪一行哪一格" |
| `sample/` | 素材本体（入库，1.2MB）——素材变了等于换了基线 |
| `../../docs/golden/desktop-baseline.json` | PC 端基线快照（入库） |

## 2. 标准用法

```bash
# ① 校验素材完好（换机器后先跑这个）
python scripts/golden/make_sample.py --verify

# ② 跑一次导出并出新快照
scripts/golden/run_desktop.sh /tmp/after

# ③ 与基线比对
python scripts/golden/compare.py \
  --base docs/golden/desktop-baseline.json \
  --after /tmp/after/snapshot.json
```

退出码：`0` 全等 / `1` 有差异 / `2` 参数或文件问题。**差异必须逐条解释**：
预期变更 → 写进 CHANGELOG 并把新快照升为基线；非预期 → 回退当步改动。

## 3. 三个已踩过的坑（都写进脚本注释了，这里给结论）

**① 复用 `--data-dir` 会读到上一轮缓存。**
第一次干跑时日志已换成 416 行的新版，界面却解出旧样本里的 `K842+000` 事件 —— 缓存按路径/日期命中，
不按日志内容。所以 `run_desktop.sh` 每次 `mktemp -d`，**永不复用**。
手工跑 CLI 时也必须换一个新的 `--data-dir`。

**② 日志里"看着像正文"的续行会凭空造出事件。**
`LogParserEngine` 判定独立日志的规则是：含桩号 / 命中事件关键词 / 以「接报、巡查、发现、处置…」开头。
初稿的续行写了「处置过程符合规范…」（以"处置"开头）、「全程佩戴反光背心…」（命中装备关键词），
于是每条都成了独立事件 —— 第一版样本因此产出 325 条事件、照片大面积错配。
**结论：续行必须以名词/副词开头，且不含桩号与关键词。**

**③ 中文时间少个"分"会被解析成整点。**
`八点四十` → 模板里 `分` 是可选的，于是只吃掉"八点"（08:00），"四十"落进描述里。
必须是 `八点四十分`。同理 `0800`、`08:00`、`8点00分` 四种写法都覆盖到了。

## 4. 快照为什么连行高/列宽一起拍

因为最容易回退且用户最先看到的，就是尺寸。
U1.9 修过一次「照片行高在 125% 缩放下被压成 0.8 倍（81pt→64.8pt）」的缺陷，根因是 `worksheet` 缺 `<sheetViews>`。
如果只比单元格文本，这类**版式回退**完全测不出来。所以快照里固定记录：

- `cols[].width`：F/G 两列必须 `20` 字符（= 3.81cm）
- `rows[].ht`：含照片的行必须 `81`（= 2.86cm），表头 `25`
- `hasSheetViews`：必须 `true`（缺了它，Excel 在非 100% 缩放下会重算行高）
- `anchors[]`：图片锚点的 row/col 与 EMU 尺寸

反向验证过一次分辨力：人为改 1 个单元格、把列宽改回 19.86、把行高改回 64.8、删 1 张图 →
`compare.py` 准确报出 4 处，且指到 `sheets[0].rows[2].ht: 81 → 64.8`。**标尺可信**。

## 5. 双跑一致性（基线不能自己抖）

同一份代码连跑两次（不同时间、不同导出目录）：

```
[SAME] 0 差异：snapshot.json == snapshot.json
```

说明 `ledger.xlsx` 的内容不含运行期时间戳 —— 这是它能当基线的前提。
若将来有人往台账里加了"导出时间"，这条会立刻红，届时用 `--ignore '导出时间'` 显式屏蔽，
**不允许整类字段不比对**。

## 6. 素材是"入库资产"，不是"临时文件"

`sample/photos/*.jpg` 已入库：JPEG 字节由 Pillow/libjpeg 版本决定，换环境重生成会对不上基线。
所以规矩是：**生成一次 → 提交 → 之后只读**。要改素材等于换基线，必须重记快照并在 CHANGELOG 说明。

`make_sample.py` 刻意**不删除**目录：重建走同名覆盖，多余文件只在末尾 `[WARN]` 列出，由人决定。
（第一版用了 `shutil.rmtree`，被执行环境拦下 —— 拦得对，素材是资产，不该被脚本删。）

## 7. Android 侧基线（P-2：已完成，真机 vivo V2232A）

基线已入库，走**真机**路线（模拟器卡在 AEHD 驱动拒绝访问，真机不需要硬件加速）：

| 产物 | 内容 |
| --- | --- |
| `docs/golden/android-baseline.json` | ZIP 结构：57 条目 / 26 目录 / 每张照片 sha256 |
| `docs/golden/android-ledger-baseline.json` | 台账：72 行 / 504 非空单元格 / 50 嵌图 / 50 锚点 |
| `docs/golden/screenshots/` | 启动 / 解析完成 / 导出完成三张证据截图 |

复现（约 3 分钟/轮）：

```bash
scripts/golden/run_android_real.sh .p2work/run-a
SKIP_INSTALL=1 KEEP_PHOTOS=1 scripts/golden/run_android_real.sh .p2work/run-b
python scripts/golden/compare.py \
  --base .p2work/run-a/snap-struct.json --after .p2work/run-b/snap-struct.json
```

两个脚本的分工：`run_android.sh` 是**模拟器**版（含 accel-check / AVD 创建 / `-no-window` 启动），
`run_android_real.sh` 是**真机**版（含 `MSYS_NO_PATHCONV`、独立素材目录、`pm clear` 后重新授权等真机坑）。
完整方案与问题清单见 `docs/ANDROID-EMULATOR-BASELINE.md`（模拟器）
与 **`docs/ANDROID-REAL-DEVICE-P2-REPORT.md`**（真机，含 P0 缺陷记录）。

Android 基线与桌面端的判定口径不同，这点必须记住：

| 端 | 产物 | 判定 |
| --- | --- | --- |
| PC | 导出目录（事件子目录 + `ledger.xlsx`） | 台账逐单元格 + 行高/列宽 + 嵌图字节全等 |
| Android | ZIP（同样的目录结构 + 台账 xlsx） | 双跑幂等（V2）+ 跨端差异登记（V4） |

**跨端不要求零差异**：两端当前在派生判定（D5）、EXIF 时间格式（D9）上本就有分叉。
P-2 的产出是一张差异表，作为 T04/T07/T12 的验收目标——改造后差异只能减少，不能新增。

实测结论（真机，2026-11-02）：

| 项 | 结果 |
| --- | --- |
| 台账单元格文本 | **0 差异**（71 条数据行完全一致） |
| 照片归属 | 3 个事件 / 8 张照片不同，全部是 `yyyy-MM-dd` 短横 EXIF —— 安卓正确、PC 漏识别 |
| 台账嵌图 | 44 vs 50 张（随上述归属）；**字节不可比**（Java ImageIO vs `Bitmap.compress`） |

> ⚠️ **嵌图字节跨端永远不可能相等**。两端常量都是 400×300 / 质量 50，但编码器不同，
> 同样"质量 50"在两套实现下的量化表与色度下采样不同，实测差约 30%。
> 跨端比嵌图只比 **尺寸 + 数量**，不比字节——否则会把正常差异当回归来追。

## 8. 真实素材（真机）：`/sdcard/Pictures/pmgolden-real`

合成素材覆盖不到的地方（真实水印相机命名、真实日志写法、真实 GPS），用一批真实数据补。**它只上真机，不入库**（含个人信息，不进 Git）。

| 项 | 内容 |
| --- | --- |
| PC 源 | `C:/Users/28988/Desktop/ceshi`（顶层 110 张 jpg + `10月24日巡查日志.txt`） |
| 设备落点 | `/sdcard/Pictures/pmgolden-real`（**扁平结构，与源一致**；与合成素材 `Pictures/pmgolden` 互不干扰） |
| 内容 | 55 主图 + 55 张 `_original` 派生 + 1 个日志（4,421 B），合计 115 MB |
| 完整性 | 逐文件 sha256 **111/111 与 PC 源一致**；MediaStore 已索引 **110** 张（日志非媒体，不入库） |
| 未拷贝 | 源内 `导出结果/1102_094007/`（18 个事件目录 + 54 张图 + `ledger.xlsx`/`export.log`/`export-manifest.json`）——那是**上次导出的产物**，不是素材；拷进去会让同一张照片在扫描中出现两份、污染去重与计数 |

拷贝与校验（可复现）：

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/Android/sdk/platform-tools/adb.exe; S=<serial>; DST=/sdcard/Pictures/pmgolden-real
$ADB -s $S shell "mkdir -p $DST"
cd "/c/Users/28988/Desktop/ceshi"
files=( *.jpg ); i=0
while [ $i -lt ${#files[@]} ]; do
  $ADB -s $S push "${files[@]:i:10}" "$DST/"; i=$((i+10))   # 必须每批把目标目录补上，否则末个文件被当成目标
done
$ADB -s $S push "10月24日巡查日志.txt" "$DST/10月24日巡查日志.txt"

# 双向比对（设备侧 sha256sum 与本地一致才算成功）
$ADB -s $S shell "cd $DST && sha256sum *"
$ADB -s $S shell "content call --uri content://media/external --method scan_volume --arg external_primary"
$ADB -s $S shell "content query --uri content://media/external/images/media --projection _data \
  --where \"_data LIKE '%pmgolden-real%'\" | grep -c _data="   # 期望 110
```

两个坑（都踩过）：

1. **`adb.exe` 不认 MSYS 路径**：`/c/Users/...` 会报 `cannot stat`。必须 `cd` 到目录用相对名，或 `cygpath -m` 转成 `C:/Users/...`。
2. **`adb push f1 f2 … fN` 没有目标参数**：多源推送时倒数第一个参数被当成目标，于是报 "target 'xxx.jpg' is not a directory"。每批必须显式写出 `"$DST/"`。
3. **`run-as` 读不到 `/sdcard`**（SELinux 限制，返回 `cat: …: Permission denied` 82 字节）。想验证设备副本字节/EXIF，用 `adb pull`，不要用 `run-as cat`——否则拿到一个 82 字节的"文件"，会误判成 GPS 又被剥离。

> 这批素材**全是 JPG**，没有 HEIC/RAW。厂商 HEIC 分支（`isHeic`、EXIF 读不出时的降级提示）仍**没有真实样本覆盖**，M3 验收时要单独说明。
