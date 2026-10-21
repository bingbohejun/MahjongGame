package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.util.TextFormatting
import doublemoon.mahjonggame.util.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * 麻将的规则,
 * 可以进行选择与调整,
 * 目前只有古役不能调整而已,
 *
 * 记得如果要进行 Json 编码到 String, 编码(encode) 预设值(Default) 的时候, 是不会编码输出任何预设值的,
 * 读的时候没读到的值会直接当作预设值处理, 所以基本上没差,
 * 如果真的要编码输出预设值的话, 要进行以下操作:
 * val format = Json { encodeDefaults = true }
 * format.encodeToString("data")
 *
 * @param length 游戏局数, 预设为 半庄
 * @param thinkingTime 思考时间, 预设为 5 + 20
 * @param startingPoints 起始点数, 预设 25000
 * @param minPointsToWin 1位必要点数, 预设 30000
 * @param minimumHan 翻缚, 预设 ONE
 * @param spectate 旁观, 这里的意思在游戏外的玩家能否看到游戏内玩家的手牌 (已经打出去的牌或者副露原本就看得见)
 * @param redFive 赤宝牌数量
 * @param openTanyao 食断
 * @param localYaku 古役,(目前没有加入)
 *
 * @see <a href="https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md">详细 Serializable 对 Json 的操作请看这</a>
 * */
/**
 * 麻将玩法:
 * [RIICHI_4P] 日本麻将四人 (预设),
 * [RIICHI_3P] 日本麻将三人 (三麻, 108 张, 不使用 2-8 万),
 * [SICHUAN] 四川麻将血战到底 (108 张, 只有万筒条, 不能吃)
 * */
enum class GameStyle : TextFormatting {
    RIICHI_4P,
    RIICHI_3P,
    SICHUAN;

    override fun toText() = Text.translatable("$MOD_ID.game.game_style.${name.lowercase()}")
}

@Serializable
data class MahjongRule(
    var gameStyle: GameStyle = GameStyle.RIICHI_4P,
    var length: GameLength = GameLength.TWO_WIND,
    var thinkingTime: ThinkingTime = ThinkingTime.NORMAL,
    var startingPoints: Int = 25000,
    var minPointsToWin: Int = 30000,
    var minimumHan: MinimumHan = MinimumHan.ONE,
    var spectate: Boolean = true,
    var redFive: RedFive = RedFive.NONE,
    var openTanyao: Boolean = true,
    var localYaku: Boolean = false
) {
    //将 MahjongRule 编码成 json 格式的 String
    fun toJsonString(): String = Json.encodeToString(serializer(), this)

    fun toTexts(
        color1: Formatting = Formatting.RED,
        color2: Formatting = Formatting.YELLOW,
        color3: Formatting = Formatting.GREEN,
        color4: Formatting = Formatting.AQUA,
        color5: Formatting = Formatting.WHITE
    ): List<Text> {
        val colon = Text.literal(": ").formatted(color5)
        val gameStyleText =
            (Text.translatable("$MOD_ID.game.game_style") + colon).formatted(color2)
        val rules =
            Text.translatable("$MOD_ID.game.rules").formatted(color1).formatted(Formatting.BOLD)
        val lengthText =
            (Text.translatable("$MOD_ID.game.length") + colon).formatted(color2)
        val thinkingTimeText =
            (Text.translatable("$MOD_ID.game.thinking_time") + colon).formatted(color2)
        val startingPointsText =
            (Text.translatable("$MOD_ID.game.starting_points") + colon).formatted(color2)
        val minPointsToWinText =
            (Text.translatable("$MOD_ID.game.min_points_to_win") + colon).formatted(color2)
        val minimumHanText =
            (Text.translatable("$MOD_ID.game.minimum_han") + colon).formatted(color2)
        val spectateText =
            (Text.translatable("$MOD_ID.game.spectate") + colon).formatted(color2)
        val redFiveText =
            (Text.translatable("$MOD_ID.game.red_five") + colon).formatted(color2)
        val openTanyaoText =
            (Text.translatable("$MOD_ID.game.open_tanyao") + colon).formatted(color2)
        val enable = Text.translatable("$MOD_ID.game.enabled").formatted(color3)
        val disable = Text.translatable("$MOD_ID.game.disabled").formatted(color3)
        val spectateStatus = if (spectate) enable else disable
        val openTanyaoStatus = if (openTanyao) enable else disable
        val second = Text.translatable("$MOD_ID.game.seconds").formatted(color3)
        return listOf(
            rules,
            Text.literal("§3 - ") + gameStyleText + Text.translatable("$MOD_ID.game.game_style.${gameStyle.name.lowercase()}").formatted(color3),
            Text.literal("§3 - ") + lengthText + (length.toText() as MutableText).formatted(color3),
            Text.literal("§3 - ")
                    + thinkingTimeText
                    + Text.literal("${thinkingTime.base}").formatted(color4)
                    + Text.literal(" + ").formatted(color1)
                    + Text.literal("${thinkingTime.extra}").formatted(color4)
                    + " " + second,
            Text.literal("§3 - ") + startingPointsText + Text.literal("$startingPoints").formatted(color3),
            Text.literal("§3 - ") + minPointsToWinText + Text.literal("$minPointsToWin").formatted(color3),
            Text.literal("§3 - ") + minimumHanText + Text.literal("${minimumHan.han}").formatted(color3),
            Text.literal("§3 - ") + spectateText + spectateStatus,
            Text.literal("§3 - ") + redFiveText + Text.literal("${redFive.quantity}").formatted(color3),
            Text.literal("§3 - ") + openTanyaoText + openTanyaoStatus
        ) + if (gameStyle == GameStyle.SICHUAN) listOf(
            //四川麻将的固定规则, 不能调整, 这里只是列出来给玩家看
            Text.literal("§3 - ") + Text.translatable("$MOD_ID.game.sichuan_rules").formatted(color3)
        ) else emptyList()
    }

    companion object {
        //从 json 格式的 String 解码回 MahjongRule
        fun fromJsonString(jsonString: String): MahjongRule = Json.decodeFromString(serializer(), jsonString)

        const val MAX_POINTS = 200000
        const val MIN_POINTS = 100
    }

    /**
     * 麻将游戏长度,
     * 以局为最小单位,
     * 以 "东 1 局" 为 0 开始算, "北 4 局" 为 15
     *
     * @param rounds 局数
     * @param finalRound 真的最后一局, 就算未满 1 位必要点数也必须结束(ex: 西入后的 西 4 局, 只要没连庄, 打完就是结束了)
     * */
    enum class GameLength(
        private val startingWind: Wind,
        val rounds: Int,
        val finalRound: Pair<Wind, Int>
    ) : TextFormatting {
        ONE_GAME(Wind.EAST, 1, Wind.EAST to 3),
        EAST(Wind.EAST, 4, Wind.SOUTH to 3),
        SOUTH(Wind.SOUTH, 4, Wind.WEST to 3),
        TWO_WIND(Wind.EAST, 8, Wind.WEST to 3);
//        FOUR_WIND(Wind.EAST, 16, "game.$MOD_ID.mahjong.length.four_wind");

        /**
         * 取得开始游戏的回合
         * */
        fun getStartingRound(): MahjongRound = MahjongRound(wind = startingWind)

        override fun toText() = Text.translatable("$MOD_ID.game.length.${name.lowercase()}")
    }

    /**
     * 翻缚,
     * 最小 翻(翻) 数限制
     * */
    enum class MinimumHan(val han: Int) : TextFormatting {
        ONE(1),      // 1 翻
        TWO(2),         // 2 翻
        FOUR(4),         // 2 翻
        YAKUMAN(13);    //13 翻(役满限定)

        override fun toText(): Text = Text.of(han.toString())
    }

    /**
     * 麻将的思考时间,
     * 和雀魂的长考时间相当
     * */
    enum class ThinkingTime(
        val base: Int,
        val extra: Int
    ) : TextFormatting {
        VERY_SHORT(3, 5),
        SHORT(5, 10),
        NORMAL(5, 20),
        LONG(60, 0),
        VERY_LONG(300, 0);

        override fun toText(): Text = Text.of("$base + $extra s")
    }

    /**
     * 赤宝牌,
     * 4 张红宝牌是 5筒 2 张,剩下的花色各 1 张
     * */
    enum class RedFive(
        val quantity: Int,
    ) : TextFormatting {
        NONE(0),
        THREE(3),
        FOUR(4);

        override fun toText(): Text = Text.of(quantity.toString())
    }
}