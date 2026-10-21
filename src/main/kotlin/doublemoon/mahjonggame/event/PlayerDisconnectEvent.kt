package doublemoon.mahjonggame.event

import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayloadListener
import net.minecraft.server.network.ServerPlayerEntity

/**
 * 玩家登出事件,
 * 用来控制登出后对游戏进行的处理
 * */
fun onPlayerDisconnect(player: ServerPlayerEntity) {
    if (!player.world.isClient && GameManager.isInAnyGame(player)) {
        when (val game = GameManager.getGameBy(player) ?: return) {
            is MahjongGame -> {
                MahjongTablePayloadListener.syncBlockEntityWithGame(game = game) {
                    onPlayerDisconnect(player)
                }
            }
        }
    }
}