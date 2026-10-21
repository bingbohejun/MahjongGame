package doublemoon.mahjonggame.client.render

import doublemoon.mahjonggame.entity.MahjongTileEntity
import doublemoon.mahjonggame.entity.MahjongTileEntity.Companion.MAHJONG_TILE_DEPTH
import doublemoon.mahjonggame.entity.MahjongTileEntity.Companion.MAHJONG_TILE_HEIGHT
import doublemoon.mahjonggame.entity.TileFacing
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
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
class MahjongTileEntityRenderer(
    context: EntityRendererFactory.Context,
) : EntityRenderer<MahjongTileEntity>(context) {

    private val itemRenderer = context.itemRenderer

    override fun shouldRender(entity: MahjongTileEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean {
        return !entity.isInvisible && super.shouldRender(entity, frustum, x, y, z)
    }

    override fun render(
        entity: MahjongTileEntity,
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
            multiply(RotationAxis.POSITIVE_X.rotationDegrees(entity.facing.angleForDegreesQuaternionFromPositiveX))
            var offsetY = 0.0
            var offsetZ = 0.0
            when (entity.facing) {  //偏移材质用
                TileFacing.HORIZONTAL -> offsetY = MAHJONG_TILE_HEIGHT / 2.0
                TileFacing.UP -> offsetZ = -MAHJONG_TILE_DEPTH / 2.0
                TileFacing.DOWN -> offsetZ = MAHJONG_TILE_DEPTH / 2.0
            }
            RenderHelper.renderItem(
                itemRenderer = itemRenderer,
                matrices = this,
                stack = mahjongTiles[entity.code],
                offsetX = 0.0,
                offsetY = offsetY,
                offsetZ = offsetZ,
                light = light,
                vertexConsumer = vertexConsumers,
                mode = ModelTransformationMode.HEAD
            )
            pop()
        }
    }

    override fun getTexture(entity: MahjongTileEntity): Identifier? = null

    companion object {
        /**
         * 所有的麻将牌物品, 先存著渲染的时候直接用, 省下每次实例化 ItemStack 的效能
         * */
        @OnlyIn(Dist.CLIENT)
        val mahjongTiles = MahjongTile.entries.map { tile ->
            ItemRegistry.mahjongTile.defaultStack.also {
                val nbt = NbtCompound()
                nbt.putInt("code", tile.code)
                it.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))
            }
        }
    }
}