package doublemoon.mahjonggame.game

import net.minecraft.particle.ParticleEffect
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos

interface GameBase<T : GamePlayer> {
    /**
     * 游戏内的玩家
     * */
    val players: MutableList<T>

    /**
     * 游戏的名称
     * */
    val name: Text

    /**
     * 游戏所在的世界
     * */
    val world: ServerWorld

    /**
     * 游戏所在的座标
     * */
    val pos: BlockPos

    /**
     * 游戏的状态
     * */
    var status: GameStatus

    /**
     * 让 [player] 加入游戏
     * */
    fun join(player: ServerPlayerEntity)

    /**
     * 让 [player] 离开游戏
     * */
    fun leave(player: ServerPlayerEntity)

    /**
     * 开始游戏
     * */
    fun start(sync: Boolean = true)

    /**
     * 结束游戏
     * */
    fun end(sync: Boolean = true)

    /**
     * 当游戏被破坏时, ex: 破坏游戏方块
     * */
    fun onBreak()

    /**
     * 当玩家登出伺服器
     * */
    fun onPlayerDisconnect(player: ServerPlayerEntity)

    /**
     * 当玩家变更世界
     * */
    fun onPlayerChangedWorld(player: ServerPlayerEntity)

    /**
     * 当 [server] 关闭
     * */
    fun onServerStopping(server: MinecraftServer)

    /**
     * [player] 是否在游戏内
     * */
    fun isInGame(player: ServerPlayerEntity): Boolean {
        return getPlayer(player) != null
    }

    /**
     * [player] 是否是这个游戏的 Host
     * */
    fun isHost(player: ServerPlayerEntity): Boolean

    /**
     * 从 [players] 依照 [player] 取得 [T]
     * */
    fun getPlayer(player: ServerPlayerEntity): T? {
        return players.find { it.entity == player }
    }

    /**
     * 在 [pos] 的位置发出声音
     *
     * @param pos 预设是麻将桌
     * */
    fun playSound(
        world: ServerWorld = this.world,
        pos: BlockPos = this@GameBase.pos,
        soundEvent: SoundEvent,
        category: SoundCategory = SoundCategory.VOICE,
        volume: Float = 1f,
        pitch: Float = 1f
    ) {
        world.playSound(
            null,
            pos,
            soundEvent,
            category,
            volume,
            pitch
        )
    }

    /**
     * 产生粒子效果
     * */
    fun spawnParticles(
        particleEffect: ParticleEffect,
        x: Double = pos.x + 0.5,
        y: Double = pos.y + 1.5,
        z: Double = pos.z + 0.5,
        count: Int = 1,
        deltaX: Double = 0.0,
        deltaY: Double = 0.0,
        deltaZ: Double = 0.0,
        speed: Double = 0.0
    ) {
        world.spawnParticles(particleEffect, x, y, z, count, deltaX, deltaY, deltaZ, speed)
    }

}