// LocationExtractor 单元测试（§3.2 / §9 测试要点）
package algorithm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocationExtractorTest {
    @Test fun `桩号优先`() {
        assertEquals("K138+700M", LocationExtractor.extract("巡查至K138+700M处，发现护栏损坏，已拍照取证"))
    }

    @Test fun `姓名不混入地点`() {
        assertEquals("A收费管理中心", LocationExtractor.extract("在A收费管理中心进行交接班，检查巡查装备齐全完好"))
    }

    @Test fun `从出发提取地点不混入姓名`() {
        assertEquals("A收费管理中心", LocationExtractor.extract("巡查人员张某某、李某某从A收费管理中心出发，往北行方向巡查"))
    }

    @Test fun `带字母服务区`() {
        assertEquals("D服务区", LocationExtractor.extract("巡查至D服务区，检查服务区消防设施齐全"))
    }

    @Test fun `带字母互通`() {
        assertEquals("E互通", LocationExtractor.extract("巡查至E互通，检查匝道情况正常"))
    }

    @Test fun `收费站`() {
        assertEquals("C收费站", LocationExtractor.extract("到达C收费站，途经，主线巡查"))
    }

    @Test fun `桩号优先于收费站`() {
        assertEquals("K170+000M", LocationExtractor.extract("巡查至K170+000M收费站，发现收费系统故障"))
    }

    @Test fun `桩号优先于机房`() {
        assertEquals("K164+000M", LocationExtractor.extract("到达K164+000M处通信机房，检查设备运行正常"))
    }

    @Test fun `隧道桩号`() {
        assertEquals("K164+000M", LocationExtractor.extract("巡查至K164+000M隧道，发现隧道渗漏水"))
    }

    @Test fun `事故处理完毕不带裸处 不误抓地点`() {
        assertNull(LocationExtractor.extract("事故处理完毕，未造成人员伤亡，交通恢复"))
    }

    @Test fun `普通无地点描述返回null`() {
        assertNull(LocationExtractor.extract("巡查人员张某某、李某某对全线进行巡查，路况正常"))
    }

    // ---------- 方向兼容（§3.2 增强：桩号+方向，方向前置） ----------

    @Test fun `方向在后 空格分隔`() {
        assertEquals("上行K12+300", LocationExtractor.extract("巡查至K12+300 上行，发现路面障碍物"))
    }

    @Test fun `方向在后 无空格`() {
        assertEquals("下行K12+300", LocationExtractor.extract("到达K12+300下行处，处置抛洒物"))
    }

    @Test fun `方向在前 空格分隔`() {
        assertEquals("上行K12+300", LocationExtractor.extract("上行 K12+300 路段发生交通事故"))
    }

    @Test fun `方向在前 无空格`() {
        assertEquals("下行K12+300", LocationExtractor.extract("下行K12+300 处标志牌倾斜"))
    }

    @Test fun `括号方向`() {
        assertEquals("上行K12+300", LocationExtractor.extract("接报K12+300（上行）处护栏损坏"))
    }

    @Test fun `左幅右幅`() {
        assertEquals("左幅K12+300", LocationExtractor.extract("K12+300 左幅 发现坑槽"))
        assertEquals("右幅K12+300", LocationExtractor.extract("右幅K12+300 施工中"))
    }

    @Test fun `上行线下行线`() {
        assertEquals("上行线K12+300", LocationExtractor.extract("K12+300 上行线 车流正常"))
        assertEquals("下行线K12+300", LocationExtractor.extract("下行线 K12+300 施工"))
    }

    @Test fun `带方向不带M桩号`() {
        assertEquals("上行K120+500", LocationExtractor.extract("巡查至K120+500 上行"))
    }

    @Test fun `无方向桩号 保持原样`() {
        assertEquals("K12+300", LocationExtractor.extract("巡查至K12+300处"))
    }

    @Test fun `方向词但不含桩号 不误抓`() {
        assertNull(LocationExtractor.extract("沿上行方向巡查，路况正常"))
    }

    // ---------- 新样本（收费公路巡查日志 v2）新增变体 ----------

    @Test fun `前方方向 带M单位 处`() {
        assertEquals("上行K138+700M", LocationExtractor.extract("到达上行K138+700M处，检查交安设施"))
    }

    @Test fun `前方方向 左幅 带M单位`() {
        assertEquals("左幅K139+100M", LocationExtractor.extract("巡查至左幅K139+100M处，发现路面有轮胎皮"))
    }

    @Test fun `双桩号区间 取首个带方向`() {
        assertEquals("下行K164+000M", LocationExtractor.extract("在下行K164+000M至下行K166+000M路段进行涉路施工监管"))
    }

    @Test fun `前方方向 带处 调头行`() {
        assertEquals("上行K145+000M", LocationExtractor.extract("在上行K145+000M处调头往北行方向巡查"))
    }

    @Test fun `前方方向 施工点`() {
        assertEquals("下行K164+000M", LocationExtractor.extract("到达下行K164+000M施工点，检查基坑施工"))
    }

    @Test fun `括号内上行线`() {
        assertEquals("上行线K12+300", LocationExtractor.extract("K12+300（上行线） 护栏损坏"))
    }

    @Test fun `前方方向 通信机房`() {
        assertEquals("下行K165+000M", LocationExtractor.extract("到达下行K165+000M处通信机房，检查设备运行正常"))
    }

    // ---------- 方位方向（北/南/东/西行） ----------

    @Test fun `北行前方方向`() {
        assertEquals("北行K123+000M", LocationExtractor.extract("北行K123+000M处发现路面有坑槽"))
    }

    @Test fun `南行后方方向`() {
        assertEquals("南行K123+000M", LocationExtractor.extract("到达K123+000M 南行，处置抛洒物"))
    }

    @Test fun `东行前方方向 带M`() {
        assertEquals("东行K456+000M", LocationExtractor.extract("巡查至东行K456+000M处，路况正常"))
    }

    @Test fun `西行无空格后方方向`() {
        assertEquals("西行K123+000M", LocationExtractor.extract("在K123+000M西行处检查护栏"))
    }

    @Test fun `方位方向词但无桩号 不误抓`() {
        kotlin.test.assertNull(LocationExtractor.extract("沿北行方向巡查，路况正常"))
    }

    @Test fun `桩号后方位方向被处阻断 不误配`() {
        // "往北行方向" 是巡查方向而非地点方向，"处"阻断后方方向 → 保持桩号本体
        assertEquals("K123+000M", LocationExtractor.extract("巡查至K123+000M处，往北行方向巡查"))
    }

}
