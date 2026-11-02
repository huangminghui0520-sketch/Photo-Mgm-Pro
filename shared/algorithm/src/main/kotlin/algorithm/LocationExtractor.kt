// algorithm/LocationExtractor.kt —— §3.2 地点识别（增强版：桩号+方向兼容）
package algorithm

object LocationExtractor {
    /** 先按标点/空格/介词整词切分，再在片段内匹配地点。
     *  ★ 目的：避免把"张某某在A收费管理中心"整段误抓为地点（姓名混入）。 */
    private val splitRe = Regex(
        """[，。、；：:！？\s]+|在|至|于|到达|前往|经过|路过|进入|穿过|绕行|驶入|驶出|附近|途经|行驶至|位于|沿线|从|对|返回|出发""")
    private val patterns = listOf(
        Regex("""K\d{1,3}[+.]\d{1,3}(?:M|米)?"""),                            // ★ 桩号优先（无方向场景兜底，见 stakeWithDir）
        Regex("""[一-龥A-Za-z0-9]{0,12}(?:收费站|收费管理中心|收费广场|主线站|匝道站|入口|出口)"""),
        Regex("""[一-龥A-Za-z0-9]{1,10}(?:立交|互通|枢纽|互通立交|枢纽互通)"""),  // E互通
        Regex("""[一-龥A-Za-z0-9]{1,10}(?:大桥|特大桥|跨线桥|隧道|隧道群)"""),
        Regex("""[一-龥A-Za-z0-9]{2,20}(?:施工点|施工|基坑|盾构|下穿|工程|工地|作业区|养护工区|项目部)"""),
        Regex("""[一-龥A-Za-z0-9]{1,10}(?:服务区|停车区|休息区)"""),           // D服务区
        Regex("""[一-龥A-Za-z0-9]{1,10}(?:机房|配电房|设备间|通信机房|监控中心)"""),
        // ★ 不含裸"处"——"事故处理完毕"会被误抓成地点"事故处"；"K138+700M处"已由桩号规则捕获
        Regex("""[一-龥A-Za-z0-9]{2,10}(?:全线|辖区|段|附近)"""),
    )

    /**
     * 方向词（长词在前，避免"上行线"被"上行"截断）。
     * 支持：上行 / 下行 / 左幅 / 右幅 / 上行线 / 下行线（方向可前、可后、可带括号）。
     */
    private val dirWords = arrayOf("上行线", "下行线", "上行", "下行", "左幅", "右幅", "北行", "南行", "东行", "西行")
    private val dirRe = dirWords.joinToString("|")

    /**
     * 桩号 + 可选方向整体匹配。
     * 捕获组：g1=前方方向  g2=桩号  g3=括号内方向  g4=后方方向（均可能为空）。
     * 例：K12+300 / 上行 K12+300 / K12+300 下行 / K12+300（左幅） / 右幅K12+300
     */
    private val stakeRe = Regex(
        """(?:($dirRe)\s*)?(K\d{1,3}[+.]\d{1,3}(?:M|米)?)(?:\s*[（(]\s*($dirRe)\s*[)）])?(?:\s*($dirRe))?""")

    fun extract(text: String): String? {
        // ① 桩号 + 方向（优先于切分）：整体匹配，方向前置 → "上行K12+300"
        stakeWithDir(text)?.let { return it }
        // ② 非桩号地点：切分片段内匹配（原逻辑；方向缺失/无关时保持原样）
        for (seg in splitRe.split(text)) {
            for (p in patterns) { p.find(seg)?.let { return it.value } }
        }
        return null
    }

    /**
     * 匹配桩号并合并方向（方向前置；无方向返回桩号本体）。
     * 方向优先级：括号方向 > 前方方向 > 后方方向（如"上行 K12+300（下行）"取下行）。
     */
    private fun stakeWithDir(text: String): String? {
        val m = stakeRe.find(text) ?: return null
        val stake = m.groupValues[2]
        val dir = m.groupValues[3].ifBlank { m.groupValues[1].ifBlank { m.groupValues[4] } }
        // ★ 方向前置：北行K123+000M（不再用括号包裹）
        return if (dir.isBlank()) stake else "$dir$stake"
    }
}
