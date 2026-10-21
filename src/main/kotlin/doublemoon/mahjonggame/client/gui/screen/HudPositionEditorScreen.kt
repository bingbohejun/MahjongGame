package doublemoon.mahjonggame.client.gui.screen

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.MahjongCraftClient
import doublemoon.mahjonggame.client.HudAttribute
import doublemoon.mahjonggame.client.gui.widget.WDraggablePlainPanel
import doublemoon.mahjonggame.client.gui.widget.rootPlainPanel
import io.github.cottonmc.cotton.gui.client.BackgroundPainter
import io.github.cottonmc.cotton.gui.client.CottonClientScreen
import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription
import io.github.cottonmc.cotton.gui.widget.WText
import io.github.cottonmc.cotton.gui.widget.data.Color
import io.github.cottonmc.cotton.gui.widget.data.HorizontalAlignment
import io.github.cottonmc.cotton.gui.widget.data.VerticalAlignment
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

@OnlyIn(Dist.CLIENT)
class HudPositionEditorScreen(hud: MahjongCraftHud) : CottonClientScreen(HudPositionEditorGui(hud)) {
    override fun init() {
        super.init()
        (description as HudPositionEditorGui).reposition(width, height)
    }

    override fun close() {
        super.close()
        MahjongCraftClient.hud?.reposition()
    }
}

@OnlyIn(Dist.CLIENT)
class HudPositionEditorGui(
    private val hud: MahjongCraftHud
) : LightweightGuiDescription() {
    private val window = MinecraftClient.getInstance().window
    private val config = MahjongCraftClient.config
    private val quickActionsText = createCenteredTextWidget(Text.translatable("config.$MOD_ID.quick_actions"))
    private val quickActions = WDraggablePlainPanel(color = config.quickActions.hudAttribute.backgroundColor) { x, y ->
        config.quickActions.hudAttribute.setPosition(x, y)
        MahjongCraftClient.saveConfig()
    }.also { it.add(quickActionsText, 0, 0, it.width, it.height) }
    private val tileHintsText = createCenteredTextWidget(Text.translatable("config.$MOD_ID.tile_hints"))
    private val tileHints = WDraggablePlainPanel(color = config.tileHints.hudAttribute.backgroundColor) { _, y ->
        config.tileHints.hudAttribute.setPosition(0, y) //牌的提示不能调整 x 轴, 这是永远置中的
        MahjongCraftClient.saveConfig()
    }.also { it.add(tileHintsText, 0, 0, it.width, it.height) }

    init {
        fullscreen = true
        rootPlainPanel(width = 0, height = 0) { //fullscreen 可以不用输入大小
            backgroundPainter = BackgroundPainter.createColorful((0x00_000000).toInt())
            this.add(quickActions, 0, 0)
            this.add(tileHints, 0, 0)
        }
    }

    fun reposition(screenWidth: Int, screenHeight: Int) {
        hud.quickActionsRootSize.also { (width, height) ->
            this.quickActions.setSize(width, height)
            quickActionsText.setSize(width, height)
        }
        config.quickActions.hudAttribute.also {
            this.quickActions.setLocation(
                (screenWidth * it.x).toInt(),
                (screenHeight * it.y).toInt()
            )
        }
        hud.tileHintsRootSize.also { (_, height) ->
            this.tileHints.setSize(screenWidth, height)
            tileHintsText.setSize(screenWidth, height)
        }
        config.tileHints.hudAttribute.also { this.tileHints.setLocation(0, (screenHeight * it.y).toInt()) }
    }

    private fun HudAttribute.setPosition(x: Int, y: Int) {
        this.x = x / window.scaledWidth.toDouble()
        this.y = y / window.scaledHeight.toDouble()
    }

    private fun createCenteredTextWidget(text: Text, color: Int = Color.WHITE.toRgb()): WText =
        WText(text, color).apply {
            verticalAlignment = VerticalAlignment.CENTER
            horizontalAlignment = HorizontalAlignment.CENTER
        }
}