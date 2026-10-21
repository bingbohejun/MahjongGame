package doublemoon.mahjonggame.game.mahjong.riichi.player

import doublemoon.mahjonggame.entity.toMahjongTileList
import doublemoon.mahjonggame.game.mahjong.riichi.model.ClaimTarget
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongGameBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongRule
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import doublemoon.mahjonggame.network.mahjong_game.MahjongGamePayload
import doublemoon.mahjonggame.network.sendPayloadToPlayer
import doublemoon.mahjonggame.scheduler.server.ServerScheduler
import doublemoon.mahjonggame.util.delayOnServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text

/**
 * 麻将玩家
 * */
class MahjongPlayer(
    override val entity: ServerPlayerEntity,
) : MahjongPlayerBase() {

    init {
        // 初始化的时候就发数据包给玩家, 用来取得玩家要不要自动理牌
        sendPayloadToPlayer(
            player = entity,
            payload = MahjongGamePayload(behavior = MahjongGameBehavior.AUTO_ARRANGE)
        )
    }

    fun sendMessage(text: Text, overlay: Boolean = true) {
        entity.sendMessage(text, overlay)
    }

    /**
     * 正在等待客户端执行的动作
     * */
    val waitingBehavior = mutableListOf<MahjongGameBehavior>()

    /**
     * 取消等待 [waitingBehavior], 用在游戏结束的时候, 避免游戏结束却还在倒数
     * */
    var cancelWaitingBehavior = false

    /**
     * 在发送数据包给玩家后,
     * 用来等待玩家回传的数据,
     * 后面的 [String] 是拿来装额外资料的,
     * 只有玩家有, 机器人没有
     * */
    var behaviorResult: Pair<MahjongGameBehavior, String>? = null

    /**
     * 玩家不能丢的牌
     * */
    var cannotDiscardTiles = listOf<MahjongTile>()
        private set

    override fun teleport(targetWorld: ServerWorld, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) {
        this.entity.teleport(targetWorld, x, y, z, yaw, pitch)
    }

    /**
     * 要求并让玩家丢牌的功能,
     * 传过去的额外资料是 能不能自动摸切 (不能丢的牌的 [MahjongTile] 编号列表, 交给 [MahjongPlayer.cannotDiscardTiles] 处理),
     * 传回来的额外资料是 要丢的牌的 [MahjongTile] 编号
     * */
    override suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean,
    ): MahjongTile {
        this.cannotDiscardTiles = cannotDiscardTiles
        return waitForBehaviorResult(
            behavior = MahjongGameBehavior.DISCARD,
            extraData = skippable.toString(),
            target = ClaimTarget.SELF,
            skippable = skippable
        ) { behavior, data ->
            this.cannotDiscardTiles = listOf()
            val tileCode = data.toIntOrNull() ?: return@waitForBehaviorResult timeoutTile
            if (behavior == MahjongGameBehavior.DISCARD && tileCode in MahjongTile.entries.toTypedArray().indices) {
                MahjongTile.entries[tileCode]
            } else timeoutTile
        }
    }

    /**
     * 询问玩家是否要吃的功能,
     * 传过去的额外资料是 要进行吃的牌的编号,以及可以与之组成顺子的牌的编号对的列表,
     * 传回来的额外资料是 要与之组成顺子的牌的编号对,
     * */
    override suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = waitForBehaviorResult(
        behavior = MahjongGameBehavior.CHII,
        extraData = Json.encodeToString( //转成 Json 字串传过去
            listOf(
                Json.encodeToString(tile),
                Json.encodeToString(tilePairs)
            )
        ),
        target = target
    ) { behavior, data ->
        val result = runCatching {
            Json.decodeFromString<Pair<MahjongTile, MahjongTile>>(data)
        }.getOrNull() ?: return@waitForBehaviorResult null
        if (behavior == MahjongGameBehavior.CHII && result in tilePairs) result
        else null
    }

    /**
     * 询问玩家是否要碰或吃的功能,
     * 传过去的额外资料是 要进行碰或吃的牌的编号,以及可以与之组成顺子的牌对的列表,最后是可以与之组成刻子的牌对
     * 传回来的额外资料是 如果是要吃->与 [tile] 组成顺子的牌对,碰->原本的 [tile],
     * */
    override suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = waitForBehaviorResult(
        behavior = MahjongGameBehavior.PON_OR_CHII,
        waitingBehavior = listOf(
            MahjongGameBehavior.CHII,
            MahjongGameBehavior.PON,
            MahjongGameBehavior.SKIP
        ),
        extraData = Json.encodeToString( //转成 Json 字串传过去
            listOf(
                Json.encodeToString(tile),
                Json.encodeToString(tilePairsForChii),
                Json.encodeToString(tilePairForPon)
            )
        ),
        target = target
    ) { behavior, data ->
        when (behavior) {
            MahjongGameBehavior.CHII -> {
                val result = runCatching {
                    Json.decodeFromString<Pair<MahjongTile, MahjongTile>>(data)
                }.getOrNull() ?: return@waitForBehaviorResult null
                if (result in tilePairsForChii) result else null
            }
            MahjongGameBehavior.PON -> tile to tile
            else -> null
        }
    }


    /**
     * 询问玩家是否要碰的功能,
     * 传过去的额外资料是 要进行碰的牌 和 可以与之组成刻子的牌对,
     * 传回来的额外资料是 空白
     * */
    override suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Boolean = waitForBehaviorResult(
        behavior = MahjongGameBehavior.PON,
        extraData = Json.encodeToString( //转成 Json 字串传过去
            listOf(
                Json.encodeToString(tile),
                Json.encodeToString(tilePairForPon)
            )
        ),
        target = target
    ) { behavior, _ ->
        behavior == MahjongGameBehavior.PON
    }

    /**
     * 询问玩家是否要暗杠或加杠的功能,
     * 传过去的额外资料是 可以进行暗杠 以及 可以进行加杠的牌的集合 和 规则,
     * 传回来的额外资料是 要暗杠或加杠的 [MahjongTile], null 表示没有要暗杠
     * */
    override suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule,
    ): MahjongTile? = waitForBehaviorResult(
        behavior = MahjongGameBehavior.ANKAN_OR_KAKAN,
        extraData = Json.encodeToString(
            listOf(
                Json.encodeToString(canAnkanTiles),
                Json.encodeToString(canKakanTiles),
                rule.toJsonString()
            )
        ),
        target = ClaimTarget.SELF
    ) { behavior, data ->
        val result = runCatching {
            Json.decodeFromString<MahjongTile>(data)
        }.getOrNull() ?: return@waitForBehaviorResult null
        if (behavior == MahjongGameBehavior.ANKAN_OR_KAKAN) {
            if (result in canAnkanTiles || result in canKakanTiles.unzip().first) result else null
        } else null
    }

    /**
     * 询问玩家是否要明杠的功能,
     * 传过去的额外资料是 要进行明杠的牌 和 规则,
     * 传回来的额外资料是 空白
     *
     * @return 由回传的行为决定是否要碰还是要杠还是跳过
     * */
    override suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior = waitForBehaviorResult(
        behavior = MahjongGameBehavior.MINKAN,
        waitingBehavior = listOf(MahjongGameBehavior.PON, MahjongGameBehavior.MINKAN),
        extraData = Json.encodeToString(
            listOf(
                Json.encodeToString(tile),
                rule.toJsonString()
            )
        ),
        target = target
    ) { behavior, _ ->
        when (behavior) {
            MahjongGameBehavior.PON -> behavior
            MahjongGameBehavior.MINKAN -> behavior
            else -> MahjongGameBehavior.SKIP
        }
    }

    /**
     * 询问玩家是否要立直的功能,
     * 传过去的额外资料是 要丢的牌和丢了之后对应会听的牌,
     * 传回来的额外资料是 空白
     * */
    override suspend fun askToRiichi(tilePairsForRiichi: List<Pair<MahjongTile, List<MahjongTile>>>): MahjongTile? =
        waitForBehaviorResult(
            behavior = MahjongGameBehavior.RIICHI,
            extraData = Json.encodeToString(tilePairsForRiichi),
            target = ClaimTarget.SELF
        ) { behavior, data ->
            val result = runCatching {
                Json.decodeFromString<MahjongTile>(data)
            }.getOrNull() ?: return@waitForBehaviorResult null
            if (behavior == MahjongGameBehavior.RIICHI && result in tilePairsForRiichi.unzip().first) result
            else null
        }

    /**
     * 询问玩家是否要自摸的功能,
     * 传过去的额外资料是 空白,
     * 传回来的额外资料是 空白
     * */
    override suspend fun askToTsumo(): Boolean = waitForBehaviorResult(
        behavior = MahjongGameBehavior.TSUMO,
        extraData = "",
        target = ClaimTarget.SELF
    ) { behavior, _ ->
        behavior == MahjongGameBehavior.TSUMO
    }

    /**
     * 询问玩家是否要抜北 (三麻) 的功能,
     * 传过去的额外资料是 空白,
     * 传回来的额外资料是 空白
     * */
    override suspend fun askToNuki(): Boolean = waitForBehaviorResult(
        behavior = MahjongGameBehavior.NUKI,
        extraData = "",
        target = ClaimTarget.SELF
    ) { behavior, _ ->
        behavior == MahjongGameBehavior.NUKI
    }

    /**
     * 询问玩家是否要荣和的功能,
     * 传过去的额外资料是 要荣和的牌的编号,
     * 传回来的额外资料是 空白
     * */
    override suspend fun askToRon(tile: MahjongTile, target: ClaimTarget): Boolean = waitForBehaviorResult(
        behavior = MahjongGameBehavior.RON,
        extraData = Json.encodeToString(tile),
        target = target
    ) { behavior, _ ->
        behavior == MahjongGameBehavior.RON
    }

    override suspend fun askToKyuushuKyuuhai(): Boolean = waitForBehaviorResult(
        behavior = MahjongGameBehavior.KYUUSHU_KYUUHAI,
        extraData = "",
        target = ClaimTarget.SELF
    ) { behavior, _ ->
        behavior == MahjongGameBehavior.KYUUSHU_KYUUHAI
    }

    /**
     * 发送给玩家行为 [behavior],
     * 传过去的资料分别是 :
     * 1.手牌 [hands]
     * 2.额外资料 [extraData] (转成 [Json] 字串列表后传给玩家)
     * 3.鸣牌对象
     *
     * 最后收到的结果的时候会执行 [onResult], 会传回对应的 [MahjongGameBehavior] 以及字串形式的资料 [String],
     * 超时的话会得到结果 [MahjongGameBehavior.SKIP]
     * */
    private suspend fun <T> waitForBehaviorResult(
        behavior: MahjongGameBehavior,
        waitingBehavior: List<MahjongGameBehavior> = listOf(behavior),
        extraData: String,
        hands: List<MahjongTile> = this.hands.toMahjongTileList(),
        target: ClaimTarget,
        skippable: Boolean = true,
        onResult: (MahjongGameBehavior, String) -> T,
    ): T {
        this.waitingBehavior += waitingBehavior
        if (skippable) this.waitingBehavior += MahjongGameBehavior.SKIP

        // 发送请求
        sendPayloadToPlayer(
            player = entity,
            payload = MahjongGamePayload(
                behavior = behavior,
                hands = hands,
                target = target,
                extraData = extraData
            )
        )

        // 倒数计时并等待回应
        var tBase = basicThinkingTime
        var tExtra = extraThinkingTime
        var count = 0
        var completed = false
        val action = ServerScheduler.scheduleRepeatAction(
            times = (tBase + tExtra) * 20 + 1, //间隔 1 tick (50ms) 的话, 1 sec = 20 ticks
            interval = 0,  // 1000 ms 响应时间太慢, 看起来卡卡的, 这里给 0 ,表示每个 tick 都执行一次
        ) {
            count++
            if (cancelWaitingBehavior || behaviorResult != null || ((tBase + tExtra) <= 0 && count % 20 == 1)) {
                completed = true
            } else if ((tBase + tExtra) > 0 && count % 20 == 1) {
                sendPayloadToPlayer(
                    player = entity,
                    payload = MahjongGamePayload(
                        behavior = MahjongGameBehavior.COUNTDOWN_TIME,
                        extraData = Json.encodeToString<Pair<Int?, Int?>>(tBase to tExtra)
                    )
                )

                if (tBase > 0) {
                    tBase--
                } else if (tExtra > 0) {
                    tExtra--
                }
            }
        }
        while (!completed) delayOnServer(50)
        ServerScheduler.removeQueuedAction(action)

        // 传两个 null 过去表示倒数结束
        sendPayloadToPlayer(
            player = entity,
            payload = MahjongGamePayload(
                behavior = MahjongGameBehavior.COUNTDOWN_TIME,
                extraData = Json.encodeToString<Pair<Int?, Int?>>(null to null)
            )
        )

        val usedExtraTime = extraThinkingTime - tExtra //使用过的额外思考时间
        extraThinkingTime -= usedExtraTime //扣掉用掉的额外思考时间
        val result = behaviorResult ?: (MahjongGameBehavior.SKIP to "")
        behaviorResult = null //最后重置 behaviorResult
        this.waitingBehavior.clear() //重置等待中的动作
        return onResult.invoke(result.first, result.second)
    }
}