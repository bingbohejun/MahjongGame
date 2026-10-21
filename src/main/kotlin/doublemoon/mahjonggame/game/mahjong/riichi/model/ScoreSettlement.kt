package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongBot
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayerBase
import kotlinx.serialization.Serializable
import net.minecraft.text.Text

/**
 * 结算游戏分数用, 会发给客户端, 用来显示结算分数画面,
 * 并不会显示和处理玩家的手牌, 单纯显示 结算的原因 以及 分数的加减 和 排行,
 *
 * 分数结算画面总共有 8 种情况：流局(5种)、胡、自摸、游戏结束
 * */
@Serializable
data class ScoreSettlement(
    val titleTranslateKey: String,
    val scoreList: List<ScoreItem>
) {
    /**
     * 根据传入的 [scoreList] 进行排序后显示分数排行用的 [RankedScoreItem] 列表,
     * 照得分后总分排名由高至低排列,
     * */
    val rankedScoreList: List<RankedScoreItem> = buildList {
        val origin = scoreList.sortedWith(originalScoreComparator).reversed() //用原始分数排列后倒过来 (即用原始分数降序排列)
        val after = scoreList.sortedWith(totalScoreComparator).reversed() //用总分降序排列后倒过来 (即用总分降序排列)
        after.forEachIndexed { index, playerScore ->
            val rankOrigin = origin.indexOf(playerScore)    //玩家原本的排名
            val rankFloat = if (index < rankOrigin) "↑" else if (index > rankOrigin) "↓" else ""
            val scoreChangeString = playerScore.scoreChange.let { //这边检查一下这个值是不是 0
                when {
                    it == 0 -> "" //如果 == 0->当作空白
                    it > 0 -> "+$it" //如果 > 0->加上 "+" 号
                    else -> "$it"
                }
            }
            this += RankedScoreItem(
                scoreItem = playerScore,
                scoreTotal = playerScore.scoreOrigin + playerScore.scoreChange,
                scoreChangeText = Text.of(scoreChangeString),
                rankFloatText = Text.of(rankFloat)
            )
        }
    }

    companion object {
        private val originalScoreComparator = Comparator<ScoreItem> { o1, o2 ->
            compareValuesBy(o1, o2) { it.scoreOrigin }.let { //比较原始分数
                if (it == 0) { //两个值一样
                    compareValuesBy(o1, o2) { arg -> arg.stringUUID } //比较 UUID
                } else it
            }
        }
        private val totalScoreComparator = Comparator<ScoreItem> { o1, o2 ->
            compareValuesBy(o1, o2) { it.scoreOrigin + it.scoreChange }.let { //比较总分
                if (it == 0) { //两个值一样
                    compareValuesBy(o1, o2) { arg -> arg.stringUUID } //比较 UUID
                } else it
            }
        }
    }
}

@Serializable
data class RankedScoreItem(
    val scoreItem: ScoreItem,
    val scoreTotal: Int,
    val scoreChangeText: Text,
    val rankFloatText: Text
)

@Serializable
data class ScoreItem(
    val displayName: String,
    val stringUUID: String,
    val isRealPlayer: Boolean,
    val botCode: Int = MahjongTile.UNKNOWN.code,
    val scoreOrigin: Int,
    val scoreChange: Int
) {
    constructor(
        mahjongPlayer: MahjongPlayerBase,
        scoreOrigin: Int,
        scoreChange: Int
    ) : this(
        displayName = mahjongPlayer.displayName,
        stringUUID = mahjongPlayer.uuid,
        isRealPlayer = mahjongPlayer.isRealPlayer,
        botCode = if (mahjongPlayer is MahjongBot) mahjongPlayer.entity.code else MahjongTile.UNKNOWN.code,
        scoreOrigin = scoreOrigin,
        scoreChange = scoreChange
    )
}