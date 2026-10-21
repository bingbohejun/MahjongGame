package doublemoon.mahjonggame.client.gui.widget

import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import io.github.cottonmc.cotton.gui.client.ScreenDrawing
import io.github.cottonmc.cotton.gui.widget.WWidget
import io.github.cottonmc.cotton.gui.widget.data.Color
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.math.RotationAxis

class WMahjongTile(
    var mahjongTile: MahjongTile,
    var direction: TileDirection = TileDirection.NORMAL,
) : WWidget() {

    override fun canResize(): Boolean = true

    override fun setSize(x: Int, y: Int) {
        if (direction == TileDirection.NORMAL) {
            super.setSize(x, y)
        } else {
            this.width = y
            this.height = x
        }
    }

    @OnlyIn(Dist.CLIENT)
    override fun paint(context: DrawContext, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        if (direction == TileDirection.NORMAL) {
            ScreenDrawing.texturedRect(
                context,
                x,
                y,
                width,
                height,
                mahjongTile.surfaceIdentifier,
                Color.WHITE.toRgb()
            )
        } else {
            val matrices = context.matrices
            //这里的 height 跟 width 都是反著用的, 因为我们把 matrices 转成横的才把直的印上去, 所以应该套用原本直的高跟宽
            matrices.push()
            when (direction) {
                TileDirection.RIGHT -> {
                    matrices.translate((x + width).toDouble(), y.toDouble(), 0.0)
                    matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90f))
                }
                TileDirection.LEFT -> {
                    matrices.translate(x.toDouble(), (y + height).toDouble(), 0.0)
                    matrices.multiply(RotationAxis.NEGATIVE_Z.rotationDegrees(90f))
                }
                else -> return //这种情况不会发生
            }
            ScreenDrawing.texturedRect(
                context,
                0, //x, y 在前面 when 的时候使用 translate 偏移过了, 直接用 0
                0,
                height,
                width,
                mahjongTile.surfaceIdentifier,
                Color.WHITE.toRgb()
            )
            matrices.pop()
        }
    }

    enum class TileDirection {
        LEFT, NORMAL, RIGHT
    }
}