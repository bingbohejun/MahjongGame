package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.entity.toMahjongTileList
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongBot
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayerBase
import kotlinx.serialization.Serializable
import org.mahjong4j.yaku.normals.NormalYaku
import org.mahjong4j.yaku.yakuman.Yakuman

/**
 * 四川麻将的一个番型 (传给客户端显示用)
 *
 * @param key 语言键 mahjonggame.game.sichuan_fan.<key>
 * @param fan 番数
 * */
@Serializable
data class SichuanFan(
    val key: String,
    val fan: Int,
)

/**
 * 传给玩家用的,
 *
 * @param displayName 胡牌玩家的 displayName
 * @param uuid 胡牌玩家的 stringUUID
 * @param nagashiMangan 特殊情况, 流局满贯专用
 * @param redFiveCount 红宝牌数量
 * @param riichi 立直, 只要有立直就算, 不管是普通的立直还是双立直
 * @param winningTile 使玩家胡牌的那张牌, 流局满贯为 [MahjongTile.UNKNOWN]
 * @param hands 手牌
 * @param fuuroList 副露列表, 结构为 <是否为暗杠, [MahjongTile] 列表>, 传副露里面的 [MahjongTile] 即可
 * @param doraIndicators 宝牌指示牌
 * @param uraDoraIndicators 里宝牌指示牌
 * @param score 这边只是做显示用, 上面的数字并不是玩家实际得到点数 (ex: 立直棒, 场棒的点数影响)
 * @param botCode 机器人才会用到的代码, 会存放机器人的外观码, 渲染画面用
 * */
@Serializable
data class YakuSettlement(
    val displayName: String,
    val uuid: String,
    val isRealPlayer: Boolean,
    val botCode: Int = MahjongTile.UNKNOWN.code,
    val yakuList: List<NormalYaku>,
    val yakumanList: List<Yakuman>,
    val doubleYakumanList: List<DoubleYakuman>,
    val nagashiMangan: Boolean = false,
    val redFiveCount: Int = 0,
    val riichi: Boolean,
    val winningTile: MahjongTile,
    val hands: List<MahjongTile>,
    val fuuroList: List<Pair<Boolean, List<MahjongTile>>>,
    val doraIndicators: List<MahjongTile>,
    val uraDoraIndicators: List<MahjongTile>,
    val fu: Int,
    val han: Int,
    val score: Int,
    /** 是否是四川麻将的结算 (不看日麻的役/符, 看 [sichuanFans]) */
    val sichuan: Boolean = false,
    /** 四川麻将成立的番型 */
    val sichuanFans: List<SichuanFan> = emptyList(),
    /** 四川麻将是否封顶 */
    val sichuanCapped: Boolean = false,
) {
    constructor(
        mahjongPlayer: MahjongPlayerBase,
        yakuList: List<NormalYaku>,
        yakumanList: List<Yakuman>,
        doubleYakumanList: List<DoubleYakuman>,
        nagashiMangan: Boolean = false,
        redFiveCount: Int = 0,
        winningTile: MahjongTile,
        fuuroList: List<Pair<Boolean, List<MahjongTile>>>,
        doraIndicators: List<MahjongTile>,
        uraDoraIndicators: List<MahjongTile>,
        fu: Int,
        han: Int,
        score: Int,
        sichuan: Boolean = false,
        sichuanFans: List<SichuanFan> = emptyList(),
        sichuanCapped: Boolean = false,
    ) : this(
        displayName = mahjongPlayer.displayName,
        uuid = mahjongPlayer.uuid,
        isRealPlayer = mahjongPlayer.isRealPlayer,
        botCode = if (mahjongPlayer is MahjongBot) mahjongPlayer.entity.code else MahjongTile.UNKNOWN.code,
        yakuList = yakuList,
        yakumanList = yakumanList,
        doubleYakumanList = doubleYakumanList,
        nagashiMangan = nagashiMangan,
        redFiveCount = redFiveCount,
        riichi = mahjongPlayer.riichi || mahjongPlayer.doubleRiichi,
        winningTile = winningTile,
        hands = mahjongPlayer.hands.toMahjongTileList(),
        fuuroList = fuuroList,
        doraIndicators = doraIndicators,
        uraDoraIndicators = uraDoraIndicators,
        fu = fu,
        han = han,
        score = score,
        sichuan = sichuan,
        sichuanFans = sichuanFans,
        sichuanCapped = sichuanCapped,
    )

    companion object {
        /**
         * 流局满贯是特殊情况
         * */
        fun nagashiMangan(
            mahjongPlayer: MahjongPlayerBase,
            doraIndicators: List<MahjongTile>,
            uraDoraIndicators: List<MahjongTile>,
            isDealer: Boolean //是否是庄家
        ): YakuSettlement = YakuSettlement(
            mahjongPlayer = mahjongPlayer,
            yakuList = listOf(),
            yakumanList = listOf(),
            doubleYakumanList = listOf(),
            nagashiMangan = true,
            winningTile = MahjongTile.UNKNOWN,
            fuuroList = listOf(),
            doraIndicators = doraIndicators,
            uraDoraIndicators = uraDoraIndicators,
            fu = 0,
            han = 0,
            score = if (isDealer) 12000 else 8000 // 满贯 8000 (庄家 12000) 点
        )

        /**
         * 无役, 判断是否听牌用
         * */
        val NO_YAKU = YakuSettlement(
            displayName = "",
            uuid = "",
            isRealPlayer = false,
            botCode = MahjongTile.UNKNOWN.code,
            yakuList = emptyList(),
            yakumanList = emptyList(),
            doubleYakumanList = emptyList(),
            nagashiMangan = false,
            redFiveCount = 0,
            riichi = false,
            winningTile = MahjongTile.UNKNOWN,
            hands = emptyList(),
            fuuroList = emptyList(),
            doraIndicators = emptyList(),
            uraDoraIndicators = emptyList(),
            fu = 0,
            han = 0,
            score = 0
        )
    }
}