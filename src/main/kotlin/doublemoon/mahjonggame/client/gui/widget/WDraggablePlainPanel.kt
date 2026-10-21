package doublemoon.mahjonggame.client.gui.widget

import io.github.cottonmc.cotton.gui.client.BackgroundPainter
import io.github.cottonmc.cotton.gui.widget.WPlainPanel
import io.github.cottonmc.cotton.gui.widget.data.InputResult
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.gui.DrawContext

/**
 *
 * @param confinedWithinParentBound 限制在父元件的边界内 (仅在 [parent] 不为 null 才有效)
 * @param onDraggingEnd 在拖动结束的时候触发, 传入的是当前这个 widget 的位置 x, y
 * */
class WDraggablePlainPanel(
    color: Int? = null,
    private val confinedWithinParentBound: Boolean = true,
    private val onDraggingEnd: (Int, Int) -> Unit,
) : WPlainPanel() {
    private var isDragging = false
    private var anchorX = 0
    private var anchorY = 0

    init {
        color?.let { backgroundPainter = BackgroundPainter.createColorful(it) }
    }

    @OnlyIn(Dist.CLIENT)
    override fun paint(context: DrawContext, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        super.paint(context, x, y, mouseX, mouseY)
        if (isDragging) {
            var deltaX = mouseX - anchorX
            var deltaY = mouseY - anchorY
            parent?.also {
                if (confinedWithinParentBound) { //如果移动超出父元件边界, 会贴到父元件上
                    val newX = this.x + deltaX
                    val newY = this.y + deltaY
                    if (newX + this.width > it.x + it.width) deltaX = it.width - (this.x + this.width)
                    else if (newX < it.x) deltaX = it.x - this.x
                    if (newY + this.height > it.y + it.height) deltaY = it.height - (this.y + this.height)
                    else if (newY < it.y) deltaY = it.y - this.y
                }
            }
            this.x += deltaX
            this.y += deltaY
        }
    }

    override fun onMouseDown(x: Int, y: Int, button: Int): InputResult {
        if (button == 0) {
            anchorX = x
            anchorY = y
            isDragging = true
        }
        return InputResult.PROCESSED
    }

    override fun onMouseUp(x: Int, y: Int, button: Int): InputResult {
        if (button == 0) {
            isDragging = false
            onDraggingEnd.invoke(this.x, this.y)
        }
        return InputResult.PROCESSED
    }
}