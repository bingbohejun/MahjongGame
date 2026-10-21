package doublemoon.mahjonggame.client.gui.screen

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.client.gui.widget.*
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongRule
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTableBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayload
import doublemoon.mahjonggame.network.sendPayloadToServer
import doublemoon.mahjonggame.registry.ItemRegistry
import doublemoon.mahjonggame.util.plus
import io.github.cottonmc.cotton.gui.client.CottonClientScreen
import io.github.cottonmc.cotton.gui.client.CialloLib
import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription
import io.github.cottonmc.cotton.gui.widget.*
import io.github.cottonmc.cotton.gui.widget.data.VerticalAlignment
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.*

@OnlyIn(Dist.CLIENT)
class MahjongTableWaitingScreen(
    mahjongTable: MahjongTableBlockEntity,
) : CottonClientScreen(MahjongTableGui(mahjongTable)) {
    /**
     * 不取消暂停的话, 通过按按钮导致的更新会卡在下个 tick, 画面会更新不了
     * */
    override fun shouldPause(): Boolean = false

    /**
     * 刷新用, 在 [MahjongTableBlockEntity.readNbt] 的时候刷新,
     * 确保存在 [MahjongTableBlockEntity] 的资料能够同步到 GUI 上
     * */
    fun refresh() {
        (description as MahjongTableGui).refresh()
    }
}

@OnlyIn(Dist.CLIENT)
class MahjongTableGui(
    val mahjongTable: MahjongTableBlockEntity,
) : LightweightGuiDescription() {

    private val client = MinecraftClient.getInstance()
    private val player: ClientPlayerEntity = client.player!!

    //以下来自 mahjongTableBlockEntity
    private val players = mahjongTable.players
    private val playerEntityNames = mahjongTable.playerEntityNames
    private val bots = mahjongTable.bots
    private val ready = mahjongTable.ready
    private val rule get() = mahjongTable.rule

    private val isInThisGame: Boolean
        get() = player.uuidAsString in players
    private val isHost: Boolean
        get() = players[0] == player.uuidAsString
    private val isReady: Boolean
        get() {
            val index = players.indexOf(player.uuidAsString)
            if (index == -1) return false
            return ready[index]
        }
    private val isAllReady: Boolean
        get() = players.withIndex().all { (index, uuid) -> uuid.isEmpty() || mahjongTable.ready[index] }
    private val isFull: Boolean
        get() = playerAmount == mahjongTable.capacity
    private val playerAmount: Int
        get() = players.filterNot { it == "" }.size

    //控件相关
    private val darkMode: Boolean
        get() = CialloLib.isDarkMode()
    private val ruleTexts = mutableListOf<WText>()
    private val playerInfoItems: List<PlayerInfoItem> = List(4) { PlayerInfoItem(it) }

    //Buttons
    private lateinit var joinOrLeave: WButton
    private var readyOrNot: WButton? = null
    private var start: WTooltipButton? = null
    private var addBot: WButton? = null
    private var editRules: WButton? = null
    private val kick: MutableList<WButton?> = mutableListOf(null, null, null)  //最多三个, 房主不能踢自己

    init {
        rootPlainPanel(width = ROOT_WIDTH, height = ROOT_HEIGHT) {
            val icon = item(
                x = BORDER_MARGIN,
                y = BORDER_MARGIN,
                itemStack = ItemRegistry.mahjongTile.defaultStack.also { it.damage = MahjongTile.S1.code })
            label(
                x = icon.x + icon.width + 5,
                y = icon.y,
                height = icon.height,
                text = Text.translatable("$MOD_ID.game.riichi_mahjong"),
                verticalAlignment = VerticalAlignment.CENTER,
            )
            joinOrLeave = button(
                x = BUTTON_JOIN_OR_LEAVE_X,
                y = BUTTON_JOIN_OR_LEAVE_Y,
                width = BUTTON_WIDTH,
                onClick = {
                    if (!isInThisGame) {  //不在这个游戏表示要加入游戏
                        sendPayloadToServer(
                            payload = MahjongTablePayload(
                                behavior = MahjongTableBehavior.JOIN,
                                pos = mahjongTable.pos
                            )
                        )
                    } else {  //在游戏中按表示要离开游戏
                        sendPayloadToServer(
                            payload = MahjongTablePayload(
                                behavior = MahjongTableBehavior.LEAVE,
                                pos = mahjongTable.pos
                            )
                        )
                    }
                }
            )
            playerInfoItems.forEachIndexed { index, playerInfoItem ->
                this.add(
                    playerInfoItem,
                    44,
                    30 + index * playerInfoItem.height,
                    playerInfoItem.width,
                    playerInfoItem.height
                )
            }
            scrollPanel(
                x = playerInfoItems[0].x + playerInfoItems[0].width,
                y = 14,
                width = 140,
                height = ROOT_HEIGHT - 14 * 2
            ) {
                rule.toTexts(
                    color2 = if (!darkMode) Formatting.DARK_GRAY else Formatting.YELLOW,
                    color3 = if (!darkMode) Formatting.DARK_PURPLE else Formatting.GREEN,
                    color4 = if (!darkMode) Formatting.LIGHT_PURPLE else Formatting.AQUA,
                    color5 = if (!darkMode) Formatting.DARK_GRAY else Formatting.WHITE
                ).forEachIndexed { index, text ->
                    val y = if (index > 0) ruleTexts[index - 1].let { it.y + it.height } + 3 else 0
                    ruleTexts += when (index) {
                        3 -> tooltipText( //起始点数
                            x = 0,
                            y = y,
                            width = 132,
                            text = text,
                            tooltip = arrayOf(
                                Text.translatable(
                                    "$MOD_ID.game.starting_points.description",
                                    MahjongRule.MIN_POINTS,
                                    MahjongRule.MAX_POINTS
                                )
                            )
                        )
                        4 -> tooltipText( //一位必要点数
                            x = 0,
                            y = y,
                            width = 132,
                            text = text,
                            tooltip = arrayOf(Text.translatable("$MOD_ID.game.min_points_to_win.description"))
                        )
                        else -> text(x = 0, y = y, width = 132, text = text)
                    }
                }
            }
        }
        refresh()
    }

    /**
     * [MahjongTableBlockEntity] 更新时使用
     * */
    fun refresh() {
        with(joinOrLeave) {
            isEnabled = if (isInThisGame) true else "" in players  //空位的时候, 会以 "" 表示
            label =
                if (!isInThisGame) Text.translatable("$MOD_ID.gui.button.join") else Text.translatable("$MOD_ID.gui.button.leave")
        }
        playerInfoItems.forEach {
            it.entityName = playerEntityNames[it.number]
            it.stringUUID = players[it.number]
            it.isBot = bots[it.number]
            it.ready = ready[it.number]
            it.fresh()
        }
        rule.toTexts(
            color2 = if (!darkMode) Formatting.DARK_GRAY else Formatting.YELLOW,
            color3 = if (!darkMode) Formatting.DARK_PURPLE else Formatting.GREEN,
            color4 = if (!darkMode) Formatting.LIGHT_PURPLE else Formatting.AQUA,
            color5 = if (!darkMode) Formatting.DARK_GRAY else Formatting.WHITE
        ).forEachIndexed { index, text ->
            ruleTexts[index].also {
                it.text = text
                val y = if (index > 0) {
                    ruleTexts[index - 1].let { wText -> wText.y + wText.height } + 3
                } else {
                    0
                }
                it.setLocation(0, y)
            }
        }
        when {
            isHost -> {
                if (start == null) {
                    start = WTooltipButton(
                        label = Text.translatable("$MOD_ID.gui.button.start"),
                        tooltip = arrayOf(Text.translatable("$MOD_ID.gui.tooltip.clickable.when_all_ready"))
                    )
                    (rootPanel as WPlainPanel).add(
                        start,
                        joinOrLeave.x,
                        joinOrLeave.y - BUTTON_HEIGHT - BUTTON_PADDING,
                        BUTTON_WIDTH,
                        BUTTON_HEIGHT
                    )
                }
                start!!.apply {
                    isEnabled = isAllReady && isFull //人满且全部准备才能开始
                    onClick = Runnable {
                        if (isAllReady && isFull && isHost) {
                            sendPayloadToServer(
                                payload = MahjongTablePayload(
                                    behavior = MahjongTableBehavior.START,
                                    pos = mahjongTable.pos
                                )
                            )
                        }
                    }
                }
                if (addBot == null) {
                    addBot = WButton(Text.translatable("$MOD_ID.gui.button.add_bot"))
                    (rootPanel as WPlainPanel).add(
                        addBot,
                        start!!.x,
                        start!!.y - BUTTON_HEIGHT - BUTTON_PADDING,
                        BUTTON_WIDTH,
                        BUTTON_HEIGHT
                    )
                }
                addBot!!.apply {
                    isEnabled = !isFull
                    onClick = Runnable {
                        if (!isFull && isHost) {
                            sendPayloadToServer(
                                payload = MahjongTablePayload(
                                    behavior = MahjongTableBehavior.ADD_BOT,
                                    pos = mahjongTable.pos
                                )
                            )
                        }
                    }
                }
                if (editRules == null) {
                    editRules = WButton(Text.translatable("$MOD_ID.gui.button.edit_rules"))
                    (rootPanel as WPlainPanel).add(
                        editRules,
                        addBot!!.x,
                        addBot!!.y - BUTTON_HEIGHT - BUTTON_PADDING,
                        BUTTON_WIDTH,
                        BUTTON_HEIGHT
                    )
                }
                editRules!!.apply {
                    onClick = Runnable {
                        if (isHost) {
                            sendPayloadToServer(
                                payload = MahjongTablePayload(
                                    behavior = MahjongTableBehavior.OPEN_RULES_EDITOR_GUI,
                                    pos = mahjongTable.pos
                                )
                            )
                        }
                    }
                }
                repeat(3) {  //kick 按钮只有 3 个
                    val playerIndex = it + 1
                    if (kick[it] == null) {
                        val kickText = Text.translatable("$MOD_ID.gui.button.kick")
                        kick[it] = WButton(kickText)
                        val buttonWidth = client.textRenderer.getWidth(kickText) + 12
                        (rootPanel as WPlainPanel).add(
                            kick[it],
                            playerInfoItems[0].x - BUTTON_PADDING - buttonWidth,
                            playerInfoItems[playerIndex].y + 2,
                            buttonWidth,
                            BUTTON_HEIGHT
                        )
                    }
                    kick[it]!!.apply {
                        isEnabled = playerAmount > playerIndex
                        onClick = Runnable {
                            if (isHost) {
                                sendPayloadToServer(
                                    payload = MahjongTablePayload(
                                        behavior = MahjongTableBehavior.KICK,
                                        pos = mahjongTable.pos,
                                        extraData = playerIndex.toString()
                                    )
                                )
                            }
                        }
                    }
                }
                clearNotHostButtons()
            }
            isInThisGame -> {
                if (readyOrNot == null) {
                    readyOrNot = WButton()
                    (rootPanel as WPlainPanel).add(
                        readyOrNot,
                        joinOrLeave.x,
                        joinOrLeave.y - BUTTON_HEIGHT - BUTTON_PADDING,
                        BUTTON_WIDTH,
                        BUTTON_HEIGHT
                    )
                }
                readyOrNot!!.apply {
                    label = if (isReady) {
                        Text.translatable("$MOD_ID.gui.button.not_ready")
                    } else {
                        Text.translatable("$MOD_ID.gui.button.ready")
                    }
                    onClick = Runnable {
                        if (isReady) {
                            sendPayloadToServer(
                                payload = MahjongTablePayload(
                                    behavior = MahjongTableBehavior.NOT_READY,
                                    pos = mahjongTable.pos
                                )
                            )
                        } else {
                            sendPayloadToServer(
                                payload = MahjongTablePayload(
                                    behavior = MahjongTableBehavior.READY,
                                    pos = mahjongTable.pos
                                )
                            )
                        }
                    }
                }
                clearHostButtons()
            }
            else -> {
                clearHostButtons()
                clearNotHostButtons()
            }
        }
    }

    //只有 host 才有的按纽
    private fun clearHostButtons() {
        start?.let { rootPanel.remove(it) }
        start = null
        addBot?.let { rootPanel.remove(it) }
        addBot = null
        editRules?.let { rootPanel.remove(it) }
        editRules = null
        kick.filterNotNull().forEach { rootPanel.remove(it) }
        repeat(3) { kick[it] = null }
    }

    //只有在游戏中非 host 才有的按纽
    private fun clearNotHostButtons() {
        readyOrNot?.let { rootPanel.remove(it) }
        readyOrNot = null
    }

    companion object {
        private const val ROOT_WIDTH = 400
        private const val ROOT_HEIGHT = 200
        private const val BUTTON_WIDTH = 80
        private const val BORDER_MARGIN = 8
        private const val BUTTON_HEIGHT = 20
        private const val BUTTON_JOIN_OR_LEAVE_X = 310
        private const val BUTTON_JOIN_OR_LEAVE_Y = 160
        private const val BUTTON_PADDING = 5
    }

    /**
     * @param number 显示在玩家名称上方, ex: Player 1 (1..4), number 最小从 0 开始算
     * */
    class PlayerInfoItem(
        val number: Int,
    ) : WPlainPanel() {
        /**
         * 对象的实体名称, 不是 displayName, 是 gameProfile.name,
         * 请不要使用 [ServerPlayerEntity.getDisplayName],
         * 麻烦使用 [ServerPlayerEntity.getName], empty 表示空位
         */
        var entityName: String = ""
        var stringUUID: String = ""
        var isBot: Boolean = false
        var ready: Boolean = false
        private val client = MinecraftClient.getInstance()
        private val fontHeight = client.textRenderer.fontHeight
        private val ttPlayer = Text.translatable("$MOD_ID.game.player")
        private val ttHost = Text.translatable("$MOD_ID.game.host")
        private val ttReady = Text.translatable("$MOD_ID.gui.button.ready")
        private val ttNotReady = Text.translatable("$MOD_ID.gui.button.not_ready")
        private val ttEmpty = Text.translatable("$MOD_ID.game.empty")

        init {
            this.setSize(WIDTH, HEIGHT)
        }

        private var face: WWidget =
            faceWidget(x = 0, y = 0, width = 22, height = 22, isBot = isBot, uuid = null, name = null)

        private val name: WLabel = label(
            x = face.x + face.width + 6,
            y = face.y + face.height - fontHeight,
            text = Text.of(entityName)
        )
        private val playerNum = ttPlayer + " ${number + 1}"
        private val numberAndReady: WLabel = label(
            x = name.x,
            y = name.y - fontHeight - 3,
            text = if (number == 0) {
                playerNum + " (" + ttHost + ")"
            } else {
                playerNum
            }
        )

        fun fresh() {
            when {
                isBot -> {
                    if (face !is WBotFace) {
                        this.remove(face)
                        face = botFace(face.x, face.y, face.width, face.height)
                    }
                }
                stringUUID.isNotEmpty() -> {
                    if (face !is WPlayerFace) {
                        this.remove(face)
                        face = playerFace(
                            face.x,
                            face.y,
                            face.width,
                            face.height,
                            uuid = UUID.fromString(stringUUID),
                            name = entityName
                        )
                    } else {
                        (face as WPlayerFace).also {
                            val uuidFromStr = UUID.fromString(stringUUID)
                            if (entityName != it.name && uuidFromStr != it.uuid) {
                                it.setUuidAndName(uuid = uuidFromStr, name = entityName)
                            }
                        }
                    }
                }
                else -> {
                    if (face !is WSprite) {
                        this.remove(face)
                        face = image(
                            face.x,
                            face.y,
                            face.width,
                            face.height,
                            Identifier.of("minecraft:textures/item/structure_void.png")
                        )
                    }
                }
            }
            if (number != 0) {
                numberAndReady.text = if (entityName.isNotEmpty()) {
                    Text.literal("") + playerNum + " (" + (if (ready) ttReady else ttNotReady) + ")"
                } else {
                    playerNum
                }
            }
            name.text = when {
                //机器人的 entityName 就是它的自定义名称 (【电脑】一、【电脑】二 ...)
                entityName.isNotEmpty() -> Text.of(entityName)
                else -> ttEmpty
            }
        }

        companion object {
            const val HEIGHT = 40
            const val WIDTH = 120

            private fun WPlainPanel.faceWidget(
                x: Int,
                y: Int,
                width: Int,
                height: Int,
                isBot: Boolean,
                uuid: UUID?,
                name: String?,
            ): WWidget = when {
                isBot -> botFace(x, y, width, height)
                uuid != null && name != null -> playerFace(x, y, width, height, uuid, name)
                else -> image(x, y, width, height, Identifier.of("minecraft:textures/item/structure_void.png"))
            }
        }
    }
}