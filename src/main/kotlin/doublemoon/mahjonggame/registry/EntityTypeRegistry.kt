package doublemoon.mahjonggame.registry

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.entity.*
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnGroup
import net.minecraft.registry.RegistryKeys
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

//参考: https://neoforged.net/wiki/concepts/registries
object EntityTypeRegistry {

    private val ENTITY_TYPES: DeferredRegister<EntityType<*>> =
        DeferredRegister.create(RegistryKeys.ENTITY_TYPE, MOD_ID)

    // 同 BlockRegistry/ItemRegistry: 1.21.1 注册表在 mod 构造阶段已冻结,
    // EntityType 的实例化必须延后到 RegisterEvent。
    private val seatHolder: DeferredHolder<EntityType<*>, EntityType<SeatEntity>> =
        ENTITY_TYPES.register("seat", Supplier {
            EntityType.Builder.create(::SeatEntity, SpawnGroup.MISC)
                .dimensions(0f, 0f)
                .build("seat")
        })

    private val diceHolder: DeferredHolder<EntityType<*>, EntityType<DiceEntity>> =
        ENTITY_TYPES.register("dice", Supplier {
            EntityType.Builder.create(::DiceEntity, SpawnGroup.MISC)
                .dimensions(DiceEntity.DICE_WIDTH, DiceEntity.DICE_HEIGHT)
                .build("dice")
        })

    private val mahjongTileHolder: DeferredHolder<EntityType<*>, EntityType<MahjongTileEntity>> =
        ENTITY_TYPES.register("mahjong_tile", Supplier {
            EntityType.Builder.create(::MahjongTileEntity, SpawnGroup.MISC)
                .dimensions(MahjongTileEntity.MAHJONG_TILE_WIDTH, MahjongTileEntity.MAHJONG_TILE_HEIGHT)
                .build("mahjong_tile")
        })

    private val mahjongScoringStickHolder: DeferredHolder<EntityType<*>, EntityType<MahjongScoringStickEntity>> =
        ENTITY_TYPES.register("mahjong_scoring_stick", Supplier {
            EntityType.Builder.create(::MahjongScoringStickEntity, SpawnGroup.MISC)
                .dimensions(
                    MahjongScoringStickEntity.MAHJONG_POINT_STICK_WIDTH,
                    MahjongScoringStickEntity.MAHJONG_POINT_STICK_HEIGHT
                ).build("mahjong_scoring_stick")
        })

    private val mahjongBotHolder: DeferredHolder<EntityType<*>, EntityType<MahjongBotEntity>> =
        ENTITY_TYPES.register("mahjong_bot", Supplier {
            EntityType.Builder.create(::MahjongBotEntity, SpawnGroup.MISC)
                .dimensions(MahjongBotEntity.MAHJONG_BOT_WIDTH, MahjongBotEntity.MAHJONG_BOT_HEIGHT)
                .build("mahjong_bot")
        })

    val seat: EntityType<SeatEntity> get() = seatHolder.get()
    val dice: EntityType<DiceEntity> get() = diceHolder.get()
    val mahjongTile: EntityType<MahjongTileEntity> get() = mahjongTileHolder.get()
    val mahjongScoringStick: EntityType<MahjongScoringStickEntity> get() = mahjongScoringStickHolder.get()
    val mahjongBot: EntityType<MahjongBotEntity> get() = mahjongBotHolder.get()

    fun register(bus: IEventBus) {
        ENTITY_TYPES.register(bus)
    }

}
