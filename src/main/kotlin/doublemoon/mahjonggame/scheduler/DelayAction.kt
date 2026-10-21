package doublemoon.mahjonggame.scheduler

import doublemoon.mahjonggame.logger
import java.time.Instant

/**
 * @param delay 要延迟的豪秒数, 单位: ms
 * @param action 延迟后执行的动作
 * */
class DelayAction(
    delay: Long,
    override val action: () -> Unit
) : ActionBase {
    override var stop: Boolean = false
    override var timeToAction: Long = Instant.now().toEpochMilli() + delay

    override fun tick(): Boolean {
        return when {
            stop -> true
            Instant.now().toEpochMilli() >= timeToAction -> {
                kotlin.runCatching { action.invoke() }.onFailure { logger.error("Error when invoking DelayAction", it) }
                true
            }
            else -> false
        }
    }

}