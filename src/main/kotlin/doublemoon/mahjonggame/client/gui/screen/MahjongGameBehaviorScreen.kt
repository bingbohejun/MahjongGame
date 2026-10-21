package doublemoon.mahjonggame.client.gui.screen

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.client.gui.widget.*
import doublemoon.mahjonggame.game.mahjong.riichi.model.ClaimTarget
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongGameBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongRule
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import doublemoon.mahjonggame.network.mahjong_game.MahjongGamePayload
import doublemoon.mahjonggame.network.sendPayloadToServer
import doublemoon.mahjonggame.scheduler.client.ClientCountdownTimeHandler
import io.github.cottonmc.cotton.gui.client.CottonClientScreen
import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription
import io.github.cottonmc.cotton.gui.widget.WPlainPanel
import io.github.cottonmc.cotton.gui.widget.data.Color
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import org.mahjong4j.tile.Tile

@OnlyIn(Dist.CLIENT)
class MahjongGameBehaviorScreen(
    behavior: MahjongGameBehavior,
    hands: List<MahjongTile>,
    target: ClaimTarget,
    data: String,
) : CottonClientScreen(MahjongGameBehaviorGui(behavior, hands, target, data)) {
    override fun shouldPause(): Boolean = false
}

@OnlyIn(Dist.CLIENT)
class MahjongGameBehaviorGui(
    private val behavior: MahjongGameBehavior,
    private val hands: List<MahjongTile>,
    private val target: ClaimTarget,
    private val data: String,
) : LightweightGuiDescription() {

    //渲染时间用
    private val basicTime: Int
        get() = ClientCountdownTimeHandler.basicAndExtraTime.first ?: 0
    private val extraTime: Int
        get() = ClientCountdownTimeHandler.basicAndExtraTime.second ?: 0
    private val basicTimeText: String
        get() = if (basicTime > 0) "§a$basicTime" else ""
    private val plusTimeText: String
        get() = if (basicTime > 0 && extraTime > 0) "§e + " else ""
    private val extraTimeText: String
        get() = if (extraTime > 0) "§c$extraTime" else ""
    private val timeText: Text
        get() = Text.of("$basicTimeText$plusTimeText$extraTimeText")

    //渲染麻将牌用
    private val alreadyDrewTile = when (behavior) { //在发送这行为过来时是否有拿牌, 目前只有下面 3 个情况有拿牌
        MahjongGameBehavior.TSUMO,
        MahjongGameBehavior.KYUUSHU_KYUUHAI,
        MahjongGameBehavior.ANKAN_OR_KAKAN,
        MahjongGameBehavior.NUKI,
        -> true
        else -> false
    }
    private val handsWidth =
        TILE_WIDTH * hands.size + TILE_GAP * (hands.size - 1) + if (alreadyDrewTile) TAKEN_TILE_GAP else 0
    private var claimingTile: MahjongTile? = null

    /**
     * 根据 [behavior] 和 [data] 产生的选项的牌,
     * 用来渲染在画面中央, 每个 [BehaviorItem.tiles] 的最后一个都是被鸣的牌 (加杠除外, 加杠是倒数第 2 个是原本碰被鸣的牌, 最后一张是加杠的牌)
     * ([BehaviorItem.target] 只有在 暗杠或加杠 的时候才会有特殊处理, 否则都是 [target], 加杠的对象是原本碰的对象)
     * */
    private val behaviorItemLists =
        buildList {
            when (behavior) {
                MahjongGameBehavior.CHII -> {
                    val dataList = Json.decodeFromString<List<String>>(data)
                    claimingTile = Json.decodeFromString<MahjongTile>(dataList[0])
                    val tilePairsForChii =
                        Json.decodeFromString<List<Pair<MahjongTile, MahjongTile>>>(dataList[1])
                    tilePairsForChii.forEach {
                        val tiles = it.toList().toMutableList().apply { this += claimingTile!! }
                        this += BehaviorItem(behavior, target, tiles)
                    }
                }
                MahjongGameBehavior.PON_OR_CHII -> {
                    val dataList = Json.decodeFromString<List<String>>(data)
                    claimingTile = Json.decodeFromString<MahjongTile>(dataList[0])
                    val tilePairsForChii =
                        Json.decodeFromString<List<Pair<MahjongTile, MahjongTile>>>(dataList[1])
                    val tilePairForPon = Json.decodeFromString<Pair<MahjongTile, MahjongTile>>(dataList[2])
                    this += BehaviorItem(
                        behavior = MahjongGameBehavior.PON,
                        target = target,
                        tiles = tilePairForPon.toList().toMutableList().apply { this += claimingTile!! }
                    )
                    tilePairsForChii.forEach {
                        this += BehaviorItem(
                            behavior = MahjongGameBehavior.CHII,
                            target = target,
                            tiles = it.toList().toMutableList().apply { this += claimingTile!! }
                        )
                    }
                }
                MahjongGameBehavior.PON -> {
                    val dataList = Json.decodeFromString<List<String>>(data)
                    claimingTile = Json.decodeFromString<MahjongTile>(dataList[0])
                    val tilePairForPon = Json.decodeFromString<Pair<MahjongTile, MahjongTile>>(dataList[1])
                    val tiles = tilePairForPon.toList().toMutableList().apply { this += claimingTile!! }
                    this += BehaviorItem(MahjongGameBehavior.PON, target, tiles)
                }
                MahjongGameBehavior.ANKAN_OR_KAKAN -> {
                    val dataList = Json.decodeFromString<List<String>>(data)
                    val canAnkanTiles = Json.decodeFromString<MutableSet<MahjongTile>>(dataList[0])
                    val canKakanTiles = Json.decodeFromString<MutableSet<Pair<MahjongTile, ClaimTarget>>>(dataList[1])
                    val rule = MahjongRule.fromJsonString(dataList[2])
                    val redFiveQuantity = rule.redFive.quantity
                    canAnkanTiles.distinctBy { it.mahjong4jTile.code }  // 避免赤牌重复出现杠的选项
                        .forEach { tile -> //添加暗杠显示的牌
                            val isFiveTile = //是否是 5 的牌
                                tile.mahjong4jTile == MahjongTile.S5.mahjong4jTile || tile.mahjong4jTile == MahjongTile.P5.mahjong4jTile || tile.mahjong4jTile == MahjongTile.M5.mahjong4jTile
                            if (redFiveQuantity == 0 || !isFiveTile) { //没有赤牌 或 不是 5 的牌
                                this += BehaviorItem(MahjongGameBehavior.ANKAN, target, List(4) { tile })
                            } else { //有赤牌,且是 5 的牌
                                val redFiveTile = getRedFiveTile(tile)
                                    ?: throw IllegalStateException("Cannot get red-five tile from $tile")
                                val notRedFiveTile = MahjongTile.entries[redFiveTile.mahjong4jTile.code]
                                val tiles = List(4) { if (it < redFiveQuantity) redFiveTile else notRedFiveTile }
                                this += BehaviorItem(MahjongGameBehavior.ANKAN, target, tiles)
                            }
                        }
                    canKakanTiles.forEach { (tile, oTarget) -> //添加加杠显示的牌
                        val isFiveTile = //是否是 5 的牌
                            tile.mahjong4jTile == MahjongTile.S5.mahjong4jTile || tile.mahjong4jTile == MahjongTile.P5.mahjong4jTile || tile.mahjong4jTile == MahjongTile.M5.mahjong4jTile
                        if (redFiveQuantity == 0 || !isFiveTile) { //没有赤牌 或 不是 5 的牌
                            this += BehaviorItem(MahjongGameBehavior.KAKAN, oTarget, List(4) { tile })
                        } else { //有赤牌,且是 5 的牌
                            val redFiveAmount = redFiveQuantity - if (tile.isRed) 1 else 0
                            val redFiveTile = getRedFiveTile(tile)
                                ?: throw IllegalStateException("Cannot get red-five tile from $tile")
                            val notRedFiveTile = MahjongTile.entries[redFiveTile.mahjong4jTile.code]
                            val tiles = List(4) { index ->
                                if (index < 3) {
                                    if (index < redFiveAmount) redFiveTile else notRedFiveTile  // 如果有赤牌，会将它摆在第一位
                                } else {
                                    tile //最后一张是加杠牌
                                }
                            }
                            this += BehaviorItem(MahjongGameBehavior.KAKAN, oTarget, tiles)
                        }
                    }
                }
                MahjongGameBehavior.MINKAN -> { //能出现明杠一定是同时能杠跟碰 (手上至少有 3 张能与 claimingTile 组成刻子的牌)
                    val dataList = Json.decodeFromString<MutableList<String>>(data)
                    claimingTile = Json.decodeFromString<MahjongTile>(dataList[0])
                    val rule = MahjongRule.fromJsonString(dataList[1])
                    val redFiveQuantity = rule.redFive.quantity
                    val isFiveTile = //是否是 5 的牌
                        claimingTile!!.mahjong4jTile == MahjongTile.S5.mahjong4jTile || claimingTile!!.mahjong4jTile == MahjongTile.P5.mahjong4jTile || claimingTile!!.mahjong4jTile == MahjongTile.M5.mahjong4jTile
                    if (redFiveQuantity == 0 || !isFiveTile) { //没有赤牌 或 不是 5 的牌
                        this += BehaviorItem(MahjongGameBehavior.MINKAN, target, List(4) { claimingTile!! })
                        this += BehaviorItem(MahjongGameBehavior.PON, target, List(3) { claimingTile!! })
                    } else { //有赤牌,且是 5 的牌
                        val redFiveAmount = redFiveQuantity - if (claimingTile!!.isRed) 1 else 0
                        val redFiveTile = getRedFiveTile(claimingTile!!)
                            ?: throw IllegalStateException("Cannot get red-five tile from $claimingTile")
                        val notRedFiveTile = MahjongTile.entries[redFiveTile.mahjong4jTile.code]
                        val tilesForPon = MutableList(2) { if (it < redFiveAmount) redFiveTile else notRedFiveTile }
                        val tilesForKan = tilesForPon.toMutableList()
                        tilesForKan += notRedFiveTile
                        tilesForPon += claimingTile!! //最后一张是加杠牌
                        tilesForKan += claimingTile!!
                        this += BehaviorItem(MahjongGameBehavior.MINKAN, target, tilesForKan)
                        this += BehaviorItem(MahjongGameBehavior.PON, target, tilesForPon)
                    }
                }
                MahjongGameBehavior.RIICHI -> { //立直会显示听的牌
                    val tilePairsForRiichi =
                        Json.decodeFromString<MutableList<Pair<MahjongTile, MutableList<MahjongTile>>>>(data)
                    tilePairsForRiichi.forEach { (tile, _) -> //只会显示可以丢的牌, 并且不会显示无役跟待取以及待取剩几张
                        this += BehaviorItem(MahjongGameBehavior.RIICHI, target, listOf(tile))
                    }
                }
                MahjongGameBehavior.KYUUSHU_KYUUHAI -> { //九种九牌会显示手牌中所有幺九牌
                    val allYaochu = hands.filter { it.mahjong4jTile.isYaochu }
                    this += BehaviorItem(behavior, target, allYaochu)
                }
                MahjongGameBehavior.RON -> {
                    claimingTile = Json.decodeFromString<MahjongTile>(data)
                    this += BehaviorItem(behavior, target, listOf(claimingTile!!))
                }
                MahjongGameBehavior.TSUMO -> { //自摸为手牌最后一张
                    this += BehaviorItem(behavior, target, listOf(hands.last()))
                }
                MahjongGameBehavior.NUKI -> { //抜北: 只显示一张北
                    this += BehaviorItem(behavior, target, listOf(MahjongTile.NORTH))
                }
                else -> { //剩下的结果都不会显示牌
                }
            }
        }

    /**
     * 根据 [tile] 取得对应的 赤牌
     * */
    private fun getRedFiveTile(tile: MahjongTile): MahjongTile? = when (tile) {
        MahjongTile.M5, MahjongTile.M5_RED -> MahjongTile.M5_RED
        MahjongTile.S5, MahjongTile.S5_RED -> MahjongTile.S5_RED
        MahjongTile.P5, MahjongTile.P5_RED -> MahjongTile.P5_RED
        else -> null
    }

    /**
     * 拿来显示手牌提示用, 仅使用 [Tile] 来比对是否为同张牌
     * */
    private val handsHintTiles: List<Tile> = buildList {
        behaviorItemLists.forEach {
            val tiles = it.tiles.toMutableList()
            when (it.behavior) {
                MahjongGameBehavior.ANKAN,
                MahjongGameBehavior.KAKAN,
                MahjongGameBehavior.MINKAN,
                MahjongGameBehavior.RIICHI,
                MahjongGameBehavior.TSUMO,
                MahjongGameBehavior.RON,
                -> this += tiles.last().mahjong4jTile //只传入最后一张牌
                else -> { //剩下的情况传入除了最后一张牌以外的牌
                    tiles.removeLast()
                    tiles.forEach { tile -> this += tile.mahjong4jTile }
                }
            }
        }
    }

    init {
        rootPlainPanel(width = ROOT_WIDTH, height = ROOT_HEIGHT) {
            val timer = dynamicLabel(x = 8, y = 8, text = { timeText.string }) //显示剩余时间用
            button(
                x = ROOT_WIDTH - BUTTON_WIDTH - BORDER_MARGIN,
                y = BORDER_MARGIN,
                width = BUTTON_WIDTH,
                height = BUTTON_HEIGHT,
                label = Text.translatable("$MOD_ID.game.behavior.skip"),
                onClick = {
                    sendPayloadToServer(
                        payload = MahjongGamePayload(behavior = MahjongGameBehavior.SKIP)
                    )
                }
            )
            claimingTileWidget()
            val handsWidgetHeight = TILE_HEIGHT + TILE_GAP + HINT_HEIGHT
            val handsWidget = handsWidget(
                x = 0,
                y = ROOT_HEIGHT - handsWidgetHeight - BORDER_MARGIN,
                width = ROOT_WIDTH,
                height = handsWidgetHeight
            )
            optionsWidget(
                x = 0,
                y = HINT_HEIGHT * 2 + TILE_GAP * 2 + TILE_HEIGHT + TILE_GAP * 6,
                width = ROOT_WIDTH,
                height = ROOT_HEIGHT - timer.height - handsWidget.height
            )
        }
    }

    private fun WPlainPanel.claimingTileWidget(): WMahjongTile? {
        claimingTile?.let { //正中间上方被鸣的牌
            val mtX = (ROOT_WIDTH - TILE_WIDTH) / 2
            val tileWidget =
                mahjongTile(
                    x = mtX,
                    y = HINT_HEIGHT + TILE_GAP + BORDER_MARGIN,
                    width = TILE_WIDTH,
                    height = TILE_HEIGHT,
                    mahjongTile = it
                )
            val target = if (behavior == MahjongGameBehavior.KAKAN) ClaimTarget.SELF else target
            val hWidth = if (target == ClaimTarget.LEFT || target == ClaimTarget.RIGHT) HINT_HEIGHT else TILE_WIDTH
            val hHeight =
                if (target == ClaimTarget.LEFT || target == ClaimTarget.RIGHT) TILE_HEIGHT else HINT_HEIGHT
            colorBlock(
                x = when (target) {
                    ClaimTarget.LEFT -> tileWidget.x - TILE_GAP - hWidth
                    ClaimTarget.RIGHT -> tileWidget.x + TILE_WIDTH + TILE_GAP
                    else -> tileWidget.x
                },
                y = when (target) {
                    ClaimTarget.ACROSS -> tileWidget.y - TILE_GAP - hHeight
                    ClaimTarget.SELF -> tileWidget.y + tileWidget.height + TILE_GAP
                    else -> tileWidget.y
                },
                width = hWidth,
                height = hHeight,
                color = Color.GREEN
            )
            return tileWidget
        } ?: return null
    }

    private fun WPlainPanel.handsWidget(x: Int, y: Int, width: Int, height: Int) =
        plainPanel(x, y, width, height) {
            hands.forEachIndexed { index, tile ->
                val tileX = ((width - handsWidth) / 2
                        + (TILE_WIDTH + TILE_GAP) * index
                        + if (alreadyDrewTile && index == hands.lastIndex) TAKEN_TILE_GAP else 0)
                if (tile.mahjong4jTile in handsHintTiles) {
                    colorBlock(
                        x = tileX,
                        y = TILE_HEIGHT + TILE_GAP,
                        width = TILE_WIDTH,
                        height = HINT_HEIGHT,
                        color = Color.RED_DYE
                    )
                }
                mahjongTile(x = tileX, y = 0, width = TILE_WIDTH, height = TILE_HEIGHT, mahjongTile = tile)
            }
        }


    private fun WPlainPanel.optionsWidget(x: Int, y: Int, width: Int, height: Int) =
        scrollPanel(x, y, width, height) { //算出第一个 x 的偏移, 让选项置中
            val totalWidth = behaviorItemLists.size * OptionItem.WIDTH + (behaviorItemLists.size - 1) * OPTION_GAP
            val offsetX = (width - totalWidth) / 2
            val x0 = if (offsetX > 0) offsetX else 0
            behaviorItemLists.forEachIndexed { index, tilesForOption ->
                val option = OptionItem(tilesForOption, hands, data)
                val optionX = x0 + (OptionItem.WIDTH + OPTION_GAP) * index
                this.add(option, optionX, 0, OptionItem.WIDTH, OptionItem.HEIGHT)
            }
        }

    /**
     * 对应 [behavior] 的牌, 透过 [behaviorItemLists] 处理
     * */
    data class BehaviorItem(
        val behavior: MahjongGameBehavior,
        val target: ClaimTarget,
        val tiles: List<MahjongTile>,
    )

    /**
     * @param behaviorItem 请参考 [BehaviorItem] and [behaviorItemLists]
     * @param data 就是 [MahjongGameBehaviorGui.data], 单纯在立直的时候产生 tooltip 用
     * */
    class OptionItem(
        behaviorItem: BehaviorItem,
        hands: List<MahjongTile>,
        data: String,
    ) : WPlainPanel() {
        private val behavior = behaviorItem.behavior
        private val target = behaviorItem.target
        private val tiles = behaviorItem.tiles

        init {
            setSize(WIDTH, HEIGHT)
            initTiles()
            val tooltip: Array<Text> = when (behavior) {
                MahjongGameBehavior.RIICHI ->
                    buildList<Text> {
                        this += Text.translatable("$MOD_ID.game.machi").formatted(Formatting.RED)
                            .formatted(Formatting.BOLD)
                        val tilePairsForRiichi =
                            Json.decodeFromString<MutableList<Pair<MahjongTile, MutableList<MahjongTile>>>>(data)
                        val pair = tilePairsForRiichi.find { it.first == tiles[0] }
                        val machi = pair!!.second
                            .distinctBy { tile -> tile.toText().string } //去掉重复的选项
                            .sortedBy { tile -> tile.sortOrder } //按照顺序排好
                        this += machi.map { Text.of("§3 - §e${it.toText().string}") }
                    }.toTypedArray()
                MahjongGameBehavior.EXHAUSTIVE_DRAW ->
                    arrayOf(behavior.toText().formatted(Formatting.RED).formatted(Formatting.BOLD))
                else -> arrayOf() //目前剩下的暂时都没有 tooltip
            }
            tooltipButton(
                x = BUTTON_X,
                y = BUTTON_Y,
                width = BUTTON_WIDTH,
                tooltip = tooltip,
                label = when (behavior) {
                    MahjongGameBehavior.MINKAN,
                    MahjongGameBehavior.ANKAN,
                    MahjongGameBehavior.KAKAN,
                    -> MahjongGameBehavior.KAN.toText()
                    MahjongGameBehavior.KYUUSHU_KYUUHAI -> MahjongGameBehavior.EXHAUSTIVE_DRAW.toText()
                    else -> behavior.toText()
                },
                onClick = { //这里 onClick 之后并不会马上关闭 GUI, 会等到 [ClientCountdownTimeHandler] 收到清除时间的数据后, 会自己关
                    when (behavior) {
                        MahjongGameBehavior.CHII -> {
                            val pair = tiles[0] to tiles[1]
                            sendPayloadToServer(
                                payload = MahjongGamePayload(
                                    behavior = behavior,
                                    extraData = Json.encodeToString(pair)
                                )
                            )
                        }
                        MahjongGameBehavior.KAKAN, MahjongGameBehavior.ANKAN -> {
                            sendPayloadToServer(
                                payload = MahjongGamePayload(
                                    behavior = MahjongGameBehavior.ANKAN_OR_KAKAN,
                                    extraData = Json.encodeToString(tiles.last())
                                )
                            )
                        }
                        MahjongGameBehavior.PON, MahjongGameBehavior.MINKAN,
                        MahjongGameBehavior.TSUMO, MahjongGameBehavior.RON,
                        MahjongGameBehavior.KYUUSHU_KYUUHAI,
                        MahjongGameBehavior.NUKI,
                        -> {
                            sendPayloadToServer(
                                payload = MahjongGamePayload(behavior = behavior)
                            )
                        }
                        MahjongGameBehavior.RIICHI -> {
                            sendPayloadToServer(
                                payload = MahjongGamePayload(
                                    behavior = MahjongGameBehavior.RIICHI,
                                    extraData = Json.encodeToString(tiles.first())
                                )
                            )
                        }
                        else -> {
                        }
                    }
                }
            )
        }

        private fun initTiles() {
            if (target == ClaimTarget.SELF || behavior == MahjongGameBehavior.RON) { //一定垂直摆放的牌->鸣牌对象是自己, 或者荣和
                val totalWidth = tiles.size * TILE_WIDTH + (tiles.size - 1) * TILE_GAP
                val x0 = (WIDTH - totalWidth) / 2 //计算这个值是为了让牌能置中
                tiles.forEachIndexed { index, mahjongTile ->
                    val x = x0 + (TILE_WIDTH + TILE_GAP) * index
                    mahjongTile(
                        x = x,
                        y = NORMAL_TILE_Y,
                        width = TILE_WIDTH,
                        height = TILE_HEIGHT,
                        mahjongTile = mahjongTile
                    )
                }
            } else { //如果鸣牌对象不是自己 或者 荣和,一定至少有一张牌是水平
                val atLeastOneHorizontalTiles = tiles.toMutableList()
                val kakanTile = if (behavior == MahjongGameBehavior.KAKAN) { //加杠牌
                    atLeastOneHorizontalTiles.removeLast() //加杠的最后一张牌不加入宽度计算 (因为加杠的牌是往上排, 不是往右排)
                } else null
                val claimingTile = atLeastOneHorizontalTiles.removeLast() //最后一张牌为被鸣的牌, 暂时先移出列表
                atLeastOneHorizontalTiles.sortBy { it.sortOrder } //将牌顺序整理一下
                val claimingTileIndex = when (target) { //计算被鸣的牌应该在的位置
                    ClaimTarget.RIGHT -> 2
                    ClaimTarget.ACROSS -> 1
                    ClaimTarget.LEFT -> 0
                    else -> throw IllegalStateException("在计算麻将动作的选项时, 不可能的情况出现") // else 情况不可能出现, 出现一定是错误
                }
                val claimingTileDirection = when (target) {
                    ClaimTarget.RIGHT, ClaimTarget.ACROSS -> WMahjongTile.TileDirection.RIGHT
                    ClaimTarget.LEFT -> WMahjongTile.TileDirection.LEFT
                    else -> throw IllegalStateException("在计算麻将动作的选项时, 不可能的情况出现")  // else 情况不可能出现, 出现一定是错误
                }
                atLeastOneHorizontalTiles.add(claimingTileIndex, claimingTile) //将被鸣的牌放入应该在的位置
                val totalWidth = ((atLeastOneHorizontalTiles.size - 1) * TILE_WIDTH //直的牌
                        + TILE_HEIGHT // 1 张横的牌
                        + (atLeastOneHorizontalTiles.size - 1) * TILE_GAP)
                val x0 = (WIDTH - totalWidth) / 2 //计算这个值是为了让牌能置中
                val widgets = mutableListOf<WMahjongTile>()
                atLeastOneHorizontalTiles.forEachIndexed { index, tile ->
                    val x = if (index > 0) widgets[index - 1].let { it.x + it.width + TILE_GAP } else x0
                    if (index == claimingTileIndex) { //不能用牌比对, 要用 index 比对, 因为碰跟杠的牌会有重复, 会全都一起水平或一起垂直
                        if (kakanTile != null) { //将加杠牌(即 tiles 最后一张)横放补到被鸣的牌上面
                            mahjongTile(
                                x = x,
                                y = KAKAN_TILE_Y,
                                width = TILE_WIDTH,
                                height = TILE_HEIGHT,
                                mahjongTile = kakanTile,
                                direction = claimingTileDirection
                            )
                        }
                        widgets += mahjongTile(
                            x = x,
                            y = HORIZONTAL_TILE_Y,
                            width = TILE_WIDTH,
                            height = TILE_HEIGHT,
                            mahjongTile = tile,
                            direction = claimingTileDirection
                        )
                    } else {
                        widgets += mahjongTile(
                            x = x,
                            y = NORMAL_TILE_Y,
                            width = TILE_WIDTH,
                            height = TILE_HEIGHT,
                            mahjongTile = tile
                        )
                    }
                }
            }
        }

        companion object {
            private const val KAKAN_TILE_Y = 0
            private const val HORIZONTAL_TILE_Y = KAKAN_TILE_Y + TILE_WIDTH + TILE_GAP
            private const val NORMAL_TILE_Y = HORIZONTAL_TILE_Y + TILE_WIDTH - TILE_HEIGHT
            const val WIDTH = TILE_WIDTH * 4 + TILE_GAP * 3 //宽度是使用 4 张直立牌的宽度
            const val HEIGHT = TILE_WIDTH * 2 + TILE_GAP * 2 + BUTTON_HEIGHT // 2 张横摆的牌 + 按纽的高度
            private const val BUTTON_X = (WIDTH - BUTTON_WIDTH) / 2
            private const val BUTTON_Y = NORMAL_TILE_Y + TILE_HEIGHT + TILE_GAP
        }
    }

    companion object {
        //背景图片的大小
        private const val ROOT_WIDTH = 400
        private const val ROOT_HEIGHT = 200
        private const val BUTTON_WIDTH = 80
        private const val BUTTON_HEIGHT = 20
        private const val TILE_SCALE = 0.5f //这个值要观察一下
        private const val TILE_WIDTH = (48 * TILE_SCALE).toInt()
        private const val TILE_HEIGHT = (64 * TILE_SCALE).toInt()
        private const val TILE_GAP = TILE_WIDTH / 12
        private const val TAKEN_TILE_GAP = TILE_GAP * 3
        private const val OPTION_GAP = TILE_GAP * 12
        private const val HINT_HEIGHT = TILE_GAP * 2
        private const val BORDER_MARGIN = 8
    }
}
