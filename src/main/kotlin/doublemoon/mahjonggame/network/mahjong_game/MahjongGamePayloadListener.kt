package doublemoon.mahjonggame.network.mahjong_game

import doublemoon.mahjonggame.MahjongCraftClient
import doublemoon.mahjonggame.client.gui.widget.WTileHints
import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongGameBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import doublemoon.mahjonggame.game.mahjong.riichi.model.ScoreSettlement
import doublemoon.mahjonggame.game.mahjong.riichi.model.YakuSettlement
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayer
import doublemoon.mahjonggame.network.ChannelType
import doublemoon.mahjonggame.network.CustomPayloadListener
import doublemoon.mahjonggame.network.sendPayloadToPlayer
import doublemoon.mahjonggame.network.sendPayloadToServer
import doublemoon.mahjonggame.scheduler.client.ClientCountdownTimeHandler
import doublemoon.mahjonggame.scheduler.client.OptionalBehaviorHandler
import doublemoon.mahjonggame.scheduler.client.ScoreSettleHandler
import doublemoon.mahjonggame.scheduler.client.YakuSettleHandler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.network.ServerPlayerEntity
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * 对麻将游戏进行操作的
 * */
object MahjongGamePayloadListener : CustomPayloadListener<MahjongGamePayload> {

    override val id: CustomPayload.Id<MahjongGamePayload> = MahjongGamePayload.ID

    override val codec: PacketCodec<RegistryByteBuf, MahjongGamePayload> = MahjongGamePayload.CODEC

    override val channelType: ChannelType = ChannelType.Both

    @OnlyIn(Dist.CLIENT)
    override fun onClientReceive(
        payload: MahjongGamePayload,
        context: IPayloadContext,
    ) {
        val (behavior, hands, target, extraData) = payload

        when (behavior) {
            MahjongGameBehavior.MACHI -> {
                WTileHints.machiOfTarget = Json.decodeFromString(extraData)
            }

            MahjongGameBehavior.COUNTDOWN_TIME -> {
                val times = Json.decodeFromString<Pair<Int?, Int?>>(extraData)
                ClientCountdownTimeHandler.basicAndExtraTime = times
            }

            MahjongGameBehavior.DISCARD -> {
                val skippable = extraData.toBoolean()
                if (skippable && MahjongCraftClient.config.quickActions.autoDrawAndDiscard) { // 自动摸切
                    sendPayloadToServer(
                        payload = MahjongGamePayload(behavior = MahjongGameBehavior.SKIP)
                    )
                }
            }

            MahjongGameBehavior.GAME_START -> {
                MahjongCraftClient.playing = true
            }

            MahjongGameBehavior.GAME_OVER -> {
                MahjongCraftClient.playing = false
                OptionalBehaviorHandler.cancel()
            }

            MahjongGameBehavior.SCORE_SETTLEMENT -> {
                with(MahjongCraftClient.config.quickActions) { //每个回合结束的时候, 重置这三个设定
                    autoCallWin = false
                    noChiiPonKan = false
                    autoDrawAndDiscard = false
                    MahjongCraftClient.saveConfig()
                }
                val settlement = Json.decodeFromString<ScoreSettlement>(extraData)
                ScoreSettleHandler.start(settlement = settlement)
            }

            MahjongGameBehavior.YAKU_SETTLEMENT -> {
                val settlements = Json.decodeFromString<List<YakuSettlement>>(extraData)
                YakuSettleHandler.start(settlementList = settlements)
            }

            MahjongGameBehavior.AUTO_ARRANGE -> {
                sendPayloadToServer(
                    payload = MahjongGamePayload(
                        behavior = behavior,
                        extraData = MahjongCraftClient.config.quickActions.autoArrange.toString()
                    )
                )
            }

            else -> {
                val quickActions = MahjongCraftClient.config.quickActions
                when (behavior) {
                    MahjongGameBehavior.RON, MahjongGameBehavior.TSUMO ->
                        if (quickActions.autoCallWin) { //自动和牌
                            sendPayloadToServer(
                                payload = MahjongGamePayload(behavior = behavior)
                            )
                            return
                        }

                    MahjongGameBehavior.CHII, MahjongGameBehavior.PON_OR_CHII, MahjongGameBehavior.PON,
                    MahjongGameBehavior.ANKAN_OR_KAKAN, MahjongGameBehavior.MINKAN,
                    ->
                        if (quickActions.noChiiPonKan) { //不吃碰杠
                            sendPayloadToServer(
                                payload = MahjongGamePayload(behavior = MahjongGameBehavior.SKIP)
                            )
                            return
                        }

                    else -> {}
                }
                OptionalBehaviorHandler.start(behavior, hands, target, extraData)
            }
        }
    }

    override fun onServerReceive(
        payload: MahjongGamePayload,
        context: IPayloadContext,
    ) {
        val (behavior, _, _, extraData) = payload
        val player = context.player() as ServerPlayerEntity

        val game = GameManager.getGame<MahjongGame>(player) ?: return
        val mjPlayer = game.getPlayer(player) as MahjongPlayer? ?: return

        when (behavior) {
            MahjongGameBehavior.MACHI -> {
                val tile = Json.decodeFromString<MahjongTile>(extraData)
                val machiAndHanOrigin = with(game) { mjPlayer.getMachiAndHan(tile) }
                val machiAndHan = machiAndHanOrigin.keys
                    .map { MahjongTile.entries[it.mahjong4jTile.code] } //过滤赤宝牌的情况
                    .associateWith {
                        machiAndHanOrigin[it] to with(game) {
                            mjPlayer.isFuriten(
                                tile = it,
                                machi = machiAndHanOrigin.keys.toList()
                            )
                        }
                    }

                sendPayloadToPlayer(
                    player = player,
                    payload = MahjongGamePayload(
                        behavior = MahjongGameBehavior.MACHI,
                        extraData = Json.encodeToString(machiAndHan)
                    )
                )
            }

            MahjongGameBehavior.AUTO_ARRANGE -> {
                mjPlayer.autoArrangeHands = extraData.toBoolean()
            }

            in mjPlayer.waitingBehavior -> {
                mjPlayer.behaviorResult = behavior to extraData
            }

            else -> {}
        }
    }
}