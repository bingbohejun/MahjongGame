package doublemoon.mahjonggame.entity

import doublemoon.mahjonggame.registry.EntityTypeRegistry
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.data.DataTracker
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/**
 * 这只是一个实体, 并没有实际的模型, 主要是用来制造出椅子的效果,
 *
 * @param sourceBlock [SeatEntity] 所在的那格 [BlockPos], 用来判断椅子有没有被摧毁, 这格方块如果消失了, [SeatEntity] 也会消失
 * @param sitOffsetY 玩家坐在椅子上的偏移量
 * @param stopSitOffsetY 玩家离开椅子的偏移量 (会往椅子上方传送)
 * */
class SeatEntity(
    type: EntityType<SeatEntity> = EntityTypeRegistry.seat,
    world: World,
    private var sourceBlock: BlockPos? = null,
    private val sitOffsetY: Double = 0.3,
    private val stopSitOffsetY: Double = 0.8,
) : Entity(type, world) {

    init {
        isInvisible = true  //将椅子设为隐形的实体
        sourceBlock?.apply { setPos(x + 0.5, y + sitOffsetY, z + 0.5) }
    }

    override fun tick() {
        super.tick()
        if (sourceBlock == null) sourceBlock = blockPos
        if (!world.isClient && (passengerList.isEmpty() || world.isAir(sourceBlock))) remove(RemovalReason.DISCARDED)
    }

    override fun getPosWithYOffset(offset: Float): BlockPos {
        return super.getPosWithYOffset(0.0f)
    }

    override fun initDataTracker(builder: DataTracker.Builder?) {}

    override fun readCustomDataFromNbt(nbt: NbtCompound?) {}

    override fun writeCustomDataToNbt(nbt: NbtCompound?) {}


    override fun updatePassengerForDismount(passenger: LivingEntity): Vec3d =
        Vec3d(blockPos.x + 0.5, blockPos.y + stopSitOffsetY, blockPos.z + 0.5)

    companion object {
        /**
         * 检查 [world] 的位置 [pos] 上能不能生成 [SeatEntity] (即有没有相同的 [SeatEntity] 实体,以及高度足够),
         * 必须在伺服端调用
         *
         * @param pos 要生成 [SeatEntity] 的位置
         * @param height 计算用的高度, 除了 [pos] 往上开始算到 [height] 空间足够的话就表示高度可以, 预设为 2
         * @param checkEntity 检查 [pos] 上否已经有 [SeatEntity] 存在 (即检查是否有人已经坐在这)
         * */
        fun canSpawnAt(
            world: ServerWorld,
            pos: BlockPos,
            height: Int = 2,
            checkEntity: Boolean = true,
        ): Boolean {
            val heightEnough =
                if (height <= 0) true  //不应该出现负数的情况
                else (1..height).all {  //检查上方是否有足够空间
                    val blockState = world.getBlockState(pos.offset(Direction.UP, it))
                    blockState.getCollisionShape(world, pos).isEmpty
                }

            return if (checkEntity) {
                val seatEntitiesAtThisPos =
                    world.getEntitiesByType(EntityTypeRegistry.seat) { it.blockPos == pos && it.isAlive }
                seatEntitiesAtThisPos.isEmpty() && heightEnough //同一格不能有其他 SeatEntity 实体 & 高度足够
            } else {
                heightEnough
            }
        }

        /**
         * 让 [entity] 坐在 [pos] 的位置上,
         * 使用前请使用 [canSpawnAt] 检查,
         * 必须在伺服端调用
         * */
        fun spawnAt(
            world: ServerWorld,
            pos: BlockPos,
            entity: Entity,
            sitOffsetY: Double = 0.3,
            stopSitOffsetY: Double = 0.8,
        ) {
            val seatEntity = SeatEntity(
                world = world,
                sourceBlock = pos,
                sitOffsetY = sitOffsetY,
                stopSitOffsetY = stopSitOffsetY
            )
            world.spawnEntity(seatEntity)
            entity.startRiding(seatEntity, false)
        }

        /**
         * 基本与 [spawnAt] 一样,
         * 会强制让原本坐在 [pos] 上的 [SeatEntity] 上的实体下来,
         * 必须在伺服端调用
         * */
        fun forceSpawnAt(
            world: ServerWorld,
            pos: BlockPos,
            entity: Entity,
            sitOffsetY: Double = 0.3,
            stopSitOffsetY: Double = 0.8,
        ) {
            val seatEntitiesAtThisPos = world.getEntitiesByType(EntityTypeRegistry.seat) {  //取得在 pos 上相同的实体
                it.blockPos == pos && it.isAlive
            }
            seatEntitiesAtThisPos.forEach { it.remove(RemovalReason.DISCARDED) }
            spawnAt(world, pos, entity, sitOffsetY, stopSitOffsetY)
        }
    }
}