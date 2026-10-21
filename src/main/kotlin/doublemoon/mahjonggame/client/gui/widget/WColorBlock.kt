package doublemoon.mahjonggame.client.gui.widget

import io.github.cottonmc.cotton.gui.client.ScreenDrawing
import io.github.cottonmc.cotton.gui.widget.WWidget
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.gui.DrawContext

class WColorBlock(
    private val color: Int,
) : WWidget() {

    override fun canResize(): Boolean = true

    @OnlyIn(Dist.CLIENT)
    override fun paint(context: DrawContext, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        ScreenDrawing.coloredRect(context, x, y, width, height, color)
    }
}