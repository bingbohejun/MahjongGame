package doublemoon.mahjonggame.network.mahjong_table

import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.client.gui.screen.MahjongTableWaitingScreen
import doublemoon.mahjonggame.client.gui.screen.RuleEditorScreen
import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.GameStatus
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongRule
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTableBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongBot
import doublemoon.mahjonggame.logger
import doublemoon.mahjonggame.network.ChannelType
import doublemoon.mahjonggame.network.CustomPayloadListener
import doublemoon.mahjonggame.network.sendPayloadToPlayer
import doublemoon.mahjonggame.scheduler.client.ClientScheduler
import doublemoon.mahjonggame.scheduler.server.ServerScheduler
import net.minecraft.client.MinecraftClient
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * 对麻将桌进行操作的
 * */
object MahjongTablePayloadListener : CustomPayloadListener<MahjongTablePayload> {

    override val id: CustomPayload.Id<MahjongTablePayload> = MahjongTablePayload.ID

    override val codec: PacketCodec<RegistryByteBuf, MahjongTablePayload> = MahjongTablePayload.CODEC

    override val channelType: ChannelType = ChannelType.Both

    @OnlyIn(Dist.CLIENT)
    override fun onClientReceive(
        payload: MahjongTablePayload,
        context: IPayloadContext,
    ) {
        val (behavior, pos, _) = payload
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return

        when (behavior) {
            //加入 GUI 参考自 https://github.com/CottonMC/LibGui/wiki/Getting-Started-with-GUIs
            MahjongTableBehavior.OPEN_TABLE_WAITING_GUI -> {  //开启麻将桌等待中的 GUI
                ClientScheduler.scheduleDelayAction {
                    val mahjongTableBlockEntity =
                        world.getBlockEntity(pos) as MahjongTableBlockEntity? ?: return@scheduleDelayAction
                    client.setScreen(MahjongTableWaitingScreen(mahjongTable = mahjongTableBlockEntity))
                }
            }
            MahjongTableBehavior.OPEN_RULES_EDITOR_GUI -> {  //开启规则编辑器 GUI
                ClientScheduler.scheduleDelayAction {
                    val mahjongTableBlockEntity =
                        world.getBlockEntity(pos) as MahjongTableBlockEntity? ?: return@scheduleDelayAction
                    client.setScreen(RuleEditorScreen(mahjongTable = mahjongTableBlockEntity))
                }
            }
            else -> {
            }
        }
    }

    override fun onServerReceive(
        payload: MahjongTablePayload,
        context: IPayloadContext,
    ) {
        val (behavior, pos, extraData) = payload
        val player = context.player() as ServerPlayerEntity
        val world = player.world as ServerWorld? ?: return

        when (behavior) {
            MahjongTableBehavior.JOIN -> syncBlockEntityWithGame(world = world, pos = pos) {  //让玩家加入游戏
                if (status == GameStatus.WAITING) join(player)
            }
            MahjongTableBehavior.LEAVE -> syncBlockEntityWithGame(world = world, pos = pos) { //让玩家离开游戏
                if (status == GameStatus.WAITING) leave(player)
            }
            MahjongTableBehavior.READY -> syncBlockEntityWithGame(world = world, pos = pos) { //让玩家准备游戏
                if (status == GameStatus.WAITING) readyOrNot(player, ready = true)
            }
            MahjongTableBehavior.NOT_READY -> syncBlockEntityWithGame(world = world, pos = pos) { //让玩家取消准备游戏
                if (status == GameStatus.WAITING) readyOrNot(player, ready = false)
            }
            MahjongTableBehavior.START -> { //开始游戏
                val game = GameManager.getGame<MahjongGame>(world, pos)
                if (game != null && game.isHost(player) && game.status == GameStatus.WAITING
                    && game.players.size >= game.seatCapacity //人数必须足够 (三人麻将 3, 四人 4)
                ) {
                    game.start()
                }
            }
            MahjongTableBehavior.KICK -> syncBlockEntityWithGame(world = world, pos = pos) { //让主持剔除玩家
                if (isHost(player) && status == GameStatus.WAITING) kick(index = extraData.toInt())
            }
            MahjongTableBehavior.ADD_BOT -> syncBlockEntityWithGame(world = world, pos = pos) { //新增机器人到游戏
                if (isHost(player) && status == GameStatus.WAITING) addBot()
            }
            MahjongTableBehavior.OPEN_RULES_EDITOR_GUI -> { //玩家请求开启规则编辑器
                val game = GameManager.getGame<MahjongGame>(world, pos)
                if (game != null && game.isHost(player) && game.status == GameStatus.WAITING) {
                    sendPayloadToPlayer(
                        player = player,
                        payload = MahjongTablePayload(
                            behavior = MahjongTableBehavior.OPEN_RULES_EDITOR_GUI,
                            pos = pos,
                            extraData = game.rule.toJsonString()
                        )
                    )
                }
            }
            MahjongTableBehavior.CHANGE_RULE -> syncBlockEntityWithGame(world = world, pos = pos) { //改变规则
                if (isHost(player) && status == GameStatus.WAITING) changeRules(MahjongRule.fromJsonString(extraData))
            }
            MahjongTableBehavior.REQUEST_END -> { //玩家投票要结束游戏 (机器人默认同意, 真人全同意才结束)
                val game = GameManager.getGame<MahjongGame>(world, pos)
                if (game != null && game.status == GameStatus.PLAYING) game.requestEnd(player)
            }
            else -> {
            }
        }
    }

    /**
     * 对 [MahjongTableBlockEntity] 设置改变,
     * 同步伺服端与客户端的麻将桌
     * */
    private fun syncBlockEntityWithGame(
        invokeOnNextTick: Boolean = true,
        world: ServerWorld,
        pos: BlockPos,
        apply: MahjongGame.() -> Unit = {},
    ) {
        val game = GameManager.getGame<MahjongGame>(world, pos) ?: return
        syncBlockEntityWithGame(invokeOnNextTick, game, apply)
    }

    /**
     * 对 [MahjongTableBlockEntity] 设置改变,
     * 同步伺服端与客户端的麻将桌
     *
     * 注意:
     * - [ServerWorld.getBlockEntity] 必须在主线程上调用, 否则会为 null, 这里 [invokeOnNextTick] 会将任务调度到下个 tick 执行
     * - 在伺服器世界中生成 Entity 时, 也要在主线程上调用, 否则可能会有小概率出错
     * */
    fun syncBlockEntityWithGame(
        invokeOnNextTick: Boolean = true,
        game: MahjongGame,
        apply: MahjongGame.() -> Unit = {},
    ) {
        val syncAction = {
            game.apply()
            val world = game.world
            val pos = game.pos
            val blockEntity = world.getBlockEntity(pos) as MahjongTableBlockEntity?
            if (blockEntity != null) {
                syncBlockEntityDataWithGame(blockEntity, game)
            } else {
                logger.error("Cannot find a MahjongTableBlockEntity at (world=$world,pos=$pos)")
            }
        }
        if (invokeOnNextTick) {
            ServerScheduler.scheduleDelayAction { syncAction.invoke() }
        } else {
            syncAction.invoke()
        }
    }

    /**
     * 对 [blockEntity] 同步 [game] 的资料
     * */
    fun syncBlockEntityDataWithGame(
        blockEntity: MahjongTableBlockEntity,
        game: MahjongGame,
    ) {
        with(blockEntity) {
            game.also {
                repeat(4) { i ->
                    this.players[i] = it.players.getOrNull(i)?.uuid ?: ""
                    this.playerEntityNames[i] = it.players.getOrNull(i)?.entity?.name?.string ?: ""
                    this.bots[i] = it.players.getOrNull(i) is MahjongBot
                    this.ready[i] = it.players.getOrNull(i)?.ready ?: false
                    this.seat[i] = it.seat.getOrNull(i)?.uuid ?: ""
                    this.points[i] = it.seat.getOrNull(i)?.points ?: 0
                }
                this.rule = it.rule
                this.playing = it.status == GameStatus.PLAYING
                this.round = it.round
                this.dealer = it.seat.getOrNull(it.round.round)?.uuid ?: ""
                this.tilesLeft = it.wallTilesLeft
                this.endVoteAgreed = it.endVoteAgreedCount
                this.endVoteNeeded = it.endVoteNeededCount
            }
            this.markDirty()
        }
    }
}