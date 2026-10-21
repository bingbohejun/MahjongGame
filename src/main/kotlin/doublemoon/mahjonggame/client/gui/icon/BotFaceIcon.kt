package doublemoon.mahjonggame.client.gui.icon

import doublemoon.mahjonggame.client.gui.BotAvatar
import io.github.cottonmc.cotton.gui.widget.icon.Icon
import net.minecraft.client.gui.DrawContext

/**
 * 机器人 (电脑) 在 GUI 里的头像: 默认的史蒂夫脸
 *
 * @param code 以前的麻将牌外观码, 现在已经不用了, 保留参数避免改调用方
 * */
class BotFaceIcon(
    @Suppress("unused") val code: Int = 0,
) : Icon {
    override fun paint(context: DrawContext, x: Int, y: Int, size: Int) {
        BotAvatar.paintFace(context, x, y, size)
    }
}
