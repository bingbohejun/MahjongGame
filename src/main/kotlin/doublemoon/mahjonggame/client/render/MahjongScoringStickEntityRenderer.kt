package doublemoon.mahjonggame.client.render

import doublemoon.mahjonggame.entity.MahjongScoringStickEntity
import doublemoon.mahjonggame.entity.MahjongScoringStickEntity.Companion.MAHJONG_POINT_STICK_HEIGHT
import doublemoon.mahjonggame.entity.MahjongScoringStickEntity.Companion.MAHJONG_POINT_STICK_SCALE
import doublemoon.mahjonggame.game.mahjong.riichi.model.ScoringStick
import doublemoon.mahjonggame.registry.ItemRegistry
import doublemoon.mahjonggame.util.RenderHelper
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.model.json.ModelTransformationMode
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.nbt.NbtCompound
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis

@OnlyIn(Dist.CLIENT)
class MahjongScoringStickEntityRenderer(
    context: EntityRendererFactory.Context,
) : EntityRenderer<MahjongScoringStickEntity>(context) {

    private val itemRenderer = context.itemRenderer

    override fun shouldRender(
        entity: MahjongScoringStickEntity,
        frustum: Frustum,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean {
        return !entity.isInvisible && super.shouldRender(entity, frustum, x, y, z)
    }

    override fun render(
        entity: MahjongScoringStickEntity,
        yaw: Float,
        tickDelta: Float,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
    ) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light)
        with(matrices) {
            push()
            multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entity.yaw + 180))
            RenderHelper.renderItem(
                itemRenderer = itemRenderer,
                matrices = this,
                stack = scoringSticks[entity.code],
                offsetX = 0.0,
                offsetY = MAHJONG_POINT_STICK_HEIGHT / 2.0 + (1f * MAHJONG_POINT_STICK_SCALE - MAHJONG_POINT_STICK_HEIGHT) / 2.0,
                offsetZ = 0.0,
                light = light,
                mode = ModelTransformationMode.HEAD,
                vertexConsumer = vertexConsumers
            )
            pop()
        }
    }

    override fun getTexture(entity: MahjongScoringStickEntity): Identifier? = null

    companion object {
        private val scoringSticks = ScoringStick.entries.map { stick->
            ItemRegistry.mahjongScoringStick.defaultStack.also {
                val nbt = NbtCompound()
                nbt.putInt("code", stick.code)
                it.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))
            }
        }
    }
}