package doublemoon.mahjonggame.client.gui.widget

import doublemoon.mahjonggame.client.gui.BotAvatar
import io.github.cottonmc.cotton.gui.widget.WWidget
import io.github.cottonmc.cotton.gui.widget.data.HorizontalAlignment
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.gui.DrawContext

/**
 * 机器人 (电脑) 在 GUI 里的头像: 默认的史蒂夫脸,
 * 和世界里看到的史蒂夫模型保持一致
 *
 * @param code 以前的麻将牌外观码, 现在已经不用了, 保留参数避免改调用方
 * */
class WBotFace(
    @Suppress("unused") var code: Int? = null,
    var alignment: HorizontalAlignment = HorizontalAlignment.CENTER,
) : WWidget() {

    override fun canResize(): Boolean = true

    @OnlyIn(Dist.CLIENT)
    override fun paint(context: DrawContext, x: Int, y: Int, mouseX: Int, mouseY: Int) {
        val imgX = when (alignment) {
            HorizontalAlignment.LEFT -> x
            HorizontalAlignment.CENTER -> x + (width - height) / 2 //脸是正方形, 以高为准
            HorizontalAlignment.RIGHT -> x + width - height
        }
        BotAvatar.paintFace(context, imgX, y, height)
    }
}
