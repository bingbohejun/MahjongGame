package doublemoon.mahjonggame.event

import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayloadListener
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld

/**
 * 玩家切换维度(世界)事件,
 * 用来切换世界后对游戏进行的处理
 * */
fun onPlayerChangedWorld(player: PlayerEntity, origin: ServerWorld, destination: ServerWorld) {
    if (origin == destination) return
    if (!player.world.isClient && GameManager.isInAnyGame(player as ServerPlayerEntity)) {
        when (val game = GameManager.getGameBy(player) ?: return) {
            is MahjongGame -> {
                MahjongTablePayloadListener.syncBlockEntityWithGame(game = game) {
                    onPlayerChangedWorld(player)
                }
            }
        }
    }
}