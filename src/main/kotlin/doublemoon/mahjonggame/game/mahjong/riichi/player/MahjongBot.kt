package doublemoon.mahjonggame.game.mahjong.riichi.player

import doublemoon.mahjonggame.entity.MahjongBotEntity
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * 麻将机器人,
 * 使用 [MahjongGame.addBot] 的时候就会生成 [entity] 了 (并不会游戏开始才生成)
 * TODO 待玩家功能完成后再回来补全电脑的 AI
 * @param world 预设生成的世界
 * @param pos 预设生成的位置
 * @param gamePos 生成这个机器人的游戏的方块座标,
 * */
class MahjongBot(
    val world: ServerWorld,
    pos: Vec3d,
    gamePos: BlockPos,
) : MahjongPlayerBase() {

    override val entity: MahjongBotEntity = MahjongBotEntity(world = world).apply {
        code = MahjongTile.random().code //随机决定外观
        isSpawnedByGame = true
        gameBlockPos = gamePos
        isInvisible = true //先隐形, 等开始游戏才解除隐形
        refreshPositionAfterTeleport(pos)  //再传送到预设位置上
        world.spawnEntity(this) //最后才在世界上生成
    }

    override var ready: Boolean = true

    override fun teleport(targetWorld: ServerWorld, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        with(entity) {
            if (this.world != targetWorld) {
                //NeoForge 1.21.1 已移除 Entity#moveToWorld / changeDimension, 跨世界传送改用原版的 teleportTo(TeleportTarget)
                teleportTo(
                    net.minecraft.world.TeleportTarget(
                        targetWorld,
                        Vec3d(x, y, z),
                        Vec3d.ZERO,
                        yaw,
                        pitch,
                        net.minecraft.world.TeleportTarget.NO_OP
                    )
                )
            } else {
                this.yaw = yaw
                this.pitch = pitch
                requestTeleport(x, y, z)
            }
        }
    }
}