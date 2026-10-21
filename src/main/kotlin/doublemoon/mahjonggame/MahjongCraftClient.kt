package doublemoon.mahjonggame

import doublemoon.mahjonggame.client.ModConfig
import doublemoon.mahjonggame.client.gui.screen.ConfigScreen
import doublemoon.mahjonggame.client.gui.screen.HudPositionEditorScreen
import doublemoon.mahjonggame.client.gui.screen.MahjongCraftHud
import doublemoon.mahjonggame.client.gui.screen.yaku_overview.YakuOverviewScreen
import doublemoon.mahjonggame.client.render.*
import doublemoon.mahjonggame.registry.BlockEntityTypeRegistry
import doublemoon.mahjonggame.registry.EntityTypeRegistry
import doublemoon.mahjonggame.registry.ItemRegistry
import doublemoon.mahjonggame.scheduler.client.ClientScheduler
import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.item.ClampedModelPredicateProvider
import net.minecraft.client.item.ModelPredicateProviderRegistry
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.component.DataComponentTypes
import net.minecraft.util.Identifier
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import doublemoon.mahjonggame.network.sendPayloadToServer
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayload
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTableBehavior
import net.minecraft.util.Hand
import net.minecraft.text.Text
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.event.GameShuttingDownEvent
import org.lwjgl.glfw.GLFW

@OnlyIn(Dist.CLIENT)
object MahjongCraftClient {

    /** 桌面中央“结束”按钮的点击目标 (由方块实体渲染器每帧更新) */
    var endButtonPos: net.minecraft.util.math.BlockPos? = null
    var endButtonCenter: net.minecraft.util.math.Vec3d? = null

    var playing = false //客户端玩家是否在游戏中
        set(value) {
            field = value
            hud?.refresh()
        }
    lateinit var config: ModConfig
        private set
    var hud: MahjongCraftHud? = null
        private set

    //KeyBinding (在 RegisterKeyMappingsEvent 时注册)
    private val configKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.open_config_gui",
        code = GLFW.GLFW_KEY_SEMICOLON,
    )
    val hudPositionEditorKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.open_hud_position_editor",
    )

    val yakuOverviewKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.open_yaku_overview",
    )

    // 左侧「快捷操作」HUD 的四个开关: 对局里用快捷键直接切换 (默认未绑定, 需在设置 → 按键里绑定)
    private val toggleAutoArrangeKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.toggle_auto_arrange",
    )
    private val toggleAutoCallWinKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.toggle_auto_call_win",
    )
    private val toggleNoChiiPonKanKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.toggle_no_chii_pon_kan",
    )
    private val toggleAutoDrawAndDiscardKey: KeyBinding = createKeyBinding(
        translationKey = "key.$MOD_ID.toggle_auto_draw_and_discard",
    )

    /**
     * 对应 Fabric 的 ClientModInitializer#onInitializeClient,
     * 由主模组的 FMLClientSetupEvent 监听器呼叫
     * */
    /**
     * 准心射线是否命中桌面中央的“结束”按钮
     * */
    private fun hitEndButton(): Boolean {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return false
        val center = endButtonCenter ?: return false
        val tablePos = endButtonPos ?: return false
        val blockEntity = client.world?.getBlockEntity(tablePos)
            as? doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
        if (blockEntity?.playing != true) return false
        val eye = player.eyePos
        val look = player.rotationVector
        val toCenter = center.subtract(eye)
        val distance = toCenter.dotProduct(look)
        if (distance <= 0.0 || distance > 6.0) return false
        return eye.add(look.multiply(distance)).distanceTo(center) <= 0.6
    }

    fun onInitializeClient() {
        logger.info("Initializing client")

        // Event
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { tick() }

        // 桌面中央“结束”按钮: 按下使用键且准心命中按钮时, 拦截并发送投票
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered::class.java) { event ->
            if (!event.isUseItem) return@addListener
            if (event.hand != Hand.MAIN_HAND) return@addListener
            if (!hitEndButton()) return@addListener
            event.isCanceled = true
            event.setSwingHand(false)
            val pos = endButtonPos ?: return@addListener
            sendPayloadToServer(
                MahjongTablePayload(
                    behavior = MahjongTableBehavior.REQUEST_END,
                    pos = pos
                )
            )
        }
        NeoForge.EVENT_BUS.addListener(GameShuttingDownEvent::class.java) { ClientScheduler.onStopping() }

        // Model Predicate Provider
        // NeoForge 1.21.1 没有对应的事件, 直接注册进原版的 ModelPredicateProviderRegistry
        val modelPredicateProvider = ClampedModelPredicateProvider { stack, _, _, _ ->
            val code = stack.get(DataComponentTypes.CUSTOM_DATA)?.copyNbt()?.getInt("code") ?: 0
            code / 100f  // 这个值必须介于 0f ~ 1f 之间，所以 MahjongTile.code 除以 100 后，会对应到材质档案之中的 predicate.code 栏位
        }
        ModelPredicateProviderRegistry.register(
            ItemRegistry.mahjongTile,
            Identifier.of("code"),
            modelPredicateProvider
        )
        ModelPredicateProviderRegistry.register(
            ItemRegistry.mahjongScoringStick,
            Identifier.of("code"),
            modelPredicateProvider
        )

        // Config
        AutoConfig.register(ModConfig::class.java, ::GsonConfigSerializer)
        config = AutoConfig.getConfigHolder(ModConfig::class.java).config

        // HUD
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post::class.java) { event ->
            //在第 1 次标题画面初始化后, 再将 hud 初始化
            if (event.screen is TitleScreen && hud == null) hud = MahjongCraftHud()
        }
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Pre::class.java) { hud?.reposition() } //在画面 resize 的时候刷新 hud, 以适配萤幕大小
    }

    /**
     * 注册按键绑定, 由主模组的 RegisterKeyMappingsEvent 监听器呼叫
     * */
    fun registerKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(configKey)
        event.register(hudPositionEditorKey)
        event.register(yakuOverviewKey)
        event.register(toggleAutoArrangeKey)
        event.register(toggleAutoCallWinKey)
        event.register(toggleNoChiiPonKanKey)
        event.register(toggleAutoDrawAndDiscardKey)
    }

    /**
     * 注册实体与方块实体的渲染器, 由主模组的 EntityRenderersEvent.RegisterRenderers 监听器呼叫
     * */
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        // Entity Renderer
        event.registerEntityRenderer(EntityTypeRegistry.dice, ::DiceEntityRenderer)
        event.registerEntityRenderer(EntityTypeRegistry.seat, ::SeatEntityRenderer)
        event.registerEntityRenderer(EntityTypeRegistry.mahjongBot, ::MahjongBotEntityRenderer)
        event.registerEntityRenderer(EntityTypeRegistry.mahjongScoringStick, ::MahjongScoringStickEntityRenderer)
        event.registerEntityRenderer(EntityTypeRegistry.mahjongTile, ::MahjongTileEntityRenderer)

        // BlockEntity Renderer
        event.registerBlockEntityRenderer(BlockEntityTypeRegistry.mahjongTable, ::MahjongTableBlockEntityRenderer)
    }

    fun saveConfig() = AutoConfig.getConfigHolder(ModConfig::class.java).save()

    private fun tick() {
        val client = MinecraftClient.getInstance()
        if (configKey.wasPressed()) client.setScreen(ConfigScreen.build(null))
        if (hudPositionEditorKey.wasPressed()) hud?.also { client.setScreen(HudPositionEditorScreen(it)) }
        if (yakuOverviewKey.wasPressed()) client.setScreen(YakuOverviewScreen())
        // 对局里用快捷键直接切换左侧「快捷操作」HUD 的四个开关
        if (toggleAutoArrangeKey.wasPressed()) flipQuickAction("自动理牌", { it.autoArrange }, { it.autoArrange = it.autoArrange.not() })
        if (toggleAutoCallWinKey.wasPressed()) flipQuickAction("自动和牌", { it.autoCallWin }, { it.autoCallWin = it.autoCallWin.not() })
        if (toggleNoChiiPonKanKey.wasPressed()) flipQuickAction("不吃碰杠", { it.noChiiPonKan }, { it.noChiiPonKan = it.noChiiPonKan.not() })
        if (toggleAutoDrawAndDiscardKey.wasPressed()) flipQuickAction("自动摸切", { it.autoDrawAndDiscard }, { it.autoDrawAndDiscard = it.autoDrawAndDiscard.not() })
        ClientScheduler.tick(client)
    }

    /**
     * 翻转快捷操作里的某个布尔开关, 写回设定档并刷新 HUD
     * @param name 开关的中文名称 (仅用于提示信息)
     * */
    private fun flipQuickAction(
        name: String,
        read: (ModConfig.QuickActions) -> Boolean,
        write: (ModConfig.QuickActions) -> Unit
    ) {
        val qa = config.quickActions
        write(qa)
        saveConfig()
        hud?.refresh()
        // 用 HUD 同样的绿/红前缀让玩家知道当前是开还是关
        val enabled = read(qa)
        val prefix = if (enabled) "§a" else "§c"
        val state = if (enabled) "已开启" else "已关闭"
        MinecraftClient.getInstance().player?.sendMessage(Text.literal(prefix + state + ": " + name), true)
    }

    private fun createKeyBinding(
        translationKey: String,
        type: InputUtil.Type = InputUtil.Type.KEYSYM,
        code: Int = InputUtil.UNKNOWN_KEY.code,
        category: String = "key.category.$MOD_ID.main",
    ): KeyBinding = KeyBinding(translationKey, type, code, category)
}
