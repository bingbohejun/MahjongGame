package doublemoon.mahjonggame.scheduler

import doublemoon.mahjonggame.logger
import java.time.Instant

/**
 * 无限地重复执行动作
 * @param interval 要执行的间隔豪秒数,每次动作最少都会相隔 1 个 tick,所以间隔小于 1 个标准 tick 的时间 (1/20 秒 (50 ms)) 的动作会延到下个 tick 执行
 * @param action 每个间隔时间后执行的动作
 * */
class LoopAction(
    private val interval: Long,
    override val action: () -> Unit
) : ActionBase {
    override var stop: Boolean = false
    override var timeToAction: Long = Instant.now().toEpochMilli() + interval

    /**
     * 除非手动 [stop], 否则这个 [tick] 是不会回传 true 的, 因为 [LoopAction] 不会完成
     * */
    override fun tick(): Boolean {
        if (stop) return true
        if (Instant.now().toEpochMilli() >= timeToAction) {
            kotlin.runCatching { action.invoke() }.onFailure { logger.error("Error when invoking LoopAction", it) }
            resetTimer()
        }
        return false
    }

    fun resetTimer() {
        timeToAction = Instant.now().toEpochMilli() + interval
    }
}