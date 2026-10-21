package doublemoon.mahjonggame.registry

//名称重复, 另写为 RiichiMahjongTile

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.id
import doublemoon.mahjonggame.item.Dice
import doublemoon.mahjonggame.item.MahjongScoringStick
import doublemoon.mahjonggame.item.MahjongTile
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.item.ItemGroup
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.text.Text
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object ItemRegistry {

    private val ITEMS: DeferredRegister<Item> = DeferredRegister.create(RegistryKeys.ITEM, MOD_ID)
    private val ITEM_GROUPS: DeferredRegister<ItemGroup> = DeferredRegister.create(RegistryKeys.ITEM_GROUP, MOD_ID)

    // 同 BlockRegistry: 1.21.1 的 Item 构造器会建立 intrusive holder,
    // 必须延后到 RegisterEvent 阶段才实例化。
    // BlockItem 依赖 BlockRegistry 的方块, 因此注册顺序必须是 Block -> BlockEntityType -> Item。
    private val diceHolder: DeferredHolder<Item, Dice> =
        ITEMS.register("dice", Supplier { Dice(Item.Settings()) })

    private val mahjongTileHolder: DeferredHolder<Item, MahjongTile> =
        ITEMS.register("mahjong_tile", Supplier {
            MahjongTile(
                Item.Settings().component(
                    DataComponentTypes.CUSTOM_DATA,
                    NbtComponent.of(NbtCompound().also { it.putInt("code", 0) })
                )
            )
        })

    private val mahjongScoringStickHolder: DeferredHolder<Item, MahjongScoringStick> =
        ITEMS.register("mahjong_scoring_stick", Supplier {
            MahjongScoringStick(
                Item.Settings().component(
                    DataComponentTypes.CUSTOM_DATA,
                    NbtComponent.of(NbtCompound().also { it.putInt("code", 0) })
                )
            )
        })
    //考虑加入牌尺

    private val mahjongStoolBlockItemHolder: DeferredHolder<Item, BlockItem> =
        ITEMS.register("mahjong_stool", Supplier { BlockItem(BlockRegistry.mahjongStool, Item.Settings()) })

    private val mahjongTableBlockItemHolder: DeferredHolder<Item, BlockItem> =
        ITEMS.register("mahjong_table", Supplier { BlockItem(BlockRegistry.mahjongTable, Item.Settings()) })

    // 对外 API 与原本一致
    val dice: Dice get() = diceHolder.get()
    val mahjongTile: MahjongTile get() = mahjongTileHolder.get()
    val mahjongScoringStick: MahjongScoringStick get() = mahjongScoringStickHolder.get()
    val mahjongStoolBlockItem: BlockItem get() = mahjongStoolBlockItemHolder.get()
    val mahjongTableBlockItem: BlockItem get() = mahjongTableBlockItemHolder.get()

    private val itemGroupKey: RegistryKey<ItemGroup> = RegistryKey.of(RegistryKeys.ITEM_GROUP, id("group"))

    private val itemGroupHolder: DeferredHolder<ItemGroup, ItemGroup> =
        ITEM_GROUPS.register("group", Supplier {
            ItemGroup.Builder(ItemGroup.Row.TOP, 0)
                .displayName(Text.translatable("itemGroup.$MOD_ID.group"))
                .icon { mahjongTile.defaultStack }
                .entries { _, entries ->
                    entries.add(dice)
                    entries.add(mahjongTile)
                    entries.add(mahjongScoringStick)
                    entries.add(mahjongStoolBlockItem)
                    entries.add(mahjongTableBlockItem)
                }
                .build()
        })

    val itemGroup: ItemGroup get() = itemGroupHolder.get()

    fun register(bus: IEventBus) {
        ITEMS.register(bus)
        ITEM_GROUPS.register(bus)
    }
}
