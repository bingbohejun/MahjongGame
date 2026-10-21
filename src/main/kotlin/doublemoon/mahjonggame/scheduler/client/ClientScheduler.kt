package doublemoon.mahjonggame.scheduler.client

import doublemoon.mahjonggame.scheduler.ActionBase
import doublemoon.mahjonggame.scheduler.DelayAction
import doublemoon.mahjonggame.scheduler.LoopAction
import doublemoon.mahjonggame.scheduler.RepeatAction
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.client.MinecraftClient

/**
 * 要严格防止在任务中呼叫其它任务
 * */
@OnlyIn(Dist.CLIENT)
object ClientScheduler {

    private val queuedActions = mutableListOf<ActionBase>()
    private val loopActions = mutableListOf<LoopAction>()

    //用来 trace world 改变的事件
    private var doesWorldExist: Boolean = false

    //1 tick = 50 ms
    fun tick(client: MinecraftClient) {
        if ((client.window == null) == doesWorldExist) {
            doesWorldExist = (client.world != null)
            loopActions.forEach { it.resetTimer() }
            queuedActions.filterIsInstance<RepeatAction>().forEach { it.resetTimer() }
        }
        if (client.world == null || client.player == null) {
            queuedActions.clear()
            return
        }

        val copyOfQueuedAction = queuedActions.toList()
        val copyOfLoopAction = loopActions.toList()
        copyOfQueuedAction.forEach { if (it.tick()) queuedActions.remove(it) }
        copyOfLoopAction.forEach { it.tick() }
    }

    /**
     * @param delay 单位: ms
     * */
    fun scheduleDelayAction(delay: Long = 0, action: () -> Unit): DelayAction =
        DelayAction(delay, action).also { queuedActions += it }

    /**
     * @param interval 单位: ms
     * */
    fun scheduleRepeatAction(times: Int, interval: Long = 0, action: () -> Unit): RepeatAction =
        RepeatAction(times, interval, action).also { queuedActions += it }

    /**
     * @param interval 单位: ms
     * */
    fun scheduleLoopAction(interval: Long = 0, action: () -> Unit): LoopAction =
        LoopAction(interval, action).also { loopActions += it }

    fun onStopping() {
        queuedActions.clear()
        loopActions.clear()
    }
}