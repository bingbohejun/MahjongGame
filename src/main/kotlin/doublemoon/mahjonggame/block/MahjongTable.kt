package doublemoon.mahjonggame.block

import com.mojang.serialization.MapCodec
import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.block.enums.MahjongTablePart
import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.GameStatus
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTableBehavior
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayload
import doublemoon.mahjonggame.network.sendPayloadToPlayer
import doublemoon.mahjonggame.registry.BlockEntityTypeRegistry
import doublemoon.mahjonggame.util.boxBySize
import doublemoon.mahjonggame.util.plus
import net.minecraft.block.*
import net.minecraft.block.entity.BlockEntity
import net.minecraft.block.entity.BlockEntityTicker
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemPlacementContext
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager
import net.minecraft.state.property.EnumProperty
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.World

/**
 * 参考自 [BedBlock]
 * */
class MahjongTable(settings: Settings) : BlockWithEntity(settings) {

    init {
        defaultState = stateManager.defaultState.with(PART, MahjongTablePart.BOTTOM_CENTER)
    }

    /**
     * 只有 blockstate 的 [PART] 为 [MahjongTablePart.BOTTOM_CENTER] 那个方块才会渲染
     * */
    override fun getRenderType(state: BlockState): BlockRenderType =
        if (state[PART] == MahjongTablePart.BOTTOM_CENTER) BlockRenderType.MODEL else BlockRenderType.INVISIBLE

    override fun getPlacementState(ctx: ItemPlacementContext): BlockState? {
        val centerPos = ctx.blockPos
        val canPlace = peripheryPos(centerPos).all { ctx.world.getBlockState(it.second).canReplace(ctx) }
        return if (canPlace) defaultState else null
    }

    override fun onPlaced(
        world: World,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        itemStack: ItemStack,
    ) {
        super.onPlaced(world, pos, state, placer, itemStack)
        if (!world.isClient) {
            peripheryPos(pos).forEach { (part, position) -> world.setBlockState(position, state.with(PART, part)) }
            world.updateNeighbors(pos, Blocks.AIR)
            state.updateNeighbors(world, pos, 3)
        }
    }

    override fun onBreak(world: World, pos: BlockPos, state: BlockState, player: PlayerEntity): BlockState {
        if (!world.isClient) {
            val part = state[PART] ?: return super.onBreak(world, pos, state, player)
            val centerPos = getCenterPosByPart(pos, part)
            //确保打掉桌子的其中一格方块就能拆掉整张桌子
            allPos(centerPos).forEach { blockPos ->
                val blockState = world.getBlockState(blockPos)
                if (blockState.block != this) return@forEach
                val blockPart = blockState[PART] ?: return@forEach
                if (blockPart == MahjongTablePart.BOTTOM_CENTER) {  //center 被破坏时会离开游戏
                    GameManager.getGame<MahjongGame>(world = world as ServerWorld, pos = centerPos)?.onBreak()
                }
                if (blockPart == MahjongTablePart.BOTTOM_CENTER && !player.isCreative) {  //让玩家在非创造破坏时, 让中间的桌子掉落
                    world.breakBlock(blockPos, true)
                } else {
                    world.breakBlock(blockPos, false)
                }
            }
        }
        return super.onBreak(world, pos, state, player)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(PART)
    }

    override fun getCodec(): MapCodec<out BlockWithEntity> = createCodec(::MahjongTable)

    override fun onUse(
        state: BlockState,
        world: World,
        pos: BlockPos,
        player: PlayerEntity,
        hit: BlockHitResult,
    ): ActionResult {
        if (!world.isClient) {
            val centerPos = getCenterPosByPart(pos = pos, part = state[PART])
            player as ServerPlayerEntity
            world as ServerWorld
            val game = GameManager.getGame<MahjongGame>(world = world, pos = centerPos) ?: return ActionResult.CONSUME
            if (!GameManager.isInAnyGame(player) || game.isInGame(player)) {
                //如果玩家没有在任何游戏中, 或者玩家在"这个"游戏中
                if (game.status == GameStatus.PLAYING) { //如果游戏已经开始,传讯息->游戏已经开始了
                    player.sendMessage(
                        Text.translatable("$MOD_ID.game.message.already_started").formatted(Formatting.YELLOW),
                        true
                    )
                } else {  // 如果游戏尚未开始, 让玩家开启麻将桌 gui
                    sendPayloadToPlayer(
                        player = player,
                        payload = MahjongTablePayload(
                            behavior = MahjongTableBehavior.OPEN_TABLE_WAITING_GUI,
                            pos = centerPos
                        )
                    )
                }
            } else {  //玩家已经在某个游戏中, 且不是"这个"游戏
                val message = Text.translatable("$MOD_ID.game.message.already_in_a_game")
                    .formatted(Formatting.YELLOW) + " (" + game.name + ")"
                player.sendMessage(message, true)
            }
        }
        return ActionResult.SUCCESS
    }

    override fun createBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state[PART] == MahjongTablePart.BOTTOM_CENTER) MahjongTableBlockEntity(pos, state) else null

    override fun <T : BlockEntity?> getTicker(
        world: World,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = validateTicker(
        type,
        BlockEntityTypeRegistry.mahjongTable
    ) { world1, pos, _, blockEntity -> MahjongTableBlockEntity.tick(world1, pos, blockEntity) }

    override fun getOutlineShape(
        state: BlockState,
        world: BlockView,
        pos: BlockPos,
        context: ShapeContext,
    ): VoxelShape = when (state[PART] ?: MahjongTablePart.BOTTOM_CENTER) {
        MahjongTablePart.BOTTOM_CENTER -> BOTTOM_CENTER_SHAPE
        MahjongTablePart.BOTTOM_EAST -> BOTTOM_EAST_SHAPE
        MahjongTablePart.BOTTOM_WEST -> BOTTOM_WEST_SHAPE
        MahjongTablePart.BOTTOM_SOUTH -> BOTTOM_SOUTH_SHAPE
        MahjongTablePart.BOTTOM_NORTH -> BOTTOM_NORTH_SHAPE
        MahjongTablePart.BOTTOM_SOUTHEAST -> BOTTOM_SOUTHEAST_SHAPE
        MahjongTablePart.BOTTOM_SOUTHWEST -> BOTTOM_SOUTHWEST_SHAPE
        MahjongTablePart.BOTTOM_NORTHEAST -> BOTTOM_NORTHEAST_SHAPE
        MahjongTablePart.BOTTOM_NORTHWEST -> BOTTOM_NORTHWEST_SHAPE
        MahjongTablePart.TOP_CENTER -> TOP_CENTER_SHAPE
        MahjongTablePart.TOP_EAST -> TOP_EAST_SHAPE
        MahjongTablePart.TOP_WEST -> TOP_WEST_SHAPE
        MahjongTablePart.TOP_SOUTH -> TOP_SOUTH_SHAPE
        MahjongTablePart.TOP_NORTH -> TOP_NORTH_SHAPE
        MahjongTablePart.TOP_SOUTHEAST -> TOP_SOUTHEAST_SHAPE
        MahjongTablePart.TOP_SOUTHWEST -> TOP_SOUTHWEST_SHAPE
        MahjongTablePart.TOP_NORTHEAST -> TOP_NORTHEAST_SHAPE
        MahjongTablePart.TOP_NORTHWEST -> TOP_NORTHWEST_SHAPE
    }

    companion object {
        val PART: EnumProperty<MahjongTablePart> = EnumProperty.of("mahjong_table_part", MahjongTablePart::class.java)

        private val BOTTOM_CENTER_SHAPE: VoxelShape

        init { //计算出 BOTTOM_CENTER_SHAPE
            val bottom: VoxelShape = boxBySize(1.0, 0.0, 1.0, 14.0, 2.0, 14.0)
            val pillar: VoxelShape = boxBySize(4.0, 2.0, 4.0, 8.0, 12.0, 8.0)
            val top: VoxelShape = boxBySize(-15.0, 14.0, -15.0, 46.0, 2.0, 46.0)
            val border1: VoxelShape = boxBySize(-16, 14, -15, 1, 3, 47)
            val border2: VoxelShape = boxBySize(-15, 14, 31, 47, 3, 1)
            val border3: VoxelShape = boxBySize(31, 14, -16, 1, 3, 47)
            val border4: VoxelShape = boxBySize(-16, 14, -16, 47, 3, 1)
            BOTTOM_CENTER_SHAPE = VoxelShapes.union(bottom, pillar, top, border1, border2, border3, border4)
        }

        //这里的位置是指相对于 BOTTOM_CENTER_SHAPE 的
        private val BOTTOM_EAST_SHAPE: VoxelShape = BOTTOM_CENTER_SHAPE.offset(-1.0, 0.0, 0.0)
        private val BOTTOM_WEST_SHAPE: VoxelShape = BOTTOM_CENTER_SHAPE.offset(1.0, 0.0, 0.0)
        private val BOTTOM_SOUTH_SHAPE: VoxelShape = BOTTOM_CENTER_SHAPE.offset(0.0, 0.0, -1.0)
        private val BOTTOM_NORTH_SHAPE: VoxelShape = BOTTOM_CENTER_SHAPE.offset(0.0, 0.0, 1.0)
        private val BOTTOM_SOUTHEAST_SHAPE: VoxelShape = BOTTOM_SOUTH_SHAPE.offset(-1.0, 0.0, 0.0)
        private val BOTTOM_SOUTHWEST_SHAPE: VoxelShape = BOTTOM_SOUTH_SHAPE.offset(1.0, 0.0, 0.0)
        private val BOTTOM_NORTHEAST_SHAPE: VoxelShape = BOTTOM_NORTH_SHAPE.offset(-1.0, 0.0, 0.0)
        private val BOTTOM_NORTHWEST_SHAPE: VoxelShape = BOTTOM_NORTH_SHAPE.offset(1.0, 0.0, 0.0)
        private val TOP_CENTER_SHAPE: VoxelShape = BOTTOM_CENTER_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_EAST_SHAPE: VoxelShape = BOTTOM_EAST_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_WEST_SHAPE: VoxelShape = BOTTOM_WEST_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_SOUTH_SHAPE: VoxelShape = BOTTOM_SOUTH_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_NORTH_SHAPE: VoxelShape = BOTTOM_NORTH_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_SOUTHEAST_SHAPE: VoxelShape = BOTTOM_SOUTHEAST_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_SOUTHWEST_SHAPE: VoxelShape = BOTTOM_SOUTHWEST_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_NORTHEAST_SHAPE: VoxelShape = BOTTOM_NORTHEAST_SHAPE.offset(0.0, -1.0, 0.0)
        private val TOP_NORTHWEST_SHAPE: VoxelShape = BOTTOM_NORTHWEST_SHAPE.offset(0.0, -1.0, 0.0)

        /**
         * 取得以 [pos] 为中心, xz 平面周围不含 [pos] 的 8 格方块位置 + xz 平面往偏移 +y 的平面 9 个方块的位置
         * */
        private fun peripheryPos(pos: BlockPos): List<Pair<MahjongTablePart, BlockPos>> = listOf(
            MahjongTablePart.BOTTOM_EAST to pos.east(),
            MahjongTablePart.BOTTOM_WEST to pos.west(),
            MahjongTablePart.BOTTOM_SOUTH to pos.south(),
            MahjongTablePart.BOTTOM_NORTH to pos.north(),
            MahjongTablePart.BOTTOM_NORTHEAST to pos.north().east(),
            MahjongTablePart.BOTTOM_NORTHWEST to pos.north().west(),
            MahjongTablePart.BOTTOM_SOUTHEAST to pos.south().east(),
            MahjongTablePart.BOTTOM_SOUTHWEST to pos.south().west(),
            MahjongTablePart.TOP_CENTER to pos.up(),
            MahjongTablePart.TOP_EAST to pos.east().up(),
            MahjongTablePart.TOP_WEST to pos.west().up(),
            MahjongTablePart.TOP_SOUTH to pos.south().up(),
            MahjongTablePart.TOP_NORTH to pos.north().up(),
            MahjongTablePart.TOP_NORTHEAST to pos.north().east().up(),
            MahjongTablePart.TOP_NORTHWEST to pos.north().west().up(),
            MahjongTablePart.TOP_SOUTHEAST to pos.south().east().up(),
            MahjongTablePart.TOP_SOUTHWEST to pos.south().west().up(),
        )

        /**
         * 取得以 [centerPos] 为中心, xz 平面含 [centerPos] 的 9 格方块位置
         * */
        private fun allPos(centerPos: BlockPos): List<BlockPos> =
            buildList { add(centerPos); addAll(peripheryPos(centerPos).map { it.second }) }

        /**
         * 以 [pos] 加上 [part] 找到麻将桌中心的位置
         * */
        private fun getCenterPosByPart(pos: BlockPos, part: MahjongTablePart): BlockPos = when (part) {
            MahjongTablePart.BOTTOM_CENTER -> pos
            MahjongTablePart.BOTTOM_EAST -> pos.west()
            MahjongTablePart.BOTTOM_WEST -> pos.east()
            MahjongTablePart.BOTTOM_SOUTH -> pos.north()
            MahjongTablePart.BOTTOM_NORTH -> pos.south()
            MahjongTablePart.BOTTOM_SOUTHEAST -> pos.north().west()
            MahjongTablePart.BOTTOM_SOUTHWEST -> pos.north().east()
            MahjongTablePart.BOTTOM_NORTHEAST -> pos.south().west()
            MahjongTablePart.BOTTOM_NORTHWEST -> pos.south().east()
            MahjongTablePart.TOP_CENTER -> pos.down()
            MahjongTablePart.TOP_EAST -> pos.west().down()
            MahjongTablePart.TOP_WEST -> pos.east().down()
            MahjongTablePart.TOP_SOUTH -> pos.north().down()
            MahjongTablePart.TOP_NORTH -> pos.south().down()
            MahjongTablePart.TOP_SOUTHEAST -> pos.north().west().down()
            MahjongTablePart.TOP_SOUTHWEST -> pos.north().east().down()
            MahjongTablePart.TOP_NORTHEAST -> pos.south().west().down()
            MahjongTablePart.TOP_NORTHWEST -> pos.south().east().down()
        }
    }
}