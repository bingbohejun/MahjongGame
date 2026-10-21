package doublemoon.mahjonggame.entity

import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongBot
import doublemoon.mahjonggame.registry.EntityTypeRegistry
import net.minecraft.entity.EntityType
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.nbt.NbtCompound
import net.minecraft.world.World

/**
 * 麻将机器人 [MahjongBot] 的实体,
 * (外观是默认的史蒂夫模型)
 * */
class MahjongBotEntity(
    type: EntityType<*> = EntityTypeRegistry.mahjongBot,
    world: World
) : GameEntity(type, world) {

    /**
     * 机器人的外观码, 结算画面里用对应的麻将牌当头像
     * */
    var code: Int
        set(value) = dataTracker.set(CODE, value)
        get() = dataTracker[CODE]

    init {
        isInvulnerable = true
    }

    //可以看到实体才有实体碰撞
    override fun isCollidable(): Boolean = !isInvisible && !isRemoved

    override fun canHit(): Boolean = isCollidable

    //目前机器人实体在麻将桌按下 加入机器人 的时候 (即游戏还没开始的时候) 就存在了,
    override fun autoRemove() {
        if (!world.isClient && isSpawnedByGame && isAlive) {
            val blockEntity = world.getBlockEntity(gameBlockPos)
            if (blockEntity !is MahjongTableBlockEntity) {  //这方块不是麻将桌
                remove(RemovalReason.DISCARDED)
            }
        }
    }

    override fun shouldRenderName(): Boolean = true

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(CODE, 0)
    }

    override fun readCustomDataFromNbt(nbt: NbtCompound) {
        super.readCustomDataFromNbt(nbt)
        code = nbt.getInt("Code")
    }

    override fun writeCustomDataToNbt(nbt: NbtCompound) {
        super.writeCustomDataToNbt(nbt)
        nbt.putInt("Code", code)
    }

    companion object {
        /** 机器人的碰撞箱尺寸, 和原版玩家一样 (外观是史蒂夫模型) */
        const val MAHJONG_BOT_WIDTH = 0.6f
        const val MAHJONG_BOT_HEIGHT = 1.8f

        private val CODE: TrackedData<Int> =
            DataTracker.registerData(MahjongBotEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
    }
}