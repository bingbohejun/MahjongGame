package doublemoon.mahjonggame.util

import doublemoon.mahjonggame.scheduler.client.ClientScheduler
import doublemoon.mahjonggame.scheduler.server.ServerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn

/**
 * 用来在 [CoroutineScope] 之中,对 伺服器主线程 上使用 [delay]
 */
suspend fun delayOnServer(timeMills: Long) {
    var completed = false
    ServerScheduler.scheduleDelayAction(delay = timeMills) { completed = true }
    while (!completed) delay(10)
}

/**
 * 用来在 [CoroutineScope] 之中,对 客户端主线程 上使用 [delay]
 */
@OnlyIn(Dist.CLIENT)
suspend fun delayOnClient(timeMills: Long) {
    var completed = false
    ClientScheduler.scheduleDelayAction(delay = timeMills) { completed = true }
    while (!completed) delay(10)
}