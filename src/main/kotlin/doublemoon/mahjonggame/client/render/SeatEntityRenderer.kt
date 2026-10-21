package doublemoon.mahjonggame.client.render

import doublemoon.mahjonggame.entity.SeatEntity
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.util.Identifier

@OnlyIn(Dist.CLIENT)
class SeatEntityRenderer(
    context: EntityRendererFactory.Context
) : EntityRenderer<SeatEntity>(context) {

    override fun getTexture(entity: SeatEntity): Identifier? = null

}