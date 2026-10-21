package doublemoon.mahjonggame.registry

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.block.MahjongStool
import doublemoon.mahjonggame.block.MahjongTable
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object BlockRegistry {

    private val BLOCKS: DeferredRegister<Block> = DeferredRegister.create(RegistryKeys.BLOCK, MOD_ID)

    // 1.21.1 起 Block/Item 的构造器会向 BuiltInRegistries 建立 intrusive holder
    // (SimpleRegistry.createEntry), 而 mod 构造阶段原版注册表已经冻结,
    // 因此实例化必须延后到 RegisterEvent 阶段(此时 NeoForge 会暂时解冻),
    // 不能像 Fabric 1.20.6 那样在 object 初始化时就 new 出来。
    private val mahjongStoolHolder: DeferredHolder<Block, MahjongStool> =
        BLOCKS.register("mahjong_stool", Supplier {
            MahjongStool(
                AbstractBlock.Settings.copy(Blocks.OAK_WOOD)
                    .pistonBehavior(PistonBehavior.DESTROY)
                    .nonOpaque()
                    .strength(0.3f)
                    .sounds(BlockSoundGroup.WOOL)
            )
        })

    private val mahjongTableHolder: DeferredHolder<Block, MahjongTable> =
        BLOCKS.register("mahjong_table", Supplier {
            MahjongTable(
                AbstractBlock.Settings.copy(Blocks.OAK_WOOD)
                    .pistonBehavior(PistonBehavior.IGNORE)
                    .nonOpaque()
                    .strength(0.7f)
                    .sounds(BlockSoundGroup.WOOL)
            )
        })

    // 对外 API 与原本一致; 注册完成后才解析, 呼叫端无须改动
    val mahjongStool: MahjongStool get() = mahjongStoolHolder.get()
    val mahjongTable: MahjongTable get() = mahjongTableHolder.get()

    fun register(bus: IEventBus) {
        BLOCKS.register(bus)
    }
}
