package doublemoon.mahjonggame.network

import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.neoforged.neoforge.network.PacketDistributor

@OnlyIn(Dist.CLIENT)
fun CustomPayload.sendToServer() {
    PacketDistributor.sendToServer(this)
}

@OnlyIn(Dist.CLIENT)
fun sendPayloadToServer(payload: CustomPayload) = payload.sendToServer()

fun CustomPayload.sendToPlayer(player: ServerPlayerEntity) {
    PacketDistributor.sendToPlayer(player, this)
}

fun sendPayloadToPlayer(player: ServerPlayerEntity, payload: CustomPayload) = payload.sendToPlayer(player)
