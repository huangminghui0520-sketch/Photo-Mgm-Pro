// algorithm/DerivedPhotoName.kt —— 派生副本判定的公开门面
//
// 为什么需要这层门面：后缀清单的真值在 Dedup.kt 的 DerivedNames（internal，P3-5 单一事实来源）。
// 但「这张照片是不是派生副本」两端都要用（PC 打角标、安卓三态过滤）。一旦各写一份，
// 就会出现「核心判派生、界面判不派生」的相反结论 —— T04 之前 desktop 的
// PhotoMgmCoreImpl 就是这么写偏的：只抄了 4 个后缀，漏了 optimized/resized/缩略/压缩/原图。
//
// 只暴露布尔判定，不暴露 suffixes/stripRe/score：那三个是 dedup 内部实现细节，
// 放出去等于邀请别人绕过 dedup 自己拼一套。
package algorithm

/**
 * 是否为派生副本（`_original` / `_compressed` / `_optimized` / `_缩略` 等）。
 *
 * 清单唯一定义处是 [DerivedNames]，本函数只是它的公开入口 —— 新增后缀改那里，两端自动跟上。
 */
fun isDerivedPhotoName(name: String): Boolean = DerivedNames.isDerived(name)
