package doublemoon.mahjonggame.game

import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity

interface GamePlayer {

    /**
     * 游戏玩家的实体
     * */
    val entity: Entity

    /**
     * 游戏玩家的显示用名称
     * */
    val displayName: String
        get() = entity.displayName?.string ?: ""

    /**
     * 游戏玩家的名称
     * */
    val name: String
        get() = entity.name.string

    /**
     * 玩家的 uuid 字串
     * */
    val uuid: String
        get() = entity.uuidAsString

    /**
     * 这个玩家是否是真的玩家
     * */
    val isRealPlayer: Boolean
        get() = entity is ServerPlayerEntity
}