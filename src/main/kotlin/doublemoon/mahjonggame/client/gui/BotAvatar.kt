package doublemoon.mahjonggame.client.gui

import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier

/**
 * 机器人 (电脑) 在 GUI 里的头像: 默认史蒂夫皮肤的那张脸,
 * 和世界里看到的史蒂夫模型保持一致
 * */
@OnlyIn(Dist.CLIENT)
object BotAvatar {

    /** 原版的默认史蒂夫皮肤 */
    private val STEVE_SKIN: Identifier = Identifier.of("minecraft", "textures/entity/player/wide/steve.png")

    /** 皮肤贴图的尺寸 */
    private const val SKIN_SIZE = 64

    /** 脸的正面在皮肤贴图里的位置 (起点 + 大小), 单位是像素 */
    private const val FACE_U = 8f
    private const val FACE_V = 8f
    private const val FACE_WIDTH = 8
    private const val FACE_HEIGHT = 8

    /**
     * 在 (x, y) 画出 [size] × [size] 的史蒂夫脸
     * */
    fun paintFace(context: DrawContext, x: Int, y: Int, size: Int) {
        //drawTexture 的参数: (贴图, x, y, 目标宽, 目标高, u, v, 采样区宽, 采样区高, 贴图宽, 贴图高)
        context.drawTexture(
            STEVE_SKIN,
            x,
            y,
            size,
            size,
            FACE_U,
            FACE_V,
            FACE_WIDTH,
            FACE_HEIGHT,
            SKIN_SIZE,
            SKIN_SIZE
        )
    }
}
