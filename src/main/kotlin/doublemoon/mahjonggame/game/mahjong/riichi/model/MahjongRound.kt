package doublemoon.mahjonggame.game.mahjong.riichi.model

import kotlinx.serialization.Serializable

/**
 * 麻将的回合
 *
 * @param wind 场风 (Prevalent Wind)
 * @param round 第几局 (从 0 开始, 3 为结束) (也可以视作 "x"th rotation, 意即转几次的意思)
 * @param honba 本场数
 * */
@Serializable
data class MahjongRound(
    var wind: Wind = Wind.EAST,
    var round: Int = 0,
    var honba: Int = 0
) {
    private var spentRounds = 0 //经过的回合数

    /**
     * 换下个回合会牵扯到 [wind] 场风的问题,
     * 所以 [round] 跟 [wind] 不能直接更改
     * */
    fun nextRound(seatCount: Int = 4) {
        val nextRound = (this.round + 1) % seatCount
        honba = 0
        if (nextRound == 0) { //这个风的最后一局, 换风
            val nextWindNum = (this.wind.ordinal + 1) % 4 //在北 4 局的下回合会回到东风, 但是请别这样搞, 一般是完全不会用到的
            wind = Wind.values()[nextWindNum]
        }
        round = nextRound
        spentRounds++
    }

    /**
     * 经过的回合数 ([spentRounds] + 1) >= 规则中的长度 即是 AllLast,
     * 超过 AllLast 的回合一律都当作 AllLast
     * */
    fun isAllLast(rule: MahjongRule): Boolean = (spentRounds + 1) >= rule.length.rounds
}