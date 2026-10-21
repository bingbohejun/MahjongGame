package doublemoon.mahjonggame.client.render

import doublemoon.mahjonggame.entity.MahjongBotEntity
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.model.EntityModelLayers
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis
import kotlin.math.PI
import kotlin.math.sin

/**
 * 麻将机器人的外观: 默认的史蒂夫模型 (模型和贴图都用原版的那一套)
 *
 * 机器人实体不是 [net.minecraft.entity.LivingEntity], 所以没有直接套用原版的
 * [net.minecraft.client.render.entity.PlayerEntityRenderer], 而是拿原版的玩家模型层自己摆姿势渲染
 * */
@OnlyIn(Dist.CLIENT)
class MahjongBotEntityRenderer(
    context: EntityRendererFactory.Context
) : EntityRenderer<MahjongBotEntity>(context) {

    /**
     * 原版的玩家模型,
     * 类型参数只在 setAngles 里用到 (机器人不是生物, 呼叫会直接转型失败), 这里自己摆姿势所以用不到
     * */
    private val model: PlayerEntityModel<PlayerEntity> =
        PlayerEntityModel(context.getPart(EntityModelLayers.PLAYER), false)

    override fun shouldRender(
        entity: MahjongBotEntity,
        frustum: Frustum,
        x: Double,
        y: Double,
        z: Double
    ): Boolean = !entity.isInvisible && super.shouldRender(entity, frustum, x, y, z)

    override fun render(
        entity: MahjongBotEntity,
        yaw: Float,
        tickDelta: Float,
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int
    ) {
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light)
        matrices.push()
        // 机器人实体不是 LivingEntity, 没有会同步的 bodyYaw/headYaw (客户端恒为 0),
        // 所以整组朝向直接拿 entity.yaw (会随实体同步到客户端) 来转, 让史蒂夫面朝牌桌
        val facing = entity.yaw
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - facing))
        matrices.scale(-1f, -1f, 1f) //模型是左右镜像的, 这是 MC 渲染模型的惯例
        matrices.translate(0f, -1.501f, 0f) //把模型原点对齐到实体的脚部

        // 摆姿势: 头跟着实体的俯仰, 机器人不单独转头 (头相对身体为 0); 手臂有一点自然的摆动
        val deg = (PI / 180.0).toFloat()
        val sway = sin(entity.age * 0.08f) * 0.02f
        model.head.pitch = entity.pitch * deg
        model.head.yaw = 0f
        model.hat.copyTransform(model.head)
        model.body.pitch = 0f
        model.body.yaw = 0f
        model.jacket.copyTransform(model.body)
        model.rightArm.pitch = 0f
        model.rightArm.yaw = 0f
        model.rightArm.roll = sway
        model.rightSleeve.copyTransform(model.rightArm)
        model.leftArm.pitch = 0f
        model.leftArm.yaw = 0f
        model.leftArm.roll = -sway
        model.leftSleeve.copyTransform(model.leftArm)
        model.rightLeg.pitch = 0f
        model.rightLeg.yaw = 0f
        model.rightPants.copyTransform(model.rightLeg)
        model.leftLeg.pitch = 0f
        model.leftLeg.yaw = 0f
        model.leftPants.copyTransform(model.leftLeg)

        model.render(
            matrices,
            vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(STEVE_TEXTURE)),
            light,
            OverlayTexture.DEFAULT_UV
        )
        matrices.pop()
    }

    override fun getTexture(entity: MahjongBotEntity): Identifier = STEVE_TEXTURE

    private companion object {
        /** 原版的默认史蒂夫皮肤 */
        private val STEVE_TEXTURE: Identifier =
            Identifier.of("minecraft", "textures/entity/player/wide/steve.png")
    }
}
