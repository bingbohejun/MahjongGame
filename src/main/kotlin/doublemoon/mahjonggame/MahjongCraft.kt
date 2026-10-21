package doublemoon.mahjonggame

import doublemoon.mahjonggame.client.registerConfigScreen
import doublemoon.mahjonggame.event.onPlayerChangedWorld
import doublemoon.mahjonggame.event.onPlayerDisconnect
import doublemoon.mahjonggame.network.mahjong_game.MahjongGamePayloadListener
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayloadListener
import doublemoon.mahjonggame.network.mahjong_tile_code.MahjongTileCodePayloadListener
import doublemoon.mahjonggame.registry.*
import doublemoon.mahjonggame.scheduler.server.ServerScheduler
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLLoader
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

const val MOD_ID = "mahjonggame"
val logger: Logger = LogManager.getLogger()  //取得 logger

fun id(path: String): Identifier = Identifier.of(MOD_ID, path)

@Mod(MOD_ID)
class MahjongCraft(
    modEventBus: IEventBus,
    modContainer: ModContainer,
) {

    init {
        // Registry
        // 注册顺序很重要: BlockItem 依赖 Block, BlockEntityType 依赖 Block,
        // DeferredRegister 的监听器按注册顺序触发, 因此必须 Block -> BlockEntityType -> Item。
        BlockRegistry.register(modEventBus)
        BlockEntityTypeRegistry.register(modEventBus)
        ItemRegistry.register(modEventBus)
        EntityTypeRegistry.register(modEventBus)
        SoundRegistry.register(modEventBus)

        // Event (游戏事件走 NeoForge 的全域事件汇流排)
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post::class.java) { event ->
            ServerScheduler.tick(event.server)
        }
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent::class.java) { event ->
            ServerScheduler.onStopping(event.server)
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent::class.java) { event ->
            val player = event.getEntity()
            val server = player.getServer() ?: return@addListener
            onPlayerChangedWorld(
                player = player,
                origin = server.getWorld(event.getFrom()) ?: return@addListener,
                destination = server.getWorld(event.getTo()) ?: return@addListener
            )
        }
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent::class.java) { event ->
            (event.getEntity() as? ServerPlayerEntity)?.let { onPlayerDisconnect(it) }
        }

        // Packet
        modEventBus.addListener(RegisterPayloadHandlersEvent::class.java) { event ->
            val registrar = event.registrar("1")
            MahjongTablePayloadListener.register(registrar)
            MahjongGamePayloadListener.register(registrar)
            MahjongTileCodePayloadListener.register(registrar)
        }

        // Client
        if (FMLLoader.getDist().isClient()) {
            // 在模组列表界面中挂接配置屏幕（取代 Fabric Mod Menu 集成）
            registerConfigScreen(modContainer)
            modEventBus.addListener(FMLClientSetupEvent::class.java) {
                MahjongCraftClient.onInitializeClient()
            }
            modEventBus.addListener(RegisterKeyMappingsEvent::class.java) { event ->
                MahjongCraftClient.registerKeyMappings(event)
            }
            modEventBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
                MahjongCraftClient.registerRenderers(event)
            }
        }
    }
}
