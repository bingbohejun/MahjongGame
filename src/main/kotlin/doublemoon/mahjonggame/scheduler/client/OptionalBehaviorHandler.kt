package doublemoon.mahjonggame.scheduler.client

import doublemoon.mahjonggame.client.gui.screen.MahjongGameBehaviorScreen
import doublemoon.mahjonggame.game.mahjong.riichi.model.ClaimTarget
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongGameBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen

/**
 * 将资料显示在 [MahjongGameBehaviorScreen] 供玩家点击
 * */
@OnlyIn(Dist.CLIENT)
object OptionalBehaviorHandler {

    private val client = MinecraftClient.getInstance()
    private var screen: Screen? = null

    private lateinit var behavior: MahjongGameBehavior
    private lateinit var hands: List<MahjongTile>
    private lateinit var target: ClaimTarget
    private lateinit var extraData: String

    var waiting: Boolean = false
        private set

    /**
     * 打开 [screen] 让玩家选择
     * */
    fun setScreen() {
        ClientScheduler.scheduleDelayAction {
            screen = MahjongGameBehaviorScreen(behavior, hands, target, extraData)
            client.setScreen(screen!!)
        }
    }

    /**
     * 关闭玩家的 [screen],
     * (必须在主线程上呼叫, 否则游戏准星或滑鼠会失灵)
     * */
    private fun closeScreen() {
        CoroutineScope(Dispatchers.Default).launch {
            val nowScreen = screen
            if (nowScreen != null && client.currentScreen == nowScreen) {
                ClientScheduler.scheduleDelayAction { nowScreen.close() }
            }
        }
    }

    /**
     * @param hands 输入进来的时候是整数列表
     * */
    fun start(
        behavior: MahjongGameBehavior,
        hands: List<MahjongTile>,
        target: ClaimTarget,
        extraData: String
    ) {
        waiting = true
        this.behavior = behavior
        this.hands = hands
        this.target = target
        this.extraData = extraData
        setScreen()
    }

    fun cancel() {
        waiting = false
        closeScreen()
    }
}