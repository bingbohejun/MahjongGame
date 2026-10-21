package doublemoon.mahjonggame.scheduler.client

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text

/**
 * 储存由数据包收到的时间, 没有任何功能
 * */
object ClientCountdownTimeHandler {

    private val client = MinecraftClient.getInstance()
    private const val titleFadeInTime = 5
    private const val titleRemainTime = 10
    private const val titleFadeOutTime = 5

    /**
     * null to null 表示没在倒数
     * */
    var basicAndExtraTime: Pair<Int?, Int?> = null to null
        set(value) {
            if (value.first != null && value.second != null) {  //有新的值出现
                displayTime(timeBase = value.first!!, timeExtra = value.second!!)
            } else {  //两个都为 null, 清除倒数计时, 关闭所有该关的处理程序
                if (OptionalBehaviorHandler.waiting) OptionalBehaviorHandler.cancel()
                client.inGameHud.clearTitle()
            }
            field = value
        }


    /**
     * 显示剩余的思考时间
     * */
    private fun displayTime(timeBase: Int, timeExtra: Int) {
        val base = if (timeBase > 0) "§a$timeBase" else ""
        val plus = if (timeBase > 0 && timeExtra > 0) "§e + " else ""
        val extra = if (timeExtra > 0) "§c$timeExtra" else ""
        val text = Text.of("$base$plus$extra")
        //TODO 改位置显示时间
        with(client.inGameHud) {
            setTitle(Text.of(""))
            setSubtitle(text)
            setTitleTicks(titleFadeInTime, titleRemainTime, titleFadeOutTime)
        }
    }
}