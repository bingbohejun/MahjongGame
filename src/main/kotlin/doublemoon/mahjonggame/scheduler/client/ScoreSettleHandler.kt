package doublemoon.mahjonggame.scheduler.client

import doublemoon.mahjonggame.client.gui.screen.ScoreSettlementScreen
import doublemoon.mahjonggame.game.mahjong.riichi.model.ScoreSettlement
import doublemoon.mahjonggame.util.delayOnClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen

object ScoreSettleHandler {
    const val defaultTime = 5 //单位:秒

    private val client = MinecraftClient.getInstance()
    private lateinit var screen: Screen
    private var jobCountdown: Job? = null

    var time = 0
        private set

    /**
     * 打开 [screen] 让玩家选择
     * */
    @OnlyIn(Dist.CLIENT)
    private fun setScreen(settlement: ScoreSettlement) {
        ClientScheduler.scheduleDelayAction {
            screen = ScoreSettlementScreen(settlement = settlement)
            client.setScreen(screen)
        }
    }

    /**
     * 关闭玩家的 [screen],
     * (必须在主线程上呼叫, 否则游戏准星或滑鼠会失灵)
     * */
    @OnlyIn(Dist.CLIENT)
    fun closeScreen() {
        CoroutineScope(Dispatchers.Default).launch {
            if (client.currentScreen == screen) {
                ClientScheduler.scheduleDelayAction { screen.close() }
            }
        }
    }

    @OnlyIn(Dist.CLIENT)
    fun start(settlement: ScoreSettlement) {
        jobCountdown?.cancel()
        jobCountdown = CoroutineScope(Dispatchers.Default).launch {
            time = defaultTime
            setScreen(settlement = settlement)
            repeat(times = time) {
                delayOnClient(1000)
                time--
                if (time <= 0) closeScreen()
            }
        }
    }
}