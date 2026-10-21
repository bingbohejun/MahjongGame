package doublemoon.mahjonggame.util

import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text


/**
 * 对玩家发送显示标题的数据包, 不能控制时间
 *
 * @param title 要显示的标题
 * @param subtitle 要显示的副标题
 * */
fun ServerPlayerEntity.sendTitles(
    title: Text = Text.of(""),
    subtitle: Text? = null,
) {
    with(networkHandler) {
        send(TitleS2CPacket(title))
        subtitle?.also { send(SubtitleS2CPacket(it)) }
    }
}

/**
 * 对玩家发送显示标题的数据包
 *
 * @param title 要显示的标题
 * @param subtitle 要显示的副标题
 * */
fun Collection<ServerPlayerEntity>.sendTitles(
    title: Text = Text.of(""),
    subtitle: Text? = null
) {
    forEach { it.sendTitles(title, subtitle) }
}