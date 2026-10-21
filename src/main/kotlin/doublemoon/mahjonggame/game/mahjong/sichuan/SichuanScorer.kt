package doublemoon.mahjonggame.game.mahjong.sichuan

import doublemoon.mahjonggame.game.mahjong.riichi.model.Fuuro
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import org.mahjong4j.hands.Hands
import org.mahjong4j.hands.Kantsu
import org.mahjong4j.hands.Kotsu
import org.mahjong4j.hands.Mentsu
import org.mahjong4j.hands.Shuntsu
import org.mahjong4j.tile.Tile

/**
 * 四川麻将（血战到底）专用的番型与计分器,
 * 和日本麻将的“役 + 符 + 翻”完全是两套东西, 所以独立出来:
 *
 * - 不看日麻的役/符, 只看四川的番型
 * - 番数是“加法”的, 分数是“翻倍”的: 分数 = [BASE_SCORE] × 2^(番-1), 封顶 [CAP_FAN] 番
 * - 每个番型的番数集中在 [fanTable] 里, 要调整规则改那里就好
 *
 * 目前实作的番型:
 * 平胡(1) / 对对胡(2) / 七对(3) / 龙七对(3+根) / 清一色(3) / 带幺九(3) / 金钩钓(3)
 * / 根(每组 +1) / 自摸(+1) / 杠上花(+1) / 海底捞月(+1) / 天胡(+6) / 地胡(+6) / 十八罗汉(+6)
 * */
object SichuanScorer {

    /** 底分: 1 番就是这个分数 */
    const val BASE_SCORE = 500

    /**
     * 番数封顶 (超过就按封顶算),
     * 5 番 = 16 倍 = 每家 8000, 和日麻的满贯差不多, 不至于一局就把点数打爆
     * */
    const val CAP_FAN = 5

    /** 番型 key -> 番数, key 对应语言键 mahjonggame.game.sichuan_fan.<key> */
    private val fanTable = mapOf(
        "pinghu" to 1,       //平胡: 最普通的胡牌
        "duiduihu" to 2,     //对对胡: 全部都是刻子/杠
        "qidui" to 3,        //七对: 7 个对子
        "long_qidui" to 3,   //龙七对: 七对里有 4 张一样的 (根另外算)
        "qingyise" to 3,     //清一色: 只有一门
        "daiyaojiu" to 3,    //带幺九: 每个面子跟将都含 1 或 9
        "jingoudiao" to 3,   //金钩钓: 4 副露 + 单钓将
        "gen" to 1,          //根: 每有一组 4 张相同的牌
        "zimo" to 1,         //自摸
        "gangshanghua" to 1, //杠上花
        "haidi" to 1,        //海底捞月
        "tianhu" to 6,       //天胡
        "dihu" to 6,         //地胡
        "shibaluohan" to 6,  //十八罗汉 (4 个杠)
    )

    /**
     * 一个番型
     * @param key 语言键用的 key
     * @param fan 这个番型的番数
     * */
    data class Fan(val key: String, val fan: Int)

    /**
     * 计分结果
     * @param fans 成立的番型列表 (含重复的“根”)
     * @param fan 番数合计 (未封顶)
     * @param score 每家应付的分数
     * @param capped 是否因为封顶而没有继续翻倍
     * */
    data class Result(
        val fans: List<Fan>,
        val fan: Int,
        val score: Int,
        val capped: Boolean,
    )

    /**
     * 计算一次胡牌
     *
     * @param hands 手牌, 必须包含胡的那张牌
     * @param fuuroList 副露
     * @param winningTile 胡的牌
     * @param isTsumo 是否自摸 (四川自摸限定, 一般都是 true)
     * @param isRinshan 是否杠上开花
     * @param isHaitei 是否海底捞月 (牌山摸完)
     * @param isHeavenly 是否天胡 (庄家第一巡就自摸)
     * @param isDihu 是否地胡 (闲家第一巡就自摸)
     * */
    fun calculate(
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        winningTile: MahjongTile,
        isTsumo: Boolean,
        isRinshan: Boolean = false,
        isHaitei: Boolean = false,
        isHeavenly: Boolean = false,
        isDihu: Boolean = false,
    ): Result {
        val allTiles = buildList {
            addAll(hands)
            fuuroList.forEach { fuuro -> fuuro.tileMjEntities.forEach { add(it.mahjongTile) } }
        }
        //每种牌各有几张 (用来判断七对 / 根)
        val counts = allTiles.groupingBy { it.mahjong4jTile.code }.eachCount()
        val fans = mutableListOf<Fan>()

        //根: 4 张一样的牌, 每组 +1 番
        val roots = counts.count { it.value == 4 }
        //清一色: 所有牌都是同一门
        val isChinitsu = allTiles.isNotEmpty() &&
                allTiles.all { it.mahjong4jTile.type == allTiles.first().mahjong4jTile.type }
        //七对: 没有副露, 每种牌都是偶数张, 且刚好组成 7 个对子
        val isChitoitsu = fuuroList.isEmpty() &&
                counts.isNotEmpty() &&
                counts.values.all { it % 2 == 0 } &&
                counts.values.sumOf { it / 2 } == 7
        //金钩钓: 4 副露 + 手里只剩单钓的将
        val isKinko = fuuroList.size == 4 && hands.size == 2
        //牌型分解 (判断对对胡 / 带幺九用), 分解不出来就是 null
        val comp = decomposition(hands, fuuroList, winningTile)
        val isToitoi = comp != null && comp.allMentsu.isNotEmpty() &&
                comp.allMentsu.all { it is Kotsu || it is Kantsu }
        val isYaochu = comp != null && isAllTerminals(comp.allMentsu, comp.janto?.tile)

        when {
            isChitoitsu -> {
                fans += fanOf(if (roots > 0) "long_qidui" else "qidui")
                if (isChinitsu) fans += fanOf("qingyise")
            }

            isToitoi -> {
                fans += fanOf("duiduihu")
                if (isChinitsu) fans += fanOf("qingyise")
                if (isYaochu) fans += fanOf("daiyaojiu")
            }

            else -> {
                fans += fanOf("pinghu")
                if (isChinitsu) fans += fanOf("qingyise")
                if (isYaochu) fans += fanOf("daiyaojiu")
            }
        }
        if (isKinko) fans += fanOf("jingoudiao")
        //十八罗汉: 4 个杠
        if (fuuroList.count { it.mentsu is Kantsu } >= 4) fans += fanOf("shibaluohan")
        //根: 每组 4 张一样的牌 +1 番 (七对时的根另外体现在龙七对上, 但规则上根是另外算的)
        repeat(roots) { fans += fanOf("gen") }
        if (isTsumo) fans += fanOf("zimo")
        if (isRinshan) fans += fanOf("gangshanghua")
        if (isHaitei) fans += fanOf("haidi")
        if (isHeavenly) fans += fanOf("tianhu")
        if (isDihu) fans += fanOf("dihu")

        val fan = fans.sumOf { it.fan }
        val effectiveFan = fan.coerceIn(1, CAP_FAN)
        //分数: 底分 × 2^(番-1)
        val score = BASE_SCORE shl (effectiveFan - 1)
        return Result(fans = fans, fan = fan, score = score, capped = fan > CAP_FAN)
    }

    /** 从 [fanTable] 取出一个番型 */
    private fun fanOf(key: String): Fan = Fan(key, fanTable.getValue(key))

    /**
     * 把牌型分解成面子, 分解不出来 (不是胡牌型) 回传 null
     * */
    private fun decomposition(
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        winningTile: MahjongTile,
    ): org.mahjong4j.hands.MentsuComp? = runCatching {
        val counts = IntArray(Tile.entries.size) { code -> hands.count { it.mahjong4jTile.code == code } }
        val mjHands = Hands(counts, winningTile.mahjong4jTile, fuuroList.map { it.mentsu })
        mjHands.mentsuCompSet.firstOrNull()
    }.getOrNull()

    /**
     * 带幺九: 每一个面子跟将都含有 1 或 9
     * (顺子只能是 123 或 789, 刻子/杠只能是 111 或 999, 将也只能是 11 或 99)
     * */
    private fun isAllTerminals(mentsuList: List<Mentsu>, janto: Tile?): Boolean {
        if (mentsuList.isEmpty() || janto == null) return false
        if (!janto.hasTerminal()) return false
        return mentsuList.all { it.hasTerminal() }
    }

    private fun Mentsu.hasTerminal(): Boolean {
        val tile = this.tile ?: return false
        return when (this) {
            is Shuntsu -> tile.number == 1 || tile.number == 7 //123 / 789
            else -> tile.number == 1 || tile.number == 9 //111 / 999 (刻子或杠)
        }
    }

    private fun Tile.hasTerminal(): Boolean = number == 1 || number == 9
}
