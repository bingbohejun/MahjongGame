package doublemoon.mahjonggame.entity

import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtHelper
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

abstract class GameEntity(
    type: EntityType<*>,
    world: World,
) : Entity(type, world) {

    // 没这行选不到实体
    override fun canHit(): Boolean = !isRemoved

    /**
     * 游戏所在的座标,
     * 不是游戏产生的话会存入实体所在的 [blockPos]
     * */
    var gameBlockPos: BlockPos
        set(value) = dataTracker.set(GAME_BLOCK_POS, value)
        get() = dataTracker[GAME_BLOCK_POS]

    /**
     * 是否由游戏产生
     * */
    open var isSpawnedByGame: Boolean
        set(value) = dataTracker.set(SPAWNED_BY_GAME, value)
        get() = dataTracker[SPAWNED_BY_GAME]

    /**
     * 当麻将桌 [MahjongTableBlockEntity] 不存在或者游戏不是开始时自动移除
     * */
    open fun autoRemove() {
        if (!world.isClient && isSpawnedByGame && isAlive) {
            val blockEntity = world.getBlockEntity(gameBlockPos)
            if (blockEntity !is MahjongTableBlockEntity) {  //这方块不是麻将桌
                remove(RemovalReason.DISCARDED)
            } else if (!blockEntity.playing) {  //这方块是麻将桌, 没有在游玩状态
                remove(RemovalReason.DISCARDED)
            }
        }
    }

    override fun tick() {
        super.tick()
        autoRemove()
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        builder.add(GAME_BLOCK_POS, blockPos)
        builder.add(SPAWNED_BY_GAME, false)
    }

    override fun readCustomDataFromNbt(nbt: NbtCompound) {
        gameBlockPos = NbtHelper.toBlockPos(nbt, "GameBlockPos").get()
        isSpawnedByGame = nbt.getBoolean("SpawnedByGame")
    }

    override fun writeCustomDataToNbt(nbt: NbtCompound) {
        nbt.put("GameBlockPos", NbtHelper.fromBlockPos(gameBlockPos))
        nbt.putBoolean("SpawnedByGame", isSpawnedByGame)
    }

    companion object {
        private val GAME_BLOCK_POS: TrackedData<BlockPos> = DataTracker.registerData(
            GameEntity::class.java,
            TrackedDataHandlerRegistry.BLOCK_POS
        )
        private val SPAWNED_BY_GAME: TrackedData<Boolean> =
            DataTracker.registerData(GameEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
    }
}