package doublemoon.mahjonggame.registry

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import net.minecraft.block.entity.BlockEntityType
import net.minecraft.registry.RegistryKeys
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object BlockEntityTypeRegistry {

    private val BLOCK_ENTITY_TYPES: DeferredRegister<BlockEntityType<*>> =
        DeferredRegister.create(RegistryKeys.BLOCK_ENTITY_TYPE, MOD_ID)

    // 延后到 RegisterEvent 阶段: 需要 BlockRegistry 的方块实例, 且 BlockEntityType
    // 本身的建立同样受注册表冻结限制。注册顺序 Block -> BlockEntityType 已由 MahjongCraft 保证。
    private val mahjongTableHolder: DeferredHolder<BlockEntityType<*>, BlockEntityType<MahjongTableBlockEntity>> =
        BLOCK_ENTITY_TYPES.register("mahjong_table", Supplier {
            BlockEntityType.Builder.create(::MahjongTableBlockEntity, BlockRegistry.mahjongTable).build(null)
        })

    val mahjongTable: BlockEntityType<MahjongTableBlockEntity> get() = mahjongTableHolder.get()

    fun register(bus: IEventBus) {
        BLOCK_ENTITY_TYPES.register(bus)
    }

}
