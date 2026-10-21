package doublemoon.mahjonggame.scheduler.server

import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.logger
import doublemoon.mahjonggame.scheduler.ActionBase
import doublemoon.mahjonggame.scheduler.DelayAction
import doublemoon.mahjonggame.scheduler.LoopAction
import doublemoon.mahjonggame.scheduler.RepeatAction
import net.minecraft.server.MinecraftServer

/**
 * 要严格防止在任务中呼叫其它任务
 * */
object ServerScheduler {

    private val queuedActions = mutableListOf<ActionBase>()
    private val loopActions = mutableListOf<LoopAction>()

    fun tick(server: MinecraftServer) {
        if (!server.isRunning) return

        /*
        * 因为有可能对 queuedActions 使用 removeIf 或者 forEach 时, 其他地方会在 queuedActions 迭代的过程中,
        * 对 queuedActions "添加"新的物件, 导致产生了 java.util.ConcurrentModificationException,
        * 为了避免错误, 虽然可能有点浪费资源, 但这边简单粗暴直接 copy 一个新的 list 来用
        * */
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

    fun removeQueuedAction(action: ActionBase): Boolean = queuedActions.remove(action)

    fun onStopping(server: MinecraftServer) {
        queuedActions.clear()
        loopActions.clear()
        GameManager.games.forEach { it.onServerStopping(server) }
        logger.info("${GameManager.games.size} games cleared")
    }
}