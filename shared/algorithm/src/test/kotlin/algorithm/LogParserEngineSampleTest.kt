// LogParserEngine 黄金样本验收测试（§3.5 必测基线）
// 《收费公路巡查日志》89 条输入 → 88 条事件，类型/地点/时间/全局信息逐条匹配（不脱敏）
package algorithm

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogParserEngineSampleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun t(ms: Long): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
    private fun d(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private val SAMPLE = """
上午8点 与早班巡查人员张某某、李某某在A收费管理中心进行交接班，检查巡查装备齐全完好，车况正常，开启粤E00001车载视频监控，视频清晰，已报监控中心。记录人：王某某。
08时00分 与早班巡查人员张某某、李某某在A收费管理中心进行交接班，检查巡查装备齐全完好，车况正常，开启粤E00001车载视频监控，视频清晰，已报监控中心。记录人：王某某。
8时15分 出发，巡查人员张某某、李某某从A收费管理中心出发，往北行方向巡查。
8时30分 到达K138+700M处，检查交安设施，发现护栏损坏，已拍照取证。
8时30分 巡查人员张某某、李某某在K138+800M处发现标志牌倾斜，已拍照取证。
8时45分 巡查至B收费站，检查收费站设施，收费车道设备运行正常，未发现异常。
9:05 到达C收费站，途经，主线巡查，路况正常。
9时10分 在B收费站往北行方向，发现路面有抛洒物，已清理，拍照取证。
9时12分 巡查至K139+100M处，发现路面有轮胎皮，已清理，拍照取证。
9时14分 巡查至K139+300M处，发现路面有木块，已清理，拍照取证。
9时16分 巡查至K139+500M处，发现路面有铁皮，已清理，拍照取证。
9时18分 巡查至K139+700M处，发现路面有散落物，已清理，拍照取证。
9时20分 巡查至K139+900M处，发现路面有掉落物，已清理，拍照取证。
9时22分 巡查至K140+100M处，发现路面异物，已清理，拍照取证。
9时24分 巡查至K140+300M处，发现路面有障碍物，清理路障，已拍照取证。
9时25分 巡查至D服务区，检查服务区消防设施齐全，卫生状况良好。
9时26分 巡查至D服务区，检查服务区卫生状况，发现垃圾未及时清理，已通知整改。
9时30分 巡查至E互通，检查匝道情况正常。
9时35分 巡查至K141+000M处涵洞，检查情况正常。
9时40分 巡查至K140+200M处，发现桥梁伸缩缝有轻微裂缝，未影响通行，记录在案。
9时42分 巡查至K140+400M处桥梁，检查支座，发现支座轻微老化，已记录。
9时52分 巡查至K146+500M处，发现路面有坑槽，已记录并拍照取证。
9时55分 巡查至K142+300M处，进行高边坡巡查，发现边坡有少量落石，已拍照取证。
10时00分 接报K145+000M处发生两车追尾事故，立即前往，10时15分到达现场，事故涉及粤A11111和粤B22222，造成路产损坏，已拍照取证，交警到场处理。
10时05分 在K145+000M处调头往北行方向巡查。
10时18分 巡查至K147+800M处，发现边沟堵塞，已记录并通知养护清理。
10时30分 事故处理完毕，未造成人员伤亡，未造成路产损坏，交通恢复。
10时35分 在K149+000M处发现路侧有非法种植，已记录并通知养护清理。
10时40分 巡查人员发现K147+000M处有行人上高速，已劝离，拍照取证。
10时48分 在K148+200M处发现一辆新能源车超速，车牌粤AD12345，已拍照取证，违法类型超速。
10时50分 在K148+000M处发现一辆车超速，车牌粤E33333，已拍照取证，违法类型超速。
10时55分 在K148+500M处发现一辆故障车，车牌粤C44444，占用应急车道，已通知救援，拍照取证。
11时05分 发现一辆摩托车在高速上行驶，已劝离，拍照取证。
11时10分 巡查至K150+000M处，发现路侧有动物尸体，已通知养护清理，拍照取证。
11时25分 协助交警在K152+000M处进行交通管制，因前方施工，已摆放标志，拍照取证。
11时40分 在K153+500M处发现路面有油污，疑似危化品泄漏，已上报并警戒，拍照取证。
11时55分 巡查至K155+000M处，发现一辆车自燃，已报消防，现场警戒，拍照取证。
12时00分 巡查人员张某某、李某某对全线进行巡查，路况正常。
12时10分 在K156+200M处发现无人机违规飞行，已制止并记录，拍照取证。
12时25分 到达A收费管理中心用餐休息。
12时30分 在A收费管理中心原地待命。
12时40分 报监控中心，记录巡查情况。
13时00分 下午巡查开始，从A收费管理中心出发，往南行方向巡查。
13时15分 在K158+000M处发现隔离栅损坏，已拍照取证。
13时30分 巡查至K160+000M处，发现路侧有非法占用公路用地搭建临时棚，已制止并上报，拍照取证。
13时45分 在K162+000M处发生边坡滑坡，泥土占用应急车道，已设置警示标志，拍照取证。
13时48分至14时48分 在K164+000M至K166+000M路段进行涉路施工监管，检查基坑、盾构、下穿施工点，施工标志齐全，已拍照取证。
14时00分 巡查至K164+000M隧道，发现隧道渗漏水，已记录并拍照取证。
14时10分 巡查至K163+500M隧道，检查隧道衬砌，发现裂缝，已记录。
14时15分 到达K166+000M处，检查隧道机电设施，发现照明灯具有一盏不亮，已拍照取证。
14时18分 到达K165+000M隧道，检查隧道通风设施，发现一台风机故障，已记录。
14时20分 到达K165+000M处通信机房，检查设备运行正常。
14时25分 检查K165+500M处监控设备，发现一台摄像机故障，已拍照取证。
14时26分 检查K165+500M处情报板，发现显示异常，已拍照取证。
14时30分 在K168+000M处发现通信光缆中断，影响监控视频传输，已报修并拍照取证。
14时45分 巡查至K170+000M收费站，发现收费系统故障，栏杆机无法抬杆，已报修并拍照取证。
15时00分 联合交警在K172+000M处开展联合巡查，未发现异常。
下午3点 巡查人员张某某、李某某在K172+500M处发现路侧有堆积物，已记录并通知清理。
15时15分 在K174+000M处进行拒超治超，拒入超限车辆3辆，联合执法，已拍照取证。
15时30分 巡查至K176+000M处，检查采砂区，未发现非法采砂行为。
15时40分 对K177+000M施工路段进行专项巡查，施工标志齐全。
15时45分 对K178+000M至K180+000M路段照明设施进行专项排查，发现高杆灯一盏不亮，已拍照取证。
16时00分 对K182+000M处交安设施进行专项排查，发现标志牌倾斜，已拍照取证。
16时05分 对K181+000M处重点部位进行专项检查，已拍照取证。
16时15分 开展安全隐患排查，发现K184+000M处边坡有裂缝，已拍照取证。
16时30分 汛期巡查，检查K186+000M处排水沟，发现堵塞，已清理并拍照取证。
16时45分 对K188+000M处消防设施进行专项检查，发现灭火器压力不足，已拍照取证。
17时00分 节假日保畅巡查，在K190+000M处疏导车流，现场缓行，拍照取证。
17时15分 路产损坏调查，发现K192+000M处护栏被撞损坏，已拍照取证。
17时20分 巡查至K193+000M处，因大雾能见度低，已开启警示灯，路况正常。
17时30分 到达K194+000M处，进行桥下空间巡查，发现桥下有堆积物，已拍照取证。
17时45分 巡查至K196+000M处，发现桥下空间有违法搭建，已制止并拍照取证。
18时00分 返回A收费管理中心，结束巡查，收车。
18时05分 与晚班巡查人员赵某某、钱某某进行交接班。
18时10分 晚班巡查人员赵某某、钱某某从A收费管理中心出发，往东行方向巡查。
18时20分 巡查至K100+000M处，调头往西行方向巡查。
18时30分 巡查至K101+000M处，掉头往南行方向巡查。
18时40分 巡查至K102+000M处，借道对向车道往北行方向巡查。
18时50分 巡查至K103+000M处，双向巡查，路况正常。
18时55分 到达K164+000M施工点，检查基坑施工，已拍照取证。
19时05分 接报K165+000M处发生车辆故障，前往处理，19时20分处理完毕，未造成路产损坏。
19时25分 返回K164+000M施工点继续检查，19时35分检查完毕，已拍照取证。
19时40分 联合养护在K200+000M处开展联合巡查，未发现异常。
19时50分 联合燃气在K201+000M处开展联合巡查，未发现异常。
20时00分 联合电力在K202+000M处开展联合巡查，未发现异常。
20时10分 联合通信在K203+000M处开展联合巡查，未发现异常。
20时20分 联合铁路在K204+000M处开展联合巡查，未发现异常。
20时30分 联合管廊在K205+000M处开展联合巡查，未发现异常。
23时30分 接报K210+000M处有障碍物，前往处理，01时00分处理完毕。
""".trimIndent()

    private fun parse() = LogParserEngine.parse(SAMPLE, LocalDate.of(2026, 9, 3), zone)

    @Test fun `黄金样本解析出88条事件`() {
        val r = parse()
        assertEquals(88, r.events.size, "89 条输入去重 1 条（上午8点/08时00分）→ 88 条事件")
    }

    @Test fun `无跳过行`() {
        val r = parse()
        assertTrue(r.skippedLines.isEmpty(), "skipped=${r.skippedLines}")
    }

    @Test fun `全局信息如实提取不脱敏`() {
        val r = parse()
        assertEquals("张某某、李某某", r.inspectors)
        assertEquals("王某某", r.recorder)
        assertEquals("粤E00001", r.vehicle)
    }

    @Test fun `首条事件为交接班并完成去重合并`() {
        val r = parse()
        val first = r.events.first()
        assertEquals(1, first.id)
        assertEquals("交接班", first.eventType)
        assertEquals("A收费管理中心", first.location)
        assertEquals(t(first.startTimeMs!!), LocalTime.of(8, 0))
        assertEquals(2, r.events.count { it.eventType == "交接班" })
    }

    @Test fun `前四条按endTime升序`() {
        val r = parse()
        assertEquals("出车", r.events[1].eventType)
        assertEquals("A收费管理中心", r.events[1].location)
        assertEquals("交安设施巡查", r.events[2].eventType)
        assertEquals("K138+700M", r.events[2].location)
        assertEquals("交安设施巡查", r.events[3].eventType)
        assertEquals("K138+800M", r.events[3].location)
    }

    @Test fun `时间相同内容不同的两条8时30分都保留`() {
        val r = parse()
        val e = r.events.filter { it.location == "K138+700M" || it.location == "K138+800M" }
        assertEquals(2, e.size)
    }

    @Test fun `主线巡查C收费站`() {
        val r = parse()
        val e = r.events.first { it.location == "C收费站" }
        assertEquals("主线巡查", e.eventType)
        assertEquals(t(e.startTimeMs!!), LocalTime.of(9, 5))
    }

    @Test fun `交通事故处置两条时间点`() {
        val r = parse()
        val e = r.events.first { it.eventType == "交通事故处置" && it.location == "K145+000M" }
        assertEquals(2, e.timePoints.size)
        assertEquals(t(e.startTimeMs!!), LocalTime.of(10, 0))
        assertEquals(t(e.endTimeMs!!), LocalTime.of(10, 15))
    }

    @Test fun `涉路施工监管时间段`() {
        val r = parse()
        val e = r.events.first { it.location == "K164+000M" && it.eventType == "涉路施工监管" }
        assertEquals(2, e.timePoints.size)
        assertEquals(t(e.startTimeMs!!), LocalTime.of(13, 48))
        assertEquals(t(e.endTimeMs!!), LocalTime.of(14, 48))
    }

    @Test fun `下午3点解析为15点路域环境`() {
        val r = parse()
        val e = r.events.first { it.location == "K172+500M" }
        assertEquals("路域环境巡查", e.eventType)
        assertEquals(t(e.startTimeMs!!), LocalTime.of(15, 0))
    }

    @Test fun `交安设施专项排查组合条件`() {
        val r = parse()
        val e = r.events.first { it.location == "K182+000M" }
        assertEquals("交安设施专项排查", e.eventType)
    }

    @Test fun `汛期专项`() {
        val r = parse()
        val e = r.events.first { it.location == "K186+000M" }
        assertEquals("汛期/恶劣天气专项巡查", e.eventType)
    }

    @Test fun `联合巡查共7条`() {
        val r = parse()
        val joins = r.events.filter { it.eventType == "联合巡查" }
        assertEquals(7, joins.size)
    }

    @Test fun `末条跨日事件闭区间归属`() {
        val r = parse()
        val last = r.events.last()
        assertEquals(88, last.id)
        assertEquals("路面障碍物清理", last.eventType)
        assertEquals("K210+000M", last.location)
        assertEquals(t(last.startTimeMs!!), LocalTime.of(23, 30))
        assertEquals(d(last.startTimeMs!!), LocalDate.of(2026, 9, 3))
        assertEquals(t(last.endTimeMs!!), LocalTime.of(1, 0))
        assertEquals(d(last.endTimeMs!!), LocalDate.of(2026, 9, 4), "跨日回绕 +1 天")
    }

    @Test fun `返回施工点继续检查归涉路施工`() {
        val r = parse()
        val e = r.events.first {
            it.location == "K164+000M" && it.eventType == "涉路施工监管" && t(it.startTimeMs!!) == LocalTime.of(19, 25)
        }
        assertEquals(t(e.endTimeMs!!), LocalTime.of(19, 35))
    }

    @Test fun `样本事件类型全部非空且id连续`() {
        val r = parse()
        assertEquals((1..88).toList(), r.events.map { it.id }, "id 必须 1..88 连续")
        assertTrue(r.events.all { it.eventType.isNotBlank() && it.description.isNotBlank() })
    }

    @Test fun `带方向桩号解析为标准化格式`() {
        val text = """
2026-09-03 08:00 交接班，检查装备完成，安全
2026-09-03 09:00 巡查至K12+300 上行，发现护栏损坏，已拍照取证
2026-09-03 09:10 接报K12+300（下行）处路面障碍物，前往处置
2026-09-03 09:30 上行 K13+500 施工中，已记录
""".trimIndent()
        val r = LogParserEngine.parse(text, LocalDate.of(2026, 9, 3), zone)
        val locs = r.events.map { it.location }
        assertTrue(locs.contains("上行K12+300"), "方向在后：$locs")
        assertTrue(locs.contains("下行K12+300"), "括号方向：$locs")
        assertTrue(locs.contains("上行K13+500"), "方向在前：$locs")
    }

}
