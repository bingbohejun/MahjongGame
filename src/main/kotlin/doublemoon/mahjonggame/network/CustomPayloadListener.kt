package doublemoon.mahjonggame.network

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.network.handling.DirectionalPayloadHandler
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.registration.PayloadRegistrar

interface CustomPayloadListener<T : CustomPayload> {

    val id: CustomPayload.Id<T>

    val codec: PacketCodec<RegistryByteBuf, T>

    val channelType: ChannelType

    @OnlyIn(Dist.CLIENT)
    fun onClientReceive(
        payload: T,
        context: IPayloadContext,
    ) {
    }

    fun onServerReceive(
        payload: T,
        context: IPayloadContext,
    ) {
    }

    /**
     * 在 [net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent] 中统一向 NeoForge 注册,
     * 取代 Fabric API 的 PayloadTypeRegistry + (Client/Server)PlayNetworking
     * */
    fun register(registrar: PayloadRegistrar) {
        when (channelType) {
            ChannelType.S2C -> registrar.playToClient(id, codec) { payload, context ->
                onClientReceive(payload, context)
            }

            ChannelType.C2S -> registrar.playToServer(id, codec) { payload, context ->
                onServerReceive(payload, context)
            }

            ChannelType.Both -> registrar.playBidirectional(
                id,
                codec,
                DirectionalPayloadHandler(
                    { payload, context -> onClientReceive(payload, context) },
                    { payload, context -> onServerReceive(payload, context) }
                )
            )
        }
    }
}
