package doublemoon.mahjonggame.scheduler

interface ActionBase {

    /**
     * 停止 [action] 用
     * */
    var stop: Boolean

    /**
     * 下次执行 [action] 的时间 (ms)
     * */
    var timeToAction: Long

    /**
     * 每次执行的动作
     * */
    val action: () -> Unit

    /**
     * 每 tick 执行的动作
     *
     * @return true 如果这个 action 完成
     * */
    fun tick(): Boolean
}