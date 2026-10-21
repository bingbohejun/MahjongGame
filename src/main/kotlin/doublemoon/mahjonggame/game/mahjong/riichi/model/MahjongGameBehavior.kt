package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.network.mahjong_game.MahjongGamePayloadListener
import doublemoon.mahjonggame.util.TextFormatting
import kotlinx.serialization.Serializable
import net.minecraft.text.MutableText
import net.minecraft.text.Text

/**
 * 麻将游戏行为, 发送数据包 [MahjongGamePayloadListener] 用,
 * 伺服端->玩家端 表示: 询问玩家是否要执行,
 * 玩家端->伺服端 表示: 要执行
 * */
@Serializable
enum class MahjongGameBehavior : TextFormatting {
    CHII,//吃
    PON_OR_CHII,//碰或吃
    PON,//碰
    KAN,//杠
    MINKAN,//明杠(明杠时可以选择要碰还是要杠)
    ANKAN,//暗杠
    ANKAN_OR_KAKAN,//暗杠或加杠
    KAKAN,//加杠
    CHAN_KAN,//抢杠
    RIICHI,//立直
    DOUBLE_RIICHI,//双立直
    RON,//荣和
    TSUMO,//自摸
    KYUUSHU_KYUUHAI,//九种九牌
    NUKI,//抜北 (三麻)

    EXHAUSTIVE_DRAW,//流局
    DISCARD,//丢牌
    SKIP,//跳过
    GAME_START,//游戏开始
    GAME_OVER,//游戏结束
    SCORE_SETTLEMENT,//分数结算画面用
    YAKU_SETTLEMENT,//役结算画面用
    COUNTDOWN_TIME,//倒数时间用
    AUTO_ARRANGE,//询问自动理牌用
    MACHI,//待取(听的牌)
    ;

    val translateKey = "$MOD_ID.game.behavior.${name.lowercase()}"
    override fun toText(): MutableText = Text.translatable(translateKey)
}