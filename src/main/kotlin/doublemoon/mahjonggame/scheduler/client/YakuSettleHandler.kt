package doublemoon.mahjonggame.scheduler.client

import doublemoon.mahjonggame.client.gui.screen.YakuSettlementScreen
import doublemoon.mahjonggame.game.mahjong.riichi.model.YakuSettlement
import doublemoon.mahjonggame.util.delayOnClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen

object YakuSettleHandler {
    const val defaultTime = 10 //单位:秒

    private val client = MinecraftClient.getInstance()
    private lateinit var screen: Screen
    private var jobCountdown: Job? = null

    var time = 0
        private set

    /**
     * 打开 [screen] 让玩家选择
     * */
    @OnlyIn(Dist.CLIENT)
    private fun setScreen(settlements: List<YakuSettlement>) {
        ClientScheduler.scheduleDelayAction {
            screen = YakuSettlementScreen(settlements = settlements)
            client.setScreen(screen)
        }
    }

    /**
     * 关闭玩家的 [screen],
     * (必须在主线程上呼叫, 否则游戏准星或滑鼠会失灵)
     * */
    @OnlyIn(Dist.CLIENT)
    private fun closeScreen() {
        CoroutineScope(Dispatchers.Default).launch {
            if (client.currentScreen == screen) {
                ClientScheduler.scheduleDelayAction { screen.close() }
            }
        }
    }

    /**
     * 时间由要显示的 [settlementList] 数量决定
     * */
    @OnlyIn(Dist.CLIENT)
    fun start(settlementList: List<YakuSettlement>) {
        jobCountdown?.cancel()
        jobCountdown = CoroutineScope(Dispatchers.Default).launch {
            time = defaultTime * settlementList.size
            setScreen(settlements = settlementList)
            repeat(times = time) {
                delayOnClient(1000)
                time--
                if (time <= 0) closeScreen()
            }
        }
    }
}