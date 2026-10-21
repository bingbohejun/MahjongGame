package doublemoon.mahjonggame.item

import doublemoon.mahjonggame.entity.MahjongScoringStickEntity
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemUsageContext
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.TypedActionResult
import net.minecraft.world.World

/**
 * 麻将点棒物品
 *
 * 使用 NBT 之中的 "code" 来控制牌的外观
 * */
class MahjongScoringStick(settings: Settings) : Item(settings) {
    /**
     * 使用右键就会触发,
     * 不会与 [useOnBlock] 一起触发 (应该吧?),
     * 右键改变外观
     * */
    override fun use(world: World, user: PlayerEntity, hand: Hand): TypedActionResult<ItemStack> {
        val itemStack = user.getStackInHand(hand)
        // 仅允许伺服端执行，如果是客户端就直接返回
        if (world.isClient) return TypedActionResult.success(itemStack)

        if (!user.isSneaking) { // 没有蹲下
            val nbt = itemStack.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt() ?: NbtCompound()
            val stickCode = nbt.getInt("code")
            nbt.putInt("code", (stickCode + 1) % 4)
            itemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))
            return TypedActionResult.success(itemStack)
        }
        return TypedActionResult.pass(itemStack)
    }

    /**
     * 对方块互动触发,
     * 蹲下右键放置, 右键改变外观
     * */
    override fun useOnBlock(context: ItemUsageContext): ActionResult {
        val player = context.player ?: return ActionResult.PASS
        // 仅允许伺服端执行，如果是客户端就直接返回
        if (context.world.isClient) return ActionResult.SUCCESS

        val itemStack = context.stack
        val nbt = itemStack.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt() ?: NbtCompound()
        val stickCode = nbt.getInt("code")

        return if (player.isSneaking) { //有蹲下才能放置
            val world = context.world as ServerWorld
            MahjongScoringStickEntity(world = world).apply {
                code = stickCode
                val pos = context.hitPos
                refreshPositionAndAngles(pos.x, pos.y, pos.z, context.playerYaw, 0f)
                world.spawnEntity(this)
            }
            if (!player.abilities.creativeMode) itemStack.decrement(1)
            ActionResult.CONSUME
        } else {  //没蹲下, 改变牌的外观
            nbt.putInt("code", (stickCode + 1) % 4)
            itemStack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))
            ActionResult.SUCCESS
        }
    }
}