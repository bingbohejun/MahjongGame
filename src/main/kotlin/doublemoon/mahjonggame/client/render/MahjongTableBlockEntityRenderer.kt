package doublemoon.mahjonggame.client.render

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.MahjongCraftClient
import doublemoon.mahjonggame.block.MahjongTable
import doublemoon.mahjonggame.block.enums.MahjongTablePart
import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.game.GameStatus
import doublemoon.mahjonggame.game.mahjong.riichi.model.Wind
import doublemoon.mahjonggame.util.RenderHelper
import doublemoon.mahjonggame.util.plus
import io.github.cottonmc.cotton.gui.widget.data.Color
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.font.TextRenderer.TextLayerType
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.block.entity.BlockEntityRenderer
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.Text
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 麻将桌的方块实体渲染
 *
 * 游戏中会在桌面中央画一块平面面板 (取代原本浮在桌子上方的文字):
 * - 中央: 场风 + 局数, 以及牌山剩余张数
 * - 四边: 每一家的风牌 (庄家为红底) 与点数, 文字各自朝向该座位的玩家
 * */
@OnlyIn(Dist.CLIENT)
class MahjongTableBlockEntityRenderer(
    context: BlockEntityRendererFactory.Context
) : BlockEntityRenderer<MahjongTableBlockEntity> {
    private val textRenderer = context.textRenderer

    override fun render(
        blockEntity: MahjongTableBlockEntity,
        tickDelta: Float,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
        overlay: Int
    ) {
        if (!MahjongCraftClient.config.displayTableLabels) return
        if (blockEntity.cachedState[MahjongTable.PART] != MahjongTablePart.BOTTOM_CENTER) return //只有 BOTTOM_CENTER 才渲染

        if (blockEntity.playing) {
            renderCenterPanel(blockEntity, matrices, vertexConsumers)
        } else {
            renderWaitingLabels(blockEntity, matrices, vertexConsumers)
        }
    }

    /**
     * 游戏中: 在桌面中央绘制平面面板
     * */
    private fun renderCenterPanel(
        blockEntity: MahjongTableBlockEntity,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider
    ) {
        val world = blockEntity.world ?: return
        val seat = blockEntity.seat
        if (seat.size !in 3..4) return //三人或四人

        val light = RenderHelper.getLightLevel(world, blockEntity.pos.up())
        val dealerSeatIndex = seat.indexOf(blockEntity.dealer)

        val round = blockEntity.round
        val roundText = Text.translatable("$MOD_ID.game.round.title", round.wind.toText(), round.round + 1)
        val tilesLeftText = Text.translatable("$MOD_ID.game.tiles_left_total", blockEntity.tilesLeft)

        // 场风/局数 + 剩余牌数: 悬浮在桌子正中央上方 (比“结束”按钮低一些), billboard 从任何角度看都正
        val cx = 0.5f
        val cz = 0.5f
        drawBillboardQuad(
            matrices, vertexConsumers,
            x = cx, y = (1.0 + CENTER_INFO_ROUND_Y).toFloat(), z = cz,
            halfW = CENTER_INFO_HALF_W, halfH = CENTER_INFO_HALF_H,
            color = if (dealerSeatIndex == localSeatIndex(blockEntity)) COLOR_DEALER_BORDER else COLOR_PLATE_BORDER
        )
        drawBillboardText(
            matrices, vertexConsumers, light, roundText,
            x = cx, y = (1.0 + CENTER_INFO_ROUND_Y).toFloat(), z = cz,
            scale = CENTER_INFO_SCALE, color = COLOR_ROUND
        )
        drawBillboardQuad(
            matrices, vertexConsumers,
            x = cx, y = (1.0 + CENTER_INFO_REMAIN_Y).toFloat(), z = cz,
            halfW = CENTER_INFO_HALF_W, halfH = CENTER_INFO_HALF_H,
            color = COLOR_PLATE_BORDER
        )
        drawBillboardText(
            matrices, vertexConsumers, light, tilesLeftText,
            x = cx, y = (1.0 + CENTER_INFO_REMAIN_Y).toFloat(), z = cz,
            scale = CENTER_INFO_SCALE, color = COLOR_ROUND
        )

        // 风牌 + 点数: 直接显示在每位玩家头顶
        renderHeadLabels(blockEntity, matrices, vertexConsumers, dealerSeatIndex)

        // 桌子正中央上方的“结束”按钮
        renderEndButton(blockEntity, matrices, vertexConsumers, light)
    }

    /**
     * 判断本地玩家是第几号座位 (不在座位上时用摄影机方位推算, 例如观战)
     * */
    private fun localSeatIndex(blockEntity: MahjongTableBlockEntity): Int {
        val client = MinecraftClient.getInstance()
        val player = client.player
        if (player != null) {
            val index = blockEntity.seat.indexOf(player.uuidAsString)
            if (index >= 0) return index
        }
        val camera = client.gameRenderer.camera.pos
        val dx = camera.x - (blockEntity.pos.x + 0.5)
        val dz = camera.z - (blockEntity.pos.z + 0.5)
        return if (abs(dx) > abs(dz)) {
            if (dx > 0) 0 else 2
        } else {
            if (dz > 0) 3 else 1
        }
    }

    /**
     * “风 + 点数”显示在每位玩家的头顶 (跟著人走)。
     * 找不到玩家实体 (例如机器人没有实体) 时, 退回该座位方向的上方。
     * */
    private fun renderHeadLabels(
        blockEntity: MahjongTableBlockEntity,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        dealerSeatIndex: Int,
    ) {
        val clientWorld = MinecraftClient.getInstance().world ?: return
        val light = RenderHelper.getLightLevel(blockEntity.world ?: clientWorld, blockEntity.pos.up())
        val blockPos = blockEntity.pos
        matrices.push()
        blockEntity.seat.forEachIndexed { index, uuid ->
            val isDealer = index == dealerSeatIndex
            val wind = Wind.entries[windIndexOf(dealerSeatIndex, index)]
            val text = Text.empty()
                .append(wind.toText())
                .append(Text.literal(" "))
                .append(Text.of(blockEntity.points[index].toString()))

            val player = clientWorld.players.firstOrNull { it.uuidAsString == uuid }
            val gx: Float
            val gz: Float
            val gy: Float
            if (player != null) {
                gx = (player.x - blockPos.x).toFloat()
                gz = (player.z - blockPos.z).toFloat()
                gy = (player.eyeY + HEAD_LABEL_ABOVE_EYE - blockPos.y).toFloat()
            } else {
                // 没有实体 (机器人): 放在该座位方向的桌子上方
                val (seatX, seatZ) = SIDE_POSITIONS[index]
                gx = 0.5f + seatX
                gz = 0.5f + seatZ
                gy = (1.0 + HEAD_LABEL_ABOVE_EYE + 0.4).toFloat()
            }

            drawBillboardText(
                matrices, vertexConsumers, light, text,
                x = gx, y = gy, z = gz,
                scale = HEAD_LABEL_SCALE,
                color = if (isDealer) COLOR_DEALER_TEXT else COLOR_SEAT_TEXT
            )
        }
        matrices.pop()
    }

    /**
     * 桌子正中央上方的“结束游戏”按钮 (右键点击投票)
     * 机器人默认同意, 所以只需要所有真人玩家都同意
     * */
    private fun renderEndButton(
        blockEntity: MahjongTableBlockEntity,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
    ) {
        val player = MinecraftClient.getInstance().player ?: return
        if (!blockEntity.seat.contains(player.uuidAsString)) return // 只有局内玩家看得到

        val agreed = blockEntity.endVoteAgreed
        val needed = blockEntity.endVoteNeeded
        val text = if (needed > 0) {
            Text.translatable("$MOD_ID.game.end_button").copy()
                .append(Text.literal(" $agreed/$needed"))
        } else {
            Text.translatable("$MOD_ID.game.end_button")
        }

        val x = 0.5f
        val y = (1.0 + END_BUTTON_HEIGHT).toFloat()
        val z = 0.5f
        val color = if (agreed > 0) COLOR_END_BUTTON_ACTIVE else COLOR_END_BUTTON

        drawBillboardQuad(matrices, vertexConsumers, x, y, z, END_BUTTON_HALF_W, END_BUTTON_HALF_H, color)
        drawBillboardText(
            matrices, vertexConsumers, light, text,
            x = x, y = y, z = z, scale = END_BUTTON_SCALE, color = COLOR_END_BUTTON_TEXT
        )

        // 供点击判定使用
        MahjongCraftClient.endButtonPos = blockEntity.pos
        MahjongCraftClient.endButtonCenter =
            Vec3d(blockEntity.pos.x + 0.5, blockEntity.pos.y + 1.0 + END_BUTTON_HEIGHT, blockEntity.pos.z + 0.5)
    }

    /**
     * 面向相机的矩形 (billboard)
     * */
    private fun drawBillboardQuad(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        x: Float,
        y: Float,
        z: Float,
        halfW: Float,
        halfH: Float,
        color: Int,
    ) {
        val camera = MinecraftClient.getInstance().gameRenderer.camera
        matrices.push()
        matrices.translate(x, y, z)
        matrices.multiply(camera.rotation)
        matrices.translate(0f, 0f, -0.004f) // 稍微退到文字后面
        val matrix = matrices.peek().positionMatrix
        val buffer = vertexConsumers.getBuffer(RenderLayer.getDebugQuads())
        buffer.vertex(matrix, -halfW, -halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, -halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, -halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, -halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, -halfH, 0f).color(color)
        buffer.vertex(matrix, -halfW, -halfH, 0f).color(color)
        matrices.pop()
    }

    /**
     * 画一行永远面向相机的文字 (billboard), 从任何角度看都是正的
     * */
    private fun drawBillboardText(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
        text: Text,
        x: Float,
        y: Float,
        z: Float,
        scale: Float,
        color: Int,
    ) {
        val camera = MinecraftClient.getInstance().gameRenderer.camera
        matrices.push()
        matrices.translate(x, y, z)
        matrices.multiply(camera.rotation)
        matrices.scale(scale, -scale, scale) // GUI 座标 Y 向下, 这里翻正
        val width = textRenderer.getWidth(text)
        textRenderer.draw(
            text, -width / 2f, -4f, color, true,
            matrices.peek().positionMatrix, vertexConsumers, TextLayerType.NORMAL, 0, light
        )
        matrices.pop()
    }

    /**
     * 等待中: 在桌子上方显示状态/人数/准备数 (沿用原本的行为)
     * */
    private fun renderWaitingLabels(
        blockEntity: MahjongTableBlockEntity,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider
    ) {
        val playerAmount = blockEntity.players.count { it.isNotEmpty() }
        val readyAmount = blockEntity.ready.count { it }
        val currentReady = READY + ": $readyAmount/4"
        val currentPlayers = PLAYER + ": $playerAmount/4"
        val currentStatus = STATUS + ": " + (if (blockEntity.playing) PLAYING else WAITING)
        buildList {
            this += currentStatus
            this += currentPlayers
            this += currentReady
            reverse()
            forEachIndexed { index, text ->
                RenderHelper.renderLabel(
                    textRenderer = textRenderer,
                    matrices = matrices,
                    offsetX = 0.5,
                    offsetY = 1.0 + WAITING_PADDING + (LABEL_INTERVAL * index),
                    offsetZ = 0.5,
                    text = text,
                    color = Color.WHITE.toRgb(),
                    light = RenderHelper.getLightLevel(blockEntity.world!!, blockEntity.pos.up()),
                    vertexConsumers = vertexConsumers
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 绘制工具
    // ------------------------------------------------------------------

    /**
     * 将座标系转成“平铺在桌面上”的局部座标:
     * 区域 +X 为右, +Y 为该座位方向的外侧, +Z 朝上
     * ([angle] 让文字朝向对应的座位)
     * */
    private fun MatrixStack.pushFlatFrame(angle: Float) {
        multiply(RotationAxis.POSITIVE_Y.rotationDegrees(angle))
        multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f))
    }

    /**
     * 同上, 但把原点移到桌子平面上的 (worldX, worldZ) (相对桌面中心)
     * */
    private fun MatrixStack.pushFlatFrameAt(angle: Float, worldX: Float, worldZ: Float) {
        push()
        multiply(RotationAxis.POSITIVE_Y.rotationDegrees(angle))
        multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f))
        val rad = Math.toRadians(angle.toDouble())
        val localX = (worldX * cos(rad) - worldZ * sin(rad)).toFloat()
        val localY = (worldX * sin(rad) + worldZ * cos(rad)).toFloat()
        translate(localX, localY, 0f)
    }

    /**
     * 在目前的局部座标原点画一个矩形 (双面)
     * */
    private fun drawLocalQuad(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        halfW: Float,
        halfH: Float,
        color: Int,
        layer: Float,
    ) {
        matrices.push()
        matrices.translate(0f, 0f, -layer)
        val matrix = matrices.peek().positionMatrix
        val buffer = vertexConsumers.getBuffer(RenderLayer.getDebugQuads())
        buffer.vertex(matrix, -halfW, -halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, -halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, -halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, -halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, halfH, 0f).color(color)
        buffer.vertex(matrix, halfW, -halfH, 0f).color(color)
        buffer.vertex(matrix, -halfW, -halfH, 0f).color(color)
        matrices.pop()
    }


    /**
     * 在桌面上画一个矩形 (双面, 避免背面剔除导致看不到)
     * */
    private fun drawFlatQuad(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        cy: Float = 0f,
        halfW: Float,
        halfH: Float,
        color: Int,
        angle: Float = 0f,
        layer: Float = 0f,
    ) {
        matrices.push()
        matrices.pushFlatFrame(angle)
        matrices.translate(0f, 0f, -layer) // 局部 -Z 就是世界的上方
        val matrix = matrices.peek().positionMatrix
        val buffer = vertexConsumers.getBuffer(RenderLayer.getDebugQuads())
        val x0 = -halfW
        val x1 = halfW
        val y0 = cy - halfH
        val y1 = cy + halfH
        buffer.vertex(matrix, x0, y0, 0f).color(color)
        buffer.vertex(matrix, x1, y0, 0f).color(color)
        buffer.vertex(matrix, x1, y1, 0f).color(color)
        buffer.vertex(matrix, x0, y1, 0f).color(color)
        // 反面
        buffer.vertex(matrix, x0, y1, 0f).color(color)
        buffer.vertex(matrix, x1, y1, 0f).color(color)
        buffer.vertex(matrix, x1, y0, 0f).color(color)
        buffer.vertex(matrix, x0, y0, 0f).color(color)
        matrices.pop()
    }

    /**
     * 在桌面上画一行文字, [dist] 是沿著该座位方向往外偏移的距离 (单位: 方块)
     * */
    private fun drawFlatText(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
        text: Text,
        angle: Float,
        dist: Float,
        scale: Float,
        color: Int,
        yOffsetPx: Float = 0f,
        shadow: Boolean = false,
        planeRotation: Float = TEXT_PLANE_ROTATION,
    ) {
        matrices.push()
        matrices.pushFlatFrame(angle)
        matrices.translate(0f, dist, -LAYER_TEXT)
        if (planeRotation != 0f) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(planeRotation))
        matrices.scale(scale, scale, scale)
        val width = textRenderer.getWidth(text)
        textRenderer.draw(
            text,
            -width / 2f,
            yOffsetPx,
            color,
            shadow,
            matrices.peek().positionMatrix,
            vertexConsumers,
            TextLayerType.NORMAL,
            0,
            light
        )
        matrices.pop()
    }

    /**
     * 座位的风: 庄家为东, 之后依座位顺序为南西北
     * */
    private fun windIndexOf(dealerSeatIndex: Int, seatIndex: Int): Int {
        if (dealerSeatIndex < 0) return seatIndex
        val difference = dealerSeatIndex - seatIndex
        return (if (difference >= 0) 4 - difference else -difference) % 4
    }

    companion object {
        // 座位 -> 方位 (与 MahjongGame.start() 的传送位置一致): 0=东, 1=北, 2=西, 3=南
        // 角度是让文字朝该座位玩家的方向书写 (每个座位都有自己的转向, 四家都能正著读)
        private val SEAT_ANGLES = floatArrayOf(90f, 180f, -90f, 0f)

        // 悬浮在桌子中央上方的场风/局数 + 剩余牌数 (比“结束”按钮 1.15 低)
        private const val CENTER_INFO_ROUND_Y = 0.72f
        private const val CENTER_INFO_REMAIN_Y = 0.47f
        private const val CENTER_INFO_HALF_W = 0.34f
        private const val CENTER_INFO_HALF_H = 0.10f
        private const val CENTER_INFO_SCALE = 0.014f

        // 图层高度 (避免与桌面 z-fighting)
        private const val LAYER_TEXT = 0.0012f

        // 中央上方的“结束”按钮
        private const val END_BUTTON_HEIGHT = 1.15
        private const val END_BUTTON_HALF_W = 0.42f
        private const val END_BUTTON_HALF_H = 0.13f
        private const val END_BUTTON_SCALE = 0.016f
        private const val COLOR_END_BUTTON = 0xE0303030.toInt()
        private const val COLOR_END_BUTTON_ACTIVE = 0xE0583030.toInt()
        private const val COLOR_END_BUTTON_TEXT = 0xFFFFFFFF.toInt()

        // 头顶标签 (风 + 点数)
        private const val HEAD_LABEL_ABOVE_EYE = 0.45
        private const val HEAD_LABEL_SCALE = 0.02f
        private const val SIDE_DIST = 1.16f

        // 每个座位对应的桌面边: 0=东(+X), 1=北(-Z), 2=西(-X), 3=南(+Z)
        private val SIDE_POSITIONS = arrayOf(
            SIDE_DIST to 0f,
            0f to -SIDE_DIST,
            -SIDE_DIST to 0f,
            0f to SIDE_DIST,
        )

        // 颜色 (ARGB)
        private const val COLOR_PLATE = 0xE0141414.toInt()
        private const val COLOR_PLATE_BORDER = 0xFF3A3A3A.toInt()
        private const val COLOR_DEALER_BORDER = 0xFFB3262C.toInt()
        private const val COLOR_ROUND = 0xFF4FD8CF.toInt()
        private const val COLOR_SEAT_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DEALER_TEXT = 0xFFFFC24A.toInt()

        // 文字在桌面平面上的旋转校正值
        private const val TEXT_PLANE_ROTATION = 0f

        // 等待中的标签
        private const val WAITING_PADDING = 0.4
        private const val LABEL_INTERVAL = 0.25
        private val READY get() = Text.translatable("$MOD_ID.gui.button.ready")
        private val PLAYER get() = Text.translatable("$MOD_ID.game.player")
        private val STATUS get() = Text.translatable("$MOD_ID.game.status")
        private val WAITING = GameStatus.WAITING.toText()
        private val PLAYING = GameStatus.PLAYING.toText()
    }
}
