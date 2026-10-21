package doublemoon.mahjonggame.game

import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos

object GameManager {

    /**
     * 根据 [BlockPos] 方块的位置, 储存各种游戏
     * */
    val games = mutableListOf<GameBase<*>>()

    /**
     * 判断 [player] 是否在任意游戏中
     * */
    fun isInAnyGame(player: ServerPlayerEntity): Boolean =
        getGameBy(player) != null

    /**
     * 根据 [player] 取得游戏
     * */
    fun getGameBy(player: ServerPlayerEntity): GameBase<*>? =
        games.find { it.getPlayer(player) != null }

    /**
     * 根据 [player] 取得 [games] 里的游戏, 没有就回传 null
     * */
    inline fun <reified T : GameBase<*>> getGame(player: ServerPlayerEntity): T? =
        games.filterIsInstance<T>().find { it.getPlayer(player) != null }

    /**
     * 根据 [world] 和 [pos] 取得 [games] 里的游戏, 没有就回传 null
     * */
    inline fun <reified T : GameBase<*>> getGame(world: ServerWorld, pos: BlockPos): T? =
        games.filterIsInstance<T>().find { it.world == world && it.pos == pos }

    /**
     * 根据 [world] 和 [pos] 取得 [games] 里的游戏, 没有就用 [default] 建立后存放在 [games] 并回传 [default]
     * */
    inline fun <reified T : GameBase<*>> getGameOrDefault(world: ServerWorld, pos: BlockPos, default: T): T =
        getGame(world, pos) ?: run { default.also { games += it } }
}