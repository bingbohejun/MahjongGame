package doublemoon.mahjonggame.network.mahjong_tile_code

import doublemoon.mahjonggame.entity.MahjongTileEntity
import doublemoon.mahjonggame.network.ChannelType
import doublemoon.mahjonggame.network.CustomPayloadListener
import doublemoon.mahjonggame.network.sendPayloadToPlayer
import net.minecraft.client.MinecraftClient
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.handling.IPayloadContext

object MahjongTileCodePayloadListener : CustomPayloadListener<MahjongTileCodePayload> {

    override val id: CustomPayload.Id<MahjongTileCodePayload> = MahjongTileCodePayload.ID

    override val codec: PacketCodec<RegistryByteBuf, MahjongTileCodePayload> = MahjongTileCodePayload.CODEC

    override val channelType: ChannelType = ChannelType.Both

    @OnlyIn(Dist.CLIENT)
    override fun onClientReceive(
        payload: MahjongTileCodePayload,
        context: IPayloadContext,
    ) {
        val (id, code) = payload
        val world = MinecraftClient.getInstance().world ?: return

        kotlin.runCatching {
            // ClientWorld 并没有 getEntity(uuid) 的方法, 但是 ServerWorld 就有 (what??)
            world.getEntityById(id) as MahjongTileEntity? ?: return
        }.fold(
            onSuccess = { it.code = code },
            onFailure = {
                // 因为包的处理不在主线程上, 所以有机会在 getEntityById 时,
                // 物体已经消失然后造成 IndexOutOfBoundsException 错误, 直接把这个错误忽略
                if (it !is IndexOutOfBoundsException) it.printStackTrace()
            }
        )
    }

    override fun onServerReceive(
        payload: MahjongTileCodePayload,
        context: IPayloadContext,
    ) {
        val (id, _) = payload
        val player = context.player() as ServerPlayerEntity
        val server = player.server

        for (world in server.worlds) {
            val entity = world.getEntityById(id) as? MahjongTileEntity ?: continue
            val code = entity.getCodeForPlayer(player)

            sendPayloadToPlayer(
                player = player,
                payload = MahjongTileCodePayload(id = id, code = code)
            )

            break
        }
    }
}