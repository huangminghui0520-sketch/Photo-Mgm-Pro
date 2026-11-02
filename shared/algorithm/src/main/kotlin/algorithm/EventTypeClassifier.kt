// algorithm/EventTypeClassifier.kt —— §3.3 事件类型识别（按 v7.0 文档实现）
package algorithm

object EventTypeClassifier {
    private val accidentCore = listOf("事故","追尾","碰撞","刮擦","翻车","侧翻","剐蹭")
    private val specialActions = listOf("专项排查","专项检查","专项巡查")   // 交安设施专项排查组合条件
    private val jiaoanFacility = listOf("交安","护栏","标志牌","交通设施","防眩","轮廓标","里程碑","百米桩")
    // ★ 2026-10-01 否定词优先：演练/演习/培训/推演等非真实事件，命中直接返回「待确认」，
    //   避免「事故演练」「防汛演练」被关键词表误判为事故/汛期处置。
    private val negationWords = listOf("演练","演习","培训","推演","桌面推演","观摩")

    fun classify(text: String): String {
        if (negationWords.any { text.contains(it) }) return "待确认"
        if (text.contains("主线巡查")) return "主线巡查"   // ★ 显式声明优先于关键词表（样本"途经，主线巡查"）
        for ((type, keywords) in EventTypeKeywords.table) {
            if (type == "交安设施专项排查") {              // ★ 组合条件：专项动作词 ∧ 交安设施词
                if (specialActions.any { text.contains(it) } && jiaoanFacility.any { text.contains(it) }) return type
                continue
            }
            if (keywords.any { text.contains(it) }) {
                if (type == "交通事故处置" && !accidentCore.any { text.contains(it) }) continue
                return type
            }
        }
        return "主线巡查"
    }
}
