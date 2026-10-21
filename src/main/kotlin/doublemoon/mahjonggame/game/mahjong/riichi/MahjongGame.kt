package doublemoon.mahjonggame.game.mahjong.riichi

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.block.MahjongStool
import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.entity.*
import doublemoon.mahjonggame.game.GameBase
import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.GameStatus
import doublemoon.mahjonggame.game.mahjong.riichi.model.*
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongBot
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayer
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayerBase
import doublemoon.mahjonggame.game.mahjong.sichuan.SichuanScorer
import doublemoon.mahjonggame.logger
import doublemoon.mahjonggame.network.mahjong_game.MahjongGamePayload
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayloadListener
import doublemoon.mahjonggame.network.sendPayloadToPlayer
import doublemoon.mahjonggame.registry.SoundRegistry
import doublemoon.mahjonggame.scheduler.client.ScoreSettleHandler
import doublemoon.mahjonggame.scheduler.client.YakuSettleHandler
import doublemoon.mahjonggame.scheduler.server.ServerScheduler
import doublemoon.mahjonggame.util.delayOnServer
import doublemoon.mahjonggame.util.plus
import doublemoon.mahjonggame.util.sendTitles
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.entity.Entity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.mahjong4j.PersonalSituation
import org.mahjong4j.hands.Kantsu
import org.mahjong4j.tile.TileType
import kotlin.math.abs

/**
 * 日本麻将的主要实现都在这, 允许食断
 *
 * @see <a href="https://github.com/mahjong4j/mahjong4j">使用 Mahjong4j</a>
 * */
class MahjongGame(
    override val world: ServerWorld,
    override val pos: BlockPos,
    var rule: MahjongRule = MahjongRule(),
) : GameBase<MahjongPlayerBase> {

    override val name = Text.translatable("$MOD_ID.game.riichi_mahjong")

    override var status = GameStatus.WAITING

    private val isPlaying: Boolean
        get() = status == GameStatus.PLAYING

    /**
     * 是否是四川麻将 (血战到底: 缺一门 / 自摸限定 / 多人胡牌后继续打)
     * */
    private val isSichuan: Boolean
        get() = rule.gameStyle == GameStyle.SICHUAN

    /**
     * 血战到底: 这一局还没胡牌的玩家,
     * 胡牌的人会退出 (手牌摊开留在桌上), 剩下的人继续打
     * 非四川玩法时就是全部玩家
     * */
    private val activePlayers: List<MahjongPlayerBase>
        get() = if (isSichuan) players.filter { !it.hasWon } else players

    /**
     * 血战到底: 从 [from] 之后找下一个还没胡牌的玩家 (找不到就回传自己)
     * */
    private fun nextActivePlayer(from: MahjongPlayerBase): MahjongPlayerBase {
        val startIndex = seat.indexOf(from)
        if (startIndex < 0) return from
        for (i in 1..seat.size) {
            val candidate = seat[(startIndex + i) % seat.size]
            if (!candidate.hasWon) return candidate
        }
        return from
    }

    /**
     *  第 1 个玩家为 host,可以调整麻将的规则
     * */
    override val players = ArrayList<MahjongPlayerBase>(4)

    private val realPlayers: List<MahjongPlayer>
        get() = players.filterIsInstance<MahjongPlayer>()

    private val botPlayers: List<MahjongBot>
        get() = players.filterIsInstance<MahjongBot>()

    /**
     * 用来操作大部分麻将牌的功能
     * */
    private val board = MahjongBoard(this)

    /**
     * 桌子正中央的座标
     * */
    val tableCenterPos = Vec3d(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)

    /**
     * 座位会随机从 players 编排, 顺序是东南西北, 游戏中的东与这个东是同个方向, 再来照麻将桌逆时针转
     * */
    var seat = mutableListOf<MahjongPlayerBase>()

    /**
     * 从庄家开始按照顺序的 [seat], seatOrderFromDealer[0] 就是庄家
     * */
    private val seatOrderFromDealer: List<MahjongPlayerBase>
        get() = List(seat.size) {
            val dealerIndex = round.round
            seat[(dealerIndex + it) % seat.size]
        }

    /**
     * 目前的回合
     * */
    var round: MahjongRound = MahjongRound()

    /**
     * 牌山剩余张数 (不含王牌区), 供麻将桌中央面板显示 "余 N"
     * 刚配完牌时为 69 (136 - 王牌 14 - 配牌 53)
     * */
    val wallTilesLeft: Int
        get() = board.wall.size

    /** 这个玩法需要的座位数 (三人麻将 = 3, 其余 = 4) */
    val seatCapacity: Int
        get() = if (rule.gameStyle == GameStyle.RIICHI_3P) 3 else 4

    /**
     * “结束游戏”投票: 已同意的真人玩家 UUID。
     * 机器人不会投票, 视为默认同意, 所以只需要所有“真人”玩家同意即可结束。
     * */
    private val endVoteAgreed = mutableSetOf<String>()

    /**
     * 已经加入过的机器人编号, 用来给机器人起名 (【电脑】一、【电脑】二 ...)
     * */
    private var botSerial = 0

    /** 需要几个真人同意 */
    val endVoteNeededCount: Int
        get() = seat.count { it is MahjongPlayer }

    /** 目前有几个真人同意 */
    val endVoteAgreedCount: Int
        get() = seat.count { it is MahjongPlayer && it.uuid in endVoteAgreed }

    /**
     * 玩家用桌面上的“结束”按钮投票结束游戏
     * */
    fun requestEnd(player: ServerPlayerEntity) {
        if (status != GameStatus.PLAYING) return
        if (seat.none { it.uuid == player.uuidAsString }) return // 不在这局的玩家不能投票
        endVoteAgreed += player.uuidAsString
        if (endVoteNeededCount > 0 && endVoteAgreedCount >= endVoteNeededCount) {
            endVoteAgreed.clear()
            end()
        } else {
            syncMahjongTable()
        }
    }

    /**
     * 处理游戏的 [Job],
     * 几乎所有的游戏逻辑都在这里处理
     * */
    private var jobRound: Job? = null

    /**
     * 等待游戏开始的 [Job]
     * */
    private var jobWaitForStart: Job? = null

    /**
     * 当前骰子点数
     * */
    private var dicePoints = 0

    /**
     * 规则发生改变, 在等待中的已准备玩家, 除了 Bot 和 Host->全都取消准备, 并且发送讯息进行提示
     * */
    fun changeRules(rule: MahjongRule) {
        this.rule = rule
        players.forEachIndexed { index, mjPlayer ->
            if (mjPlayer is MahjongPlayer) {
                if (index == 0) {
                    mjPlayer.sendMessage(
                        text = PREFIX + Text.translatable("$MOD_ID.game.message.you_changed_the_rules")
                    )
                } else {
                    mjPlayer.ready = false
                    mjPlayer.sendMessage(
                        text = PREFIX + Text.translatable("$MOD_ID.game.message.host_changed_the_rules")
                    )
                }
            }
        }
    }

    /**
     * 加入机器人到游戏,
     * 第一个加进来的叫「【电脑】一」, 依次往下「【电脑】二」、「【电脑】三」...
     * */
    fun addBot() {
        if (players.none { it is MahjongBot }) botSerial = 0 //机器人全被踢掉了就重新从 1 开始编号
        botSerial++
        val bot = MahjongBot(world = world, pos = tableCenterPos, gamePos = pos)
        //起名, 桌面中央的状态牌和实体头上的名字标签都会显示这个名字
        bot.entity.setCustomName(Text.of(BOT_NAME_PREFIX + botSerial.toChineseNumber()))
        bot.entity.setCustomNameVisible(true)
        players += bot
    }

    /**
     * 让玩家准备 or 取消准备
     * */
    fun readyOrNot(player: ServerPlayerEntity, ready: Boolean) {
        getPlayer(player)?.ready = ready
    }

    /**
     * 剔除 [players] 的 [index] 位置上的玩家
     * */
    fun kick(index: Int) {
        if (index in players.indices) {
            val player = players.removeAt(index)
            if (player is MahjongPlayer) { //是玩家
                player.sendMessage(PREFIX + Text.translatable("$MOD_ID.game.message.be_kick"))
            } else {  //是机器人, 清掉实体
                player.entity.remove(Entity.RemovalReason.DISCARDED)
            }
        }
    }

    /**
     * 玩家加入,
     * 不会重复加入
     * */
    override fun join(player: ServerPlayerEntity) {
        if (GameManager.isInAnyGame(player) || isInGame(player)) return
        players += MahjongPlayer(entity = player)
        if (isHost(player)) players[0].ready = true
    }

    /**
     * 玩家离开,
     * 目前只有离开伺服器或切换世界会自动离开 麻将游戏,
     * "没有限制"超出范围会离开 麻将游戏
     * */
    override fun leave(player: ServerPlayerEntity) {
        if (!GameManager.isInAnyGame(player) || !isInGame(player)) return
        if (isHost(player)) { //如果玩家是 host
            players.find { it is MahjongPlayer && it.entity != player }.apply {
                //找到第一个 不是机器人(即 MahjongPlayer) 也不是当前 host 的玩家, 让他成为 host
                //否则全都是机器人, 清空玩家
                if (this != null) {
                    players.remove(this)
                    players.add(0, this)
                    this.ready = true
                } else {
                    botPlayers.forEach { it.entity.remove(Entity.RemovalReason.DISCARDED) } //清掉机器人实体
                    players.clear()
                }
            }
        }
        players.removeIf { it.entity == player }
    }

    /**
     * 清除或重置大部分东西用,
     * 包括: [board] 之中的牌, 玩家立直状态 and 手牌 etc.
     *
     * @param clearRiichiSticks 是否要清掉玩家的立直棒
     * */
    private fun clearStuffs(clearRiichiSticks: Boolean = true) {
        players.forEach { //清理玩家的状态
            if (clearRiichiSticks) { //如果要清理立直棒
                it.sticks.filter { stick -> stick.scoringStick == ScoringStick.P1000 }
                    .forEach { stick ->
                        stick.remove(Entity.RemovalReason.DISCARDED)
                        it.sticks -= stick
                    }
            }
            it.riichi = false
            it.doubleRiichi = false
            it.hasWon = false
            it.nukiTiles.clear()
            it.nukiDoraCount = 0
            it.kanScoreCollected = 0
            it.hands.clear()
            it.fuuroList.clear()
            it.discardedTiles.clear()
            it.discardedTilesForDisplay.clear()
            it.riichiSengenTile = null
        }
        dicePoints = 0
        board.clear()
    }

    /**
     * 向游戏中的玩家以标题的方式显示目前的回合数
     * */
    private fun showRoundsTitle() {
        val windText = round.wind.toText()
        val countersText = Text.translatable("$MOD_ID.game.repeat_counter", round.honba).formatted(Formatting.YELLOW)
        realPlayers.map { it.entity }.sendTitles(
            title = Text.translatable("$MOD_ID.game.round.title", windText, round.round + 1)
                .formatted(Formatting.GOLD)
                .formatted(Formatting.BOLD),
            subtitle = Text.literal("§c - ") + countersText + "§c - "
        )
    }

    /**
     * 开始目前的 [round],
     * 请在主线程上调用
     * 向客户端发送一些讯息 (像是听的牌, 振听, 或者哪几张牌剩几张)
     * @param clearRiichiSticks 是否要清掉立直棒
     * */
    private fun startRound(clearRiichiSticks: Boolean = true) {
        if (status != GameStatus.PLAYING) return
        val handler = CoroutineExceptionHandler { _, exception -> //目前游戏过程中的例外, 异常情况都暂时先无视
            logger.warn("Something happened, I hope you can report it.", exception)
        }
        jobRound?.cancel()
        jobRound = CoroutineScope(Dispatchers.IO).launch(handler) {
            clearStuffs(clearRiichiSticks = clearRiichiSticks)
            showRoundsTitle()
            syncMahjongTable() //每个 Round 开始时同步
            board.generateAllTilesAndSpawnWall() //产生所有牌

            val dealer = seatOrderFromDealer[0] //这回合的庄家
            var dealerRemaining = false //判断连庄用
            var clearNextRoundRiichiSticks = true //判断下回合要不要清掉立直棒用 (四家立直会保留立直棒)
            var roundExhaustiveDraw: ExhaustiveDraw? = null //判断结束游戏的原因用
            delayOnServer(0) //等待 1 tick 后才整理积棒, 否则可能积棒还没清就整理了, 导致整理的位置不对
            players.forEach { board.resortSticks(it) }
            delayOnServer(1000)

            val dices = rollDice()
            val openDoorPlayerSeatIndexFromDealer = ((dicePoints - 1) % seat.size + round.round) % seat.size
            val openDoorPlayer = seatOrderFromDealer[openDoorPlayerSeatIndexFromDealer] //开门牌所在位置的玩家
            board.assignWallAndHands(dicePoints = dicePoints)
            board.assignDeadWall()
            delayOnServer(500)

            //清除掷出的骰子
            dices.forEach { it.remove(Entity.RemovalReason.DISCARDED) }
            delayOnServer(1000)

            var nextPlayer: MahjongPlayerBase = dealer //庄家开始打牌
            var drawTile = true //这次动作需不需要拿牌
            var drewTile: Boolean //这次动作已经拿牌了吗
            val cannotDiscardTiles = mutableListOf<MahjongTile>() //不能丢的牌的 mahjong4j 编号列表 出现在吃或碰的时候, 不能丢刚吃或刚碰的牌, 每次丢牌后清空

            // 这圈 while 最后要判断 [wall] 还有没有牌, 否则拿最后一张牌的后一张牌的时候会出问题
            roundLoop@ while (isPlaying) {
                // 开始打牌的动作
                val player = nextPlayer //当前执行动作的玩家, (正常是从庄家按顺序开始)
                val seatIndex = seat.indexOf(player)
                val isDealer = dealer == player //是否是庄家
                var timeoutTile = player.hands.last() //超时操作预设丢弃的牌 (预设为手牌最后一张)

                if (drawTile) { // 这次动作需要拿牌
                    var lastTile =
                        if (isDealer && player.discardedTiles.size == 0) player.hands.last() //如果是庄家第一轮->取手牌最后一张牌
                        else board.wall
                            .removeFirst() //如果不是庄家第一轮->从牌山拿第一张牌,并加入手牌
                            .also {
                                player.drawTile(it)
                                board.sortHands(player = player, lastTile = it) //指定最后一张牌, 否则摸的牌会被整理进去
                            }
                    drewTile = true //已经拿过牌了
                    if (player is MahjongBot) delayOnServer(500) //如果这是机器人, 摸牌后会等待 500 毫秒再执行动作

                    // 三麻“抜北”: 摸牌后可以把北拔出去换一张牌, 每张北算 1 番, 可以连续抜
                    if (rule.gameStyle == GameStyle.RIICHI_3P) {
                        while (player.hasNorthTile && player.askToNuki()) {
                            player.nukiNorth() ?: break
                            board.sortNuki(player = player)
                            val replacementTile = board.drawNukiTile(player = player) //从牌山尾补一张
                            board.sortHands(player = player, lastTile = replacementTile)
                            lastTile = replacementTile
                            timeoutTile = replacementTile
                        }
                    }

                    //判断能不能自摸
                    val canWin = player.canWin(
                        winningTile = lastTile.mahjongTile,
                        isWinningTileInHands = true,
                        rule = rule,
                        generalSituation = board.generalSituation,
                        personalSituation = player.getPersonalSituation(isTsumo = true)
                    )
                    if (canWin && player.askToTsumo()) {
                        player.tsumo(tile = lastTile)
                        if (isSichuan) {
                            //四川血战到底: 胡牌的人退出本局, 剩下的人继续打, 直到只剩一家
                            player.hasWon = true
                            player.openHands()
                            syncMahjongTable() //分数变了, 桌面中央面板要跟著更新
                            //只剩一家 或 牌山摸完 -> 本局结束 (要挡住 continue 之后的空牌山摸牌)
                            if (players.count { !it.hasWon } <= 1) break@roundLoop
                            if (board.wall.isEmpty()) {
                                roundExhaustiveDraw = ExhaustiveDraw.NORMAL
                                break@roundLoop
                            }
                            nextPlayer = nextActivePlayer(player)
                            drawTile = true
                            continue@roundLoop
                        }
                        if (isDealer) dealerRemaining = true //庄家自摸的话会连庄
                        break@roundLoop
                    } else { //不是自摸, 判断暗杠
                        //第一巡有九种九牌可以和局
                        if (player.isKyuushuKyuuhai() && player.askToKyuushuKyuuhai()) {
                            //询问是否要九种九牌和局
                            roundExhaustiveDraw = ExhaustiveDraw.KYUUSHU_KYUUHAI
                            break@roundLoop
                        }
                        //加杠跟暗杠应该同时询问
                        //玩家可以选择一直暗杠或加杠, 使用 while 判断
                        var finalRinshanTile: MahjongTileEntity? = null //最后一张摸的岭上牌, null 表示没有暗杠或加杠
                        kanLoop@ while ((player.canKakan || player.canAnkan) && !board.isHoutei && board.kanCount < 4) {
                            val tileToAnkanOrKakan = player.askToAnkanOrKakan(rule)
                            if (tileToAnkanOrKakan != null) { //玩家要暗杠或加杠
                                val isAnkan = tileToAnkanOrKakan in player.tilesCanAnkan.toList()
                                    .toMahjongTileList() //是要暗杠,否则就是加杠
                                val tileEntityToAnkanOrKakan =
                                    player.hands.find { it.mahjongTile == tileToAnkanOrKakan } //要暗杠或加杠的牌
                                if (tileEntityToAnkanOrKakan == null) {
                                    cancel("要暗杠的牌的实体消失, 可能是麻将桌被摧毁")
                                    return@launch
                                }
                                if (isAnkan) player.ankan(tileEntityToAnkanOrKakan) {
                                    it.playSoundAtSeat(soundEvent = SoundRegistry.kan)
                                } else player.kakan(tileEntityToAnkanOrKakan) {
                                    it.playSoundAtSeat(soundEvent = SoundRegistry.kan)
                                }
                                board.sortFuuro(player = player)
                                if (isSichuan) collectSichuanKanScore(player, isAnkan = isAnkan) //刮风下雨
                                val canChankanList = //可以抢杠的玩家列表
                                    if (isAnkan) canChanAnkanList(tileEntityToAnkanOrKakan, player)
                                    else canChanKakanList(tileEntityToAnkanOrKakan, player)
                                if (canChankanList.isNotEmpty()) { //有人可以抢杠
                                    if (canChankanList.size > 1) { //超过 1 人可以荣和,会强制所有可以荣和的玩家都荣和
                                        canChankanList.ron(
                                            target = player,
                                            isChankan = true,
                                            tile = tileEntityToAnkanOrKakan
                                        )
                                        if (dealer in canChankanList) dealerRemaining = true //抢暗杠的人之中有庄家
                                        break@roundLoop
                                    } else { //只有 1 人可以抢暗杠
                                        val canChanKanPlayer = canChankanList[0]
                                        if (canChanKanPlayer.askToRon(
                                                tileEntityToAnkanOrKakan,
                                                canChanKanPlayer.asClaimTarget(player)
                                            )
                                        ) { //询问抢暗杠是否要荣和
                                            mutableListOf(canChanKanPlayer).ron(
                                                target = player,
                                                isChankan = true,
                                                tile = tileEntityToAnkanOrKakan
                                            )
                                            if (dealer == canChanKanPlayer) dealerRemaining = true
                                            break@roundLoop
                                        }
                                    }
                                }
                                val rinshanTile = board.drawRinshanTile(player = player) //摸岭上牌
                                board.sortHands(player = player, lastTile = rinshanTile) //整理并指定最后一张牌为摸到的岭上牌
                                //判断岭上开花
                                val rinshanKaihoh = player.canWin(
                                    winningTile = rinshanTile.mahjongTile,
                                    isWinningTileInHands = true,
                                    rule = rule,
                                    generalSituation = board.generalSituation,
                                    personalSituation = player.getPersonalSituation(
                                        isTsumo = true,
                                        isRinshanKaihoh = true
                                    )
                                )
                                if (rinshanKaihoh) { //岭上开花成立
                                    player.tsumo(isRinshanKaihoh = true, tile = rinshanTile)
                                    if (isDealer) dealerRemaining = true //庄家自摸的话会连庄
                                    break@roundLoop
                                }
                                val isFourKanAbort = //最后才判断 四开杠
                                    //四川麻將允许一个人四杠 (十八罗汉), 不会四开杠流局
                                    if (board.kanCount == 3 && !isSichuan) { //已经杠 3 次
                                        val playerKanCount =
                                            player.fuuroList.count { it.mentsu is Kantsu } //杠的这个玩家杠几次
                                        playerKanCount != 3 //如果不是同一个玩家已经杠了 3 次,准备杠第 4 次->四开杠成立
                                    } else false
                                if (isFourKanAbort) { //四开杠, 直接结束
                                    roundExhaustiveDraw = ExhaustiveDraw.SUUKAIKAN
                                    break@roundLoop
                                }
                                finalRinshanTile = rinshanTile
                            } else {
                                //玩家不要暗杠或加杠
                                break@kanLoop
                            }
                        }
                        //不能或结束暗杠或加杠->让玩家出牌
                        timeoutTile = finalRinshanTile ?: lastTile
                    }
                } else {  // 这次动作不需要拿牌
                    drewTile = false //这次没有拿牌
                    drawTile = true //下次要拿牌
                }

                // 丢牌前判断玩家要不要立直宣言 (四川麻将没有立直)
                val riichiSengen =
                    if (!isSichuan && !board.isHoutei && player.isRiichiable) { //不是 河底 且 能够立直
                        player.askToRiichi() //询问是否要立直宣言, 并选择要丢出去的牌
                    } else null // 没有要立直

                // 限制立直时候只能丢摸到的牌或者暗杠后的岭上牌
                if (player.riichi || player.doubleRiichi) {
                    cannotDiscardTiles += player.hands.toMahjongTileList()
                    cannotDiscardTiles.removeAll { it == timeoutTile.mahjongTile }
                }

                // 让玩家丢牌
                // 四川麻将: 机器人不会主动缺一门, 这里给它一个提示, 让它先把最少的那门丢掉
                if (isSichuan && !player.isRealPlayer) {
                    player.discardHint = sichuanBotDiscardHint(player)
                }
                val tileToDiscard = //玩家丢掉的牌的编号
                    riichiSengen ?: player.askToDiscardTile(
                        timeoutTile = timeoutTile.mahjongTile,
                        cannotDiscardTiles = cannotDiscardTiles,
                        skippable = drewTile //这次有没有拿牌代表客户端能不能 跳过(即自动摸切)
                    )
                player.discardHint = null
                val tileDiscarded = player.discardTile(tileToDiscard) //玩家丢掉的牌
                if (tileDiscarded == null) {
                    cancel("要丢的牌的实体消失, 可能是麻将桌被摧毁") //实体不正常
                    return@launch
                }
                board.discards += tileDiscarded
                board.sortDiscardedTilesForDisplay(player = player, openDoorPlayer = openDoorPlayer) //整理显示用丢牌堆
                board.sortHands(player = player)  //整理手牌
                cannotDiscardTiles.clear()

                // 判断 四风连打
                if (board.isSuufonRenda) {
                    roundExhaustiveDraw = ExhaustiveDraw.SUUFON_RENDA
                    break
                }

                // 判断荣和
                val canRonList = canRonList(tile = tileDiscarded, player)
                if (canRonList.isNotEmpty()) { //有人可以荣和
                    if (canRonList.size > 1) { //超过 1 人可以荣和,会强制所有可以荣和的玩家都荣和
                        canRonList.ron(target = player, tile = tileDiscarded)
                        if (dealer in canRonList) dealerRemaining = true //荣和的人之中有庄家
                        break@roundLoop
                    } else { //只有 1 人可以荣和
                        val canRonPlayer = canRonList[0]
                        if (canRonPlayer.askToRon(tileDiscarded, canRonPlayer.asClaimTarget(player))) { //询问是否要荣和
                            mutableListOf(canRonPlayer).ron(target = player, tile = tileDiscarded)
                            if (dealer == canRonPlayer) dealerRemaining = true
                            break@roundLoop
                        }
                    }
                }

                // 立直宣言要在没有被荣和的情况下才成立
                if (riichiSengen != null) {
                    // 立直棒的点数会在流局或者有人赢牌的时候结算减去
                    player.riichi(riichiSengenTile = tileDiscarded, isFirstRound = board.isFirstRound)
                    board.sortDiscardedTilesForDisplay(player = player, openDoorPlayer = openDoorPlayer)
                    board.putRiichiStick(player = player)
                    player.playSoundAtSeat(SoundRegistry.riichi)
                    //TODO 播放立直动画 (需要再放?
                    if (players.count { it.riichi || it.doubleRiichi } == seat.size) { //全员立直
                        roundExhaustiveDraw = ExhaustiveDraw.SUUCHA_RIICHI
                        break
                    }
                }

                // 能明杠或碰的玩家列表，有 4 杠以后, 这个列表不会有玩家 (即最多 4 杠且必须是 4 杠子的情况才行)
                val canMinKanOrPonList = canMinKanOrPonList(
                    tile = tileDiscarded,
                    seatIndex = seatIndex,
                    discardedPlayer = player
                )

                // 执行明杠或碰两种情况的部分
                var someoneKanOrPon = false
                if (canMinKanOrPonList.isNotEmpty()) { //有人可以明杠 (明杠一定只有一个人能明杠, 而且可以进行明杠的人除了上家以外一定能碰)
                    val canMinKanOrPonPlayer = canMinKanOrPonList[0] // 明杠一定只有一个人能明杠
                    val seatIndexOfCanMinkanOrPonPlayer = seat.indexOf(canMinKanOrPonPlayer)
                    val claimTarget = when (abs(seatIndexOfCanMinkanOrPonPlayer - seatIndex)) { // 取两个座位的距离绝对值
                        //杠或碰的玩家相对丢牌的玩家
                        1 -> ClaimTarget.RIGHT
                        2 -> ClaimTarget.ACROSS
                        else -> ClaimTarget.LEFT // 明杠的时候这情况不可能出现 (不能杠上家), 一出现就是表示错误
                    }

                    // 询问玩家要不要明杠, 明杠跟碰一起询问, 故丢牌的玩家的下家不会被这里问到, 因为他不能明杠上家
                    someoneKanOrPon = when (canMinKanOrPonPlayer.askToMinkanOrPon(
                        tileDiscarded,
                        canMinKanOrPonPlayer.asClaimTarget(player),
                        rule
                    )) {
                        // 玩家要碰
                        MahjongGameBehavior.PON -> {
                            canMinKanOrPonPlayer.pon(tileDiscarded, claimTarget, player) {
                                it.playSoundAtSeat(soundEvent = SoundRegistry.pon)
                            }
                            board.sortFuuro(player = canMinKanOrPonPlayer)
                            nextPlayer = canMinKanOrPonPlayer
                            drawTile = false
                            cannotDiscardTiles += tileDiscarded.mahjongTile
                            true
                        }
                        // 玩家要明杠
                        MahjongGameBehavior.MINKAN -> {
                            canMinKanOrPonPlayer.minkan(tileDiscarded, claimTarget, player) {
                                it.playSoundAtSeat(soundEvent = SoundRegistry.kan)
                            }
                            board.sortFuuro(player = canMinKanOrPonPlayer) //整理副露显示
                            if (isSichuan) collectSichuanKanScore(canMinKanOrPonPlayer, isAnkan = false) //刮风
                            val rinshanTile = board.drawRinshanTile(player = canMinKanOrPonPlayer) //摸岭上牌

                            //整理并指定最后一张牌为摸到的岭上牌
                            board.sortHands(player = canMinKanOrPonPlayer, lastTile = rinshanTile)

                            //判断岭上开花
                            val rinshanKaiHoh = canMinKanOrPonPlayer.canWin(
                                winningTile = rinshanTile.mahjongTile,
                                isWinningTileInHands = true,
                                rule = rule,
                                generalSituation = board.generalSituation,
                                personalSituation = canMinKanOrPonPlayer.getPersonalSituation(
                                    isTsumo = true,
                                    isRinshanKaihoh = true
                                )
                            )

                            // 岭上开花成立
                            if (rinshanKaiHoh) {
                                player.tsumo(isRinshanKaihoh = true, tile = rinshanTile)
                                if (canMinKanOrPonPlayer == dealer) dealerRemaining = true //庄家自摸的话会连庄
                                break@roundLoop
                            }

                            val isFourKanAbort = //最后才判断 四开杠
                                //四川麻將允许一个人四杠 (十八罗汉), 不会四开杠流局
                                if (board.kanCount == 3 && !isSichuan) { //已经杠 3 次
                                    val playerKanCount =
                                        canMinKanOrPonPlayer.fuuroList.count { it.mentsu is Kantsu } //杠的这个玩家杠几次
                                    playerKanCount != 3 //如果是同一个玩家已经杠了 3 次,准备杠第 4 次->四开杠成立
                                } else false
                            if (isFourKanAbort) { //四开杠, 直接结束
                                roundExhaustiveDraw = ExhaustiveDraw.SUUKAIKAN
                                break@roundLoop
                            }
                            nextPlayer = canMinKanOrPonPlayer
                            drawTile = false
                            true
                        }
                        // 玩家选择 pass
                        else -> false
                    }
                }

                // 能碰的玩家列表
                val canPonList = canPonList(tile = tileDiscarded, discardedPlayer = player)
                    .toMutableList()
                    .also { it -= canMinKanOrPonList.toSet() }  // 去除掉与 canMinKanOrPonList 重复的玩家

                // 能吃的玩家列表
                val canChiiList = canChiiList(tile = tileDiscarded, seatIndex = seatIndex, discardedPlayer = player)
                    .toMutableList()

                // 执行碰或吃两种情况的部分
                var someonePonOrChii = false
                if (!someoneKanOrPon && canPonList.isNotEmpty()) {
                    var ponOrChiiResult = false

                    //从丢牌玩家开始照顺序问要不要碰
                    repeat(4) {
                        val seatIndexOfPonOrChiiPlayer = (seatIndex + it) % seat.size
                        val ponOrChiiPlayer = seat[seatIndexOfPonOrChiiPlayer]
                        if (ponOrChiiPlayer in canPonList) {
                            if (ponOrChiiPlayer in canChiiList) { //如果 nPlayer 同时出现可以吃的情况
                                val tilePairToPonOrChii =
                                    ponOrChiiPlayer.askToPonOrChii(
                                        tileDiscarded,
                                        ponOrChiiPlayer.asClaimTarget(player)
                                    )
                                if (tilePairToPonOrChii != null) { //玩家有要碰或吃
                                    if (tilePairToPonOrChii.first == tilePairToPonOrChii.second) { //玩家选择碰
                                        ponOrChiiPlayer.pon(
                                            tileDiscarded,
                                            ClaimTarget.LEFT, //碰或吃同时出现, target 必定为上家
                                            player
                                        ) { here ->
                                            here.playSoundAtSeat(soundEvent = SoundRegistry.pon)
                                        }
                                        cannotDiscardTiles += tileDiscarded.mahjongTile
                                    } else { //玩家选择吃
                                        ponOrChiiPlayer.chii(
                                            tileDiscarded,
                                            tilePairToPonOrChii,
                                            player
                                        ) { here ->
                                            here.playSoundAtSeat(soundEvent = SoundRegistry.chii)
                                        }
                                        val tileDiscardedCode = tileDiscarded.mahjong4jTile.code
                                        val tileDiscardedNumber = tileDiscarded.mahjong4jTile.number
                                        val tileCodeList = mutableListOf(
                                            tileDiscardedCode,
                                            tilePairToPonOrChii.first.mahjong4jTile.code,
                                            tilePairToPonOrChii.second.mahjong4jTile.code
                                        ).also { list -> list.sort() } //正序排列, 由小到大
                                        val indexOfTileDiscarded = tileCodeList.indexOf(tileDiscardedCode)
                                        if (indexOfTileDiscarded == 0 && tileDiscardedNumber + 3 < 9) //吃的这张牌在最前面
                                            cannotDiscardTiles += tileDiscarded.mahjongTile.nextTile.nextTile.nextTile
                                        if (indexOfTileDiscarded == 2 && tileDiscardedNumber - 3 > 1) //吃的这张牌在最后面
                                            cannotDiscardTiles += tileDiscarded.mahjongTile.previousTile.previousTile.previousTile
                                    }
                                    board.sortFuuro(player = ponOrChiiPlayer)
                                    nextPlayer = ponOrChiiPlayer
                                    drawTile = false
                                    ponOrChiiResult = true
                                    return@repeat
                                } else { //玩家没有要碰或吃
                                    canChiiList -= ponOrChiiPlayer //避免重复询问吃的动作
                                }
                            } else { //正常情况
                                val claimTarget = when (seatIndex) {
                                    (seatIndexOfPonOrChiiPlayer + 1) % seat.size -> ClaimTarget.RIGHT
                                    (seatIndexOfPonOrChiiPlayer + 2) % seat.size -> ClaimTarget.ACROSS
                                    (seatIndexOfPonOrChiiPlayer + 3) % seat.size -> ClaimTarget.LEFT
                                    else -> ClaimTarget.SELF //不可能出现的情况, 碰自己的牌
                                }
                                if (ponOrChiiPlayer.askToPon(tileDiscarded, claimTarget)) { //如果问到的玩家要碰
                                    ponOrChiiPlayer.pon(tileDiscarded, claimTarget, player) { here ->
                                        here.playSoundAtSeat(soundEvent = SoundRegistry.pon)
                                    }
                                    board.sortFuuro(player = ponOrChiiPlayer)
                                    nextPlayer = ponOrChiiPlayer
                                    drawTile = false
                                    cannotDiscardTiles += tileDiscarded.mahjongTile
                                    ponOrChiiResult = true
                                    return@repeat
                                }
                            }
                        }
                    }
                    someonePonOrChii = ponOrChiiResult
                }

                // 执行吃的部分
                var someoneChii = false
                if (!someoneKanOrPon && !someonePonOrChii && canChiiList.isNotEmpty()) {
                    var chiiResult = false
                    val canChiiPlayer = canChiiList[0] //只有一个玩家能吃
                    val askToChiiResult =
                        canChiiPlayer.askToChii(tileDiscarded, canChiiPlayer.asClaimTarget(player))
                    if (askToChiiResult != null) {
                        val tileDiscardedCode = tileDiscarded.mahjong4jTile.code
                        val tileDiscardedNumber = tileDiscarded.mahjong4jTile.number
                        val tileCodeList = mutableListOf(
                            tileDiscardedCode,
                            askToChiiResult.first.mahjong4jTile.code,
                            askToChiiResult.second.mahjong4jTile.code
                        ).also { it.sort() } //正序排列, 由小到大
                        val indexOfTileDiscarded = tileCodeList.indexOf(tileDiscardedCode)
                        canChiiPlayer.chii(tileDiscarded, askToChiiResult, player) {
                            it.playSoundAtSeat(soundEvent = SoundRegistry.chii)
                        }
                        board.sortFuuro(player = canChiiPlayer)
                        nextPlayer = canChiiPlayer
                        drawTile = false
                        cannotDiscardTiles += tileDiscarded.mahjongTile
                        if (indexOfTileDiscarded == 0 && tileDiscardedNumber + 3 < 9) //吃的这张牌在最前面
                            cannotDiscardTiles += tileDiscarded.mahjongTile.nextTile.nextTile.nextTile
                        if (indexOfTileDiscarded == 2 && tileDiscardedNumber - 3 > 1) //吃的这张牌在最后面
                            cannotDiscardTiles += tileDiscarded.mahjongTile.previousTile.previousTile.previousTile
                        chiiResult = true
                    }
                    someoneChii = chiiResult
                }

                // 在没有人可以吃碰杠的情况下, 会给予一点延迟, 让每次玩家丢牌后都有一点点延迟, 看起来比较自然
                if (canMinKanOrPonList.isEmpty() && canPonList.isEmpty() && canChiiList.isEmpty()) {
                    delayOnServer(MIN_WAITING_TIME) //这个延迟待调整
                }

                // 没有任何人进行 吃 碰 杠
                if (!someoneKanOrPon && !someonePonOrChii && !someoneChii) {
                    nextPlayer = nextActivePlayer(player) //轮到下家 (血战到底会跳过已经胡牌的人)
                }

                // 如果牌山没牌的话会直接结束这 Round
                if (board.wall.size == 0) {
                    roundExhaustiveDraw = ExhaustiveDraw.NORMAL
                    break@roundLoop
                }
            }

            // 如果是流局的话
            if (isSichuan) {
                //四川血战到底: 每胡一次已经即时结算, 这里只处理本局结束 (只剩一家 或 牌山摸完) 的 查大叫 / 花猪
                bloodBattleSettlement()
                dealerRemaining = false //血战到底不连庄, 庄家固定轮转
            } else if (roundExhaustiveDraw != null) {
                dealerRemaining = if (roundExhaustiveDraw == ExhaustiveDraw.NORMAL) { //正常流局
                    val nagashiPlayers = canNagashiManganList()
                    //有人流局满贯->直接让可以流局满贯的玩家直接流局满贯, 并按照流局结束这局 (意思是庄家有听牌才连庄)
                    if (nagashiPlayers.isNotEmpty()) nagashiPlayers.nagashiMangan()
                    dealer.isTenpai //庄家听牌才会连庄
                } else { //特殊流局
                    true //特殊流局庄家直接连庄
                }
                clearNextRoundRiichiSticks = false //只要流局就不会回收立直棒
                roundDraw(roundExhaustiveDraw)
            }

            //结束后等待一下
            delayOnServer(3000L)

            //准备进行下一回合
            if (!round.isAllLast(rule)) { //不是 AllLast
                if (dealerRemaining) {//有连庄就本场 + 1
                    board.addHonbaStick(player = dealer)
                    round.honba++
                } else { //没连庄就换庄家
                    board.removeHonbaSticks(player = dealer)
                    round.nextRound(seatCount = seat.size)
                }
                startRound(clearRiichiSticks = clearNextRoundRiichiSticks)
            } else {  //是 AllLast
                //AllLast 要考虑连庄,和第一点数不足 南/西入的问题
                if (players.none { it.points >= rule.minPointsToWin }) {
                    //没有任何玩家的点数大于 1 位必要点数->继续游戏直到有人大于 1 位必要点数,玩家可以连庄
                    if (dealerRemaining) { //庄家有连庄
                        board.addHonbaStick(player = dealer)
                        round.honba++
                        startRound(clearRiichiSticks = clearNextRoundRiichiSticks)
                    } else { //庄家没连庄
                        //最多 AllLast 只能持续一个风
                        board.removeHonbaSticks(player = dealer)
                        val finalRound = rule.length.finalRound
                        if (round.wind == finalRound.first && round.round == finalRound.second) {
                            //是最后一局->结束游戏
                            showGameResult()
                            end()
                        } else {
                            //不是最后一局->继续下一场
                            round.nextRound(seatCount = seat.size)
                            startRound(clearRiichiSticks = clearNextRoundRiichiSticks)
                        }
                    }
                } else {
                    //至少一个玩家的点数大于 1 位必要点数->结束游戏
                    showGameResult()
                    end()
                }
            }
        }
    }

    /**
     * 这局的结果,
     * 只会对 [ExhaustiveDraw] 进行处理, 会考虑 当前的立直状态 加减分数
     * @param draw 这局结束的原因
     * */
    private suspend fun roundDraw(draw: ExhaustiveDraw) {
        //计算流局情况下的分数变化, 并处理成列表
        val scoreList = buildList { //计算分数列表
            if (draw != ExhaustiveDraw.NORMAL) { //不是正常流局->存入原本的分数
                players.forEach {
                    val riichiStickPoints = if (it.riichi || it.doubleRiichi) ScoringStick.P1000.point else 0
                    this += ScoreItem(
                        mahjongPlayer = it,
                        scoreOrigin = it.points,
                        scoreChange = -riichiStickPoints
                    )
                    it.points -= riichiStickPoints
                    if (draw == ExhaustiveDraw.KYUUSHU_KYUUHAI && it.isKyuushuKyuuhai()) { //如果是九种九牌才要翻出手牌
                        it.openHands()
                    } else it.closeHands()
                }
            } else { //是正常流局
                val tenpaiCount = players.count { it.isTenpai } //听牌有几人
                if (tenpaiCount == 0) { //没有人听->点数变动 = 0
                    players.forEach {
                        this += ScoreItem(
                            mahjongPlayer = it,
                            scoreOrigin = it.points,
                            scoreChange = 0
                        )
                        it.closeHands()
                    }
                } else { //有人听->计算不听罚符, 不听者向听牌者支付合计 3000 点
                    val notenCount = 4 - tenpaiCount //计算没听的有几人
                    val notenBappu = 3000 / notenCount //不听罚符
                    val bappuGet = 3000 / tenpaiCount //得到的罚符
                    players.forEach {
                        if (it.isTenpai) { //这个玩家有听牌
                            val riichiStickPoints =
                                if (it.riichi || it.doubleRiichi) ScoringStick.P1000.point else 0 //这个玩家这轮有没有立直,有->还要减去立直棒的点数
                            this += ScoreItem(
                                mahjongPlayer = it,
                                scoreOrigin = it.points,
                                scoreChange = bappuGet - riichiStickPoints
                            )
                            it.points += bappuGet
                            it.points -= riichiStickPoints
                            it.openHands()
                        } else { //这个玩家没有听牌
                            this += ScoreItem(
                                mahjongPlayer = it,
                                scoreOrigin = it.points,
                                scoreChange = -notenBappu
                            )
                            it.points -= notenBappu
                            it.closeHands()
                        }
                    }
                }
            }
        }
        delayOnServer(3000) //玩家倒牌后小延迟一下, 再发送和局, 看起来比较自然
        realPlayers.forEach {  //寄送结算数据包以及分数列表
            sendPayloadToPlayer(
                player = it.entity,
                payload = MahjongGamePayload(
                    behavior = MahjongGameBehavior.SCORE_SETTLEMENT,
                    extraData = Json.encodeToString(
                        ScoreSettlement(
                            titleTranslateKey = draw.translateKey,
                            scoreList = scoreList
                        )
                    )
                )
            )
        }
        delayOnServer(ScoreSettleHandler.defaultTime * 1000L) //基本最少会等待跟结算画面一样长的秒数
    }

    /**
     * 四川麻将的「刮风下雨」: 杠牌后立刻收分 (不等胡牌)
     * 明杠(刮风) 每家 1 分, 暗杠(下雨) 每家 2 分, 1 分 = [SichuanScorer.BASE_SCORE]
     * 已经胡牌退出的人不用付分
     *
     * @param isAnkan 是否暗杠
     * */
    private fun collectSichuanKanScore(kanPlayer: MahjongPlayerBase, isAnkan: Boolean) {
        val perPlayer = SichuanScorer.BASE_SCORE * if (isAnkan) 2 else 1
        val payers = activePlayers.filter { it != kanPlayer }
        if (payers.isEmpty()) return
        val total = perPlayer * payers.size
        kanPlayer.kanScoreCollected += total
        payers.forEach { it.points -= perPlayer }
        kanPlayer.points += total
        syncMahjongTable() //分数变了, 桌面中央面板要跟着更新
    }

    /**
     * 四川血战到底的本局结算,
     * 胡牌时的分数在每次胡牌时就已经即时结算过了, 这里只处理“本局结束”时剩下那些还没胡牌的人:
     *
     * - 花猪: 手上还有 万/筒/条 三门牌的人, 向其他还在场的玩家各赔 [HUA_ZHU_PENALTY] 点
     * - 查大叫: 没听牌的人, 向每个听牌的人赔 [CHA_DA_JIAO_PENALTY] 点
     * - 退税: 本局没胡牌的人, 把这一局收到的杠分(刮风下雨)退还给其他玩家
     *
     * (固定分是简化版, 真正的血战会按最大番数赔付)
     * */
    private suspend fun bloodBattleSettlement() {
        val active = activePlayers //还没胡牌的人
        active.forEach { it.openHands() } //查大叫要摊牌

        val changes = players.associate { it.uuid to 0 }.toMutableMap()
        fun add(player: MahjongPlayerBase, delta: Int) {
            changes[player.uuid] = (changes[player.uuid] ?: 0) + delta
        }

        //花猪: 手上还有三门牌
        if (active.size > 1) {
            active.filter { !it.isMissingOneSuitNow }.forEach { pig ->
                active.filter { it != pig }.forEach { other ->
                    add(pig, -HUA_ZHU_PENALTY)
                    add(other, HUA_ZHU_PENALTY)
                }
            }
        }

        //查大叫: 没听牌的赔听牌的
        val tenpaiPlayers = active.filter { it.isSichuanTenpai() }
        val notenPlayers = active.filter { it !in tenpaiPlayers }
        if (tenpaiPlayers.isNotEmpty() && notenPlayers.isNotEmpty()) {
            notenPlayers.forEach { noten ->
                tenpaiPlayers.forEach { tenpai ->
                    add(noten, -CHA_DA_JIAO_PENALTY)
                    add(tenpai, CHA_DA_JIAO_PENALTY)
                }
            }
        }

        //退税: 本局没胡牌的人, 把这一局收到的杠分(刮风下雨)退回去, 平摊给其他玩家
        active.forEach { refundPlayer ->
            val refund = refundPlayer.kanScoreCollected
            refundPlayer.kanScoreCollected = 0
            if (refund <= 0) return@forEach
            val others = players.filter { it != refundPlayer }
            if (others.isEmpty()) return@forEach
            val perPlayer = refund / others.size
            val remainder = refund - perPlayer * others.size //除不尽的余数补在最前面的玩家, 保证总分守恒
            add(refundPlayer, -refund)
            others.forEachIndexed { index, other ->
                add(other, perPlayer + if (index < remainder) 1 else 0)
            }
        }

        val scoreList = players.map { player ->
            val change = changes[player.uuid] ?: 0
            val scoreItem = ScoreItem(
                mahjongPlayer = player,
                scoreOrigin = player.points,
                scoreChange = change
            )
            player.points += change
            scoreItem
        }
        delayOnServer(2000) //先让玩家看一下摊牌
        realPlayers.forEach {
            sendPayloadToPlayer(
                player = it.entity,
                payload = MahjongGamePayload(
                    behavior = MahjongGameBehavior.SCORE_SETTLEMENT,
                    extraData = Json.encodeToString(
                        ScoreSettlement(
                            titleTranslateKey = BLOOD_BATTLE_SETTLEMENT_KEY,
                            scoreList = scoreList
                        )
                    )
                )
            )
        }
        delayOnServer(ScoreSettleHandler.defaultTime * 1000L)
    }

    /**
     * 四川麻将: 这个玩家现在是否听牌,
     * 和日本麻将的 [MahjongPlayerBase.isTenpai] 不同, 这里要能“真的胡”才算
     * (必须缺一门, 也要满足起胡番数)
     * */
    private fun MahjongPlayerBase.isSichuanTenpai(): Boolean {
        if (hands.size % 3 != 1) return false //手牌不是 3n+1 张 (13 张) 就不能判断听牌, 避免 mahjong4j 报错
        return SICHUAN_TILES.any { tile ->
            canWin(
                winningTile = tile,
                isWinningTileInHands = false,
                rule = rule,
                generalSituation = board.generalSituation,
                personalSituation = getPersonalSituation(isTsumo = true)
            )
        }
    }

    /**
     * 四川麻将的结算: 用 [SichuanScorer] 算番型, 再包成 [YakuSettlement] 让客户端用同一套画面显示
     * (四川没有宝牌/里宝牌/符, 那些栏位一律给空)
     * */
    private fun MahjongPlayerBase.calcSichuanSettlement(
        winningTile: MahjongTile,
        isRinshanKaihoh: Boolean,
    ): YakuSettlement {
        //天胡: 庄家第一巡 (还没人打过牌) 就自摸
        //地胡: 闲家第一巡 (自己还没打过牌, 且庄家只打了 1 张) 就自摸
        val isDealer = this == seatOrderFromDealer[0]
        val isHeavenly = isDealer && this.discardedTiles.isEmpty() && board.discards.isEmpty()
        val isDihu = !isDealer && this.discardedTiles.isEmpty() && board.discards.size == 1
        val result = SichuanScorer.calculate(
            hands = this.hands.toMahjongTileList(),
            fuuroList = this.fuuroList,
            winningTile = winningTile,
            isTsumo = true,
            isRinshan = isRinshanKaihoh,
            isHaitei = board.wall.isEmpty(),
            isHeavenly = isHeavenly,
            isDihu = isDihu,
        )
        return YakuSettlement(
            mahjongPlayer = this,
            yakuList = emptyList(),
            yakumanList = emptyList(),
            doubleYakumanList = emptyList(),
            winningTile = winningTile,
            fuuroList = this.fuuroList.map { fuuro ->
                (fuuro.mentsu is Kantsu && !fuuro.mentsu.isOpen) to fuuro.tileMjEntities.toMahjongTileList()
            },
            doraIndicators = emptyList(),
            uraDoraIndicators = emptyList(),
            fu = 0,
            han = result.fan,
            score = result.score,
            sichuan = true,
            sichuanFans = result.fans.map { SichuanFan(it.key, it.fan) },
            sichuanCapped = result.capped,
        )
    }

    /**
     * 四川麻将的机器人出牌提示:
     * 手上还有三门牌的时候, 从“张数最少”的那一门挑一张丢掉 (优先丢数字小的),
     * 让机器人会主动往缺一门走; 已经缺一门就回传 null (照原本的摸切)
     * */
    private fun sichuanBotDiscardHint(player: MahjongPlayerBase): MahjongTile? {
        if (player.isMissingOneSuitNow) return null
        val hands = player.hands.toMahjongTileList()
        val fewestSuit = hands
            .filter {
                it.mahjong4jTile.type == TileType.MANZU ||
                        it.mahjong4jTile.type == TileType.PINZU ||
                        it.mahjong4jTile.type == TileType.SOHZU
            }
            .groupingBy { it.mahjong4jTile.type }
            .eachCount()
            .minByOrNull { it.value }?.key ?: return null
        return hands
            .filter { it.mahjong4jTile.type == fewestSuit }
            .minByOrNull { it.mahjong4jTile.number }
    }

    /**
     * 显示游戏结果
     * */
    private fun showGameResult() {
        val scoreList = players.map {
            ScoreItem(
                mahjongPlayer = it,
                scoreOrigin = it.points,
                scoreChange = 0
            )
        }

        realPlayers.forEach {
            // 寄送结算数据包以及分数列表
            sendPayloadToPlayer(
                player = it.entity,
                payload = MahjongGamePayload(
                    behavior = MahjongGameBehavior.SCORE_SETTLEMENT,
                    extraData = Json.encodeToString(
                        ScoreSettlement(
                            titleTranslateKey = "$MOD_ID.game.game_over",
                            scoreList = scoreList
                        )
                    )
                )
            )

            // 会多传一份给玩家的游戏结算文字讯息
            val mahjongText = (Text.translatable("$MOD_ID.game.riichi_mahjong") + ":")
                .formatted(Formatting.YELLOW)
                .formatted(Formatting.BOLD)
//            val tooltipRuleString = rule.toTexts().joinToString(separator = "\n") { text -> text.string } //这方法会让 text.string 没有包含色码, 所以不适用
            val ruleTooltip = Text.literal("").also {
                rule.toTexts().forEachIndexed { index, text ->
                    if (index > 0) it.append("\n")
                    it.append(text)
                }
            }
            val ruleHoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, ruleTooltip)
            val ruleStyle = Style.EMPTY.withColor(Formatting.GREEN).withHoverEvent(ruleHoverEvent)
            val ruleText = (Text.literal("§a[") + Text.translatable("$MOD_ID.game.rules") + "§a]").fillStyle(ruleStyle)
            val scoreText = Text.translatable("$MOD_ID.game.score")
            it.sendMessage(
                Text.literal("§2------------------------------------------")
                        + "\n" + mahjongText
                        + "\n§7 - " + ruleText
                        + "\n§a" // 空一行
                        + "\n§6" + scoreText + ":"
            )
            players.sortedByDescending { player -> player.points }.forEachIndexed { index, mjPlayer ->
                val displayNameText = Text.literal(mjPlayer.displayName).formatted(Formatting.AQUA)
                it.sendMessage(Text.literal("§7 - §e${index + 1}. ") + displayNameText + "  §c${mjPlayer.points}")
            }
            it.sendMessage(Text.of("§2------------------------------------------"))
        }
    }

    /**
     * 在玩家的座位上播放声音,
     * (因为玩家有可能离开座位我才这样写,其实好像也没必要(?))
     * */
    private fun MahjongPlayerBase.playSoundAtSeat(
        soundEvent: SoundEvent,
        category: SoundCategory = SoundCategory.PLAYERS,
        volume: Float = 1f,
        pitch: Float = 1f,
    ) {
        val seatIndex = seat.indexOf(this)
        val x = pos.x + 0.5 + if (seatIndex == 0) 2 else if (seatIndex == 2) -2 else 0
        val y = pos.y.toDouble()
        val z = pos.z + 0.5 + if (seatIndex == 1) -2 else if (seatIndex == 3) 2 else 0
        world.playSound(
            null,
            x,
            y,
            z,
            soundEvent,
            category,
            volume,
            pitch
        )
    }

    /**
     * 玩家是否满足九种九牌的条件,
     * 第一巡 且 幺九牌种类 >= 9
     * */
    private fun MahjongPlayerBase.isKyuushuKyuuhai(): Boolean = board.isFirstRound && numbersOfYaochuuhaiTypes >= 9

    /**
     * 以 [this] 为基础, 按 [target] 相对的位置返回 鸣牌的对象
     * */
    private fun MahjongPlayerBase.asClaimTarget(target: MahjongPlayerBase): ClaimTarget {
        val seatIndex = seat.indexOf(this)
        return when (target) {
            seat[(seatIndex + 1) % seat.size] -> ClaimTarget.RIGHT
            seat[(seatIndex + 2) % seat.size] -> ClaimTarget.ACROSS
            seat[(seatIndex + 3) % seat.size] -> ClaimTarget.LEFT
            else -> ClaimTarget.SELF //除非 target 是自己, 否则不可能发生这个条件
        }
    }

    /**
     * 根据 [tile] 判断有没有人可以 碰,
     * 任何位置的玩家都可以碰
     * TODO 目前有不确定的情况->ex: 自家有 2 张东, 下家打了 1 张你没碰, 但是对面打了 1 张你突然想碰, 这样能碰吗? (就是 过水碰 的状况)
     *
     * @return 可以碰的玩家的列表
     * */
    private fun canPonList(
        tile: MahjongTileEntity,
        discardedPlayer: MahjongPlayerBase,
    ): List<MahjongPlayerBase> =
        if (!board.isHoutei) players.filter { it != discardedPlayer && !it.hasWon && it.canPon(tile) }
        else emptyList()

    /**
     * 根据 [tile] 判断有没有人可以 明杠,
     * 除了丢出 [tile] 的玩家的下家以外都可以明杠 (除非已经有 4 杠了)
     *
     *
     * @param seatIndex 丢出 [tile] 的玩家的座位编号
     * @return 可以明杠的玩家的列表
     * */
    private fun canMinKanOrPonList(
        tile: MahjongTileEntity,
        discardedPlayer: MahjongPlayerBase,
        seatIndex: Int = seat.indexOf(discardedPlayer),
    ): List<MahjongPlayerBase> =
        if (board.kanCount < 4 && !board.isHoutei) // 4 杠前或者不是河底都可以明杠
            players.filter {
                it != discardedPlayer && !it.hasWon && it.canMinkan(tile) && it != seat[(seatIndex + 1) % seat.size]
            }
        else emptyList()

    /**
     * 根据 [tile] 判断有没有人可以 吃
     * 只有丢出 [tile] 的玩家的下家可以吃
     *
     * @param seatIndex 丢出 [tile] 的玩家的座位编号
     * @return 可以明杠的玩家的列表
     * */
    private fun canChiiList(
        tile: MahjongTileEntity,
        discardedPlayer: MahjongPlayerBase,
        seatIndex: Int = seat.indexOf(discardedPlayer),
    ): List<MahjongPlayerBase> =
        if (rule.gameStyle == GameStyle.SICHUAN) emptyList() //四川麻将不能吃
        else if (!board.isHoutei) players.filter {
            it != discardedPlayer && !it.hasWon && it.canChii(tile) && it == seat[(seatIndex + 1) % seat.size]
        }
        else emptyList()

    /**
     * 根据 [tile] 判断有没有人可以 荣和
     *
     * @return 可以荣和的玩家的列表
     * */
    private fun canRonList(
        tile: MahjongTileEntity,
        discardedPlayer: MahjongPlayerBase,
        isChanKan: Boolean = false,
    ): List<MahjongPlayerBase> =
        if (rule.gameStyle == GameStyle.SICHUAN) emptyList() //四川麻将“自摸限定”: 只能自摸, 不能荣和 (抢杠也一并关掉)
        else players.filter {
            if (it == discardedPlayer) return@filter false //丢牌玩家不能和自己的牌
            if (it.hasWon) return@filter false //血战到底: 已经胡牌退出的人不能再鸣牌
            if (it.discardedTiles.isEmpty() && it.isMenzenchin) return@filter false //如果这个玩家连牌都没丢过而且门前清, 先跳过
            val canWin = it.canWin(
                winningTile = tile.mahjongTile,
                isWinningTileInHands = false,
                rule = rule,
                generalSituation = board.generalSituation,
                personalSituation = it.getPersonalSituation(isChankan = isChanKan)
            )
            val isFuriten = it.isFuriten(tile = tile, discards = board.discards) //需要没有振听
            (canWin && !isFuriten)
        }

    /**
     * 根据 [kanTile] 判断有没有人可以 抢加杠
     *
     * @return 可以抢杠的玩家的列表
     * */
    private fun canChanKakanList(
        kanTile: MahjongTileEntity,
        discardedPlayer: MahjongPlayerBase,
    ): List<MahjongPlayerBase> =
        canRonList(tile = kanTile, isChanKan = true, discardedPlayer = discardedPlayer)

    /**
     * 根据 [kanTile] 判断有没有人可以 抢暗杠
     * 只有国士无双才能抢暗杠
     *
     * @return 可以抢杠的玩家的列表
     * */
    private fun canChanAnkanList(
        kanTile: MahjongTileEntity,
        discardedPlayer: MahjongPlayerBase,
    ): List<MahjongPlayerBase> =
        canChanKakanList(
            kanTile = kanTile,
            discardedPlayer = discardedPlayer
        ).filter { it.isKokushimuso(kanTile.mahjong4jTile) }

    /**
     * 当前能够流局满贯的玩家
     * */
    private fun canNagashiManganList(): List<MahjongPlayerBase> =
        if (board.wall.isEmpty()) { //牌山必须为空
            players.filter {
                val discardedTilesNoCall = it.discardedTiles == it.discardedTilesForDisplay //弃牌堆的牌全都在
                val discardedTilesAllYaochu = it.discardedTiles.all { tile -> tile.mahjong4jTile.isYaochu } //全部都打幺九牌
                discardedTilesNoCall && discardedTilesAllYaochu
            }
        } else listOf()

    /**
     * 玩家荣和其他玩家,
     * @param target 放枪的对象
     * @param tile 枪牌
     * */
    private suspend fun List<MahjongPlayerBase>.ron(
        target: MahjongPlayerBase,
        isChankan: Boolean = false,
        tile: MahjongTileEntity,
    ) {
        val yakuSettlementList = mutableListOf<YakuSettlement>()
        val scoreList = mutableListOf<ScoreItem>()
        val honbaScore = round.honba * 300 //场棒的分数
        var totalScore = honbaScore //这个总分是被胡的人将要扣的分数, 先算上场棒的分数
        val allRiichiStickQuantity = players.sumOf { it.riichiStickAmount } //所有立直棒的数量
        //额外分数->计算场上的积棒, 立直棒一根 1000, 本场棒一根 300 (只会给头跳, 即摸牌顺序离被荣和的人最近的那一位)
        val extraScore = allRiichiStickQuantity * ScoringStick.P1000.point + honbaScore
        val seatOrderFromTarget = List(4) { //从被荣和的玩家开始排序玩家位置
            val targetIndex = seat.indexOf(target)
            seat[(targetIndex + it) % seat.size]
        }
        val atamahanePlayer = seatOrderFromTarget.find { it in this } //找到头跳的玩家
        this.forEach { //荣和的玩家
            it.playSoundAtSeat(soundEvent = SoundRegistry.ron) //多个人荣和会同时播放声音
            it.openHands()
            val isDealer = it == seatOrderFromDealer[0]
            val isAtamahanePlayer = it == atamahanePlayer
            val settlement = it.calcYakuSettlementForWin(
                winningTile = tile.mahjongTile,
                isWinningTileInHands = false,
                rule = rule,
                generalSituation = board.generalSituation,
                personalSituation = it.getPersonalSituation(isChankan = isChankan),
                doraIndicators = board.doraIndicators.map { entity -> entity.mahjongTile },
                uraDoraIndicators = board.uraDoraIndicators.map { entity -> entity.mahjongTile }
            )
            yakuSettlementList += settlement
            //对分数做加减的动作
            val riichiStickPoints =
                if (it.riichi || it.doubleRiichi) ScoringStick.P1000.point else 0 //这个玩家这轮有没有立直,有->还要减去立直棒的点数
            val basicScore = (settlement.score * if (isDealer) 1.5 else 1.0).toInt() //如果是庄家有 1.5 倍的分数
            val score = basicScore - riichiStickPoints + if (isAtamahanePlayer) extraScore else 0 //头跳玩家会拿走所有积棒
            scoreList += ScoreItem(
                mahjongPlayer = it,
                scoreOrigin = it.points,
                scoreChange = score
            )
            it.points += score
            totalScore += basicScore //总分除去已经计算的场棒分数, 只会累计基本分数
        }
        target.also { //被荣和的玩家
            val riichiStickPoints =
                if (it.riichi || it.doubleRiichi) ScoringStick.P1000.point else 0 //这个玩家这轮有没有立直,有->还要减去立直棒的点数
            scoreList += ScoreItem(
                mahjongPlayer = it,
                scoreOrigin = it.points,
                scoreChange = -(totalScore + riichiStickPoints)
            )
            it.points -= (totalScore + riichiStickPoints)
        }
        val remainingPlayers = players.toMutableList().also { it -= this.toSet(); it -= target } //除了 荣和 和 被荣和 以外的玩家
        remainingPlayers.forEach {
            val riichiStickPoints =
                if (it.riichi || it.doubleRiichi) ScoringStick.P1000.point else 0 //这个玩家这轮有没有立直,有->还要减去立直棒的点数
            scoreList += ScoreItem(mahjongPlayer = it, scoreOrigin = it.points, scoreChange = -riichiStickPoints)
            it.points -= riichiStickPoints
        }
        val scoreSettlement = ScoreSettlement(
            titleTranslateKey = MahjongGameBehavior.RON.translateKey,
            scoreList = scoreList
        )
        realPlayers.sendSettlePacketAndDelay(yakuSettlementList, scoreSettlement)
    }

    /**
     * 玩家自摸
     * @param tile 自摸牌
     * */
    private suspend fun MahjongPlayerBase.tsumo(
        isRinshanKaihoh: Boolean = false,
        tile: MahjongTileEntity,
    ) {
        playSoundAtSeat(soundEvent = SoundRegistry.tsumo)
        val yakuSettlementList = mutableListOf<YakuSettlement>()
        val scoreList = mutableListOf<ScoreItem>()
        val allRiichiStickQuantity = players.sumOf { it.riichiStickAmount } //所有立直棒的数量
        //额外分数->计算场上的积棒, 立直棒一根 1000, 本场棒一根 300 (自摸全拿)
        val honbaScore = round.honba * 300 //场棒的分数
        val playerRiichiStickPoints =
            if (this.riichi || this.doubleRiichi) ScoringStick.P1000.point else 0 //这个自摸的玩家这轮有没有立直,有->还要减去立直棒的点数
        //以下对自摸的玩家做计算
        val extraScore = allRiichiStickQuantity * ScoringStick.P1000.point + honbaScore
        val tsumoPlayerIsDealer = this == seatOrderFromDealer[0]
        val settlement = if (isSichuan) {
            //四川麻将走另一套番型计分器 (不看日麻的役/符)
            this.calcSichuanSettlement(winningTile = tile.mahjongTile, isRinshanKaihoh = isRinshanKaihoh)
        } else {
            this.calcYakuSettlementForWin(
                winningTile = tile.mahjongTile,
                isWinningTileInHands = true,
                rule = rule,
                generalSituation = board.generalSituation,
                personalSituation = this.getPersonalSituation(isTsumo = true, isRinshanKaihoh = isRinshanKaihoh),
                doraIndicators = board.doraIndicators.map { entity -> entity.mahjongTile },
                uraDoraIndicators = board.uraDoraIndicators.map { entity -> entity.mahjongTile }
            )
        }
        yakuSettlementList += settlement
        val basicScore = settlement.score //settlement 已经计算过庄家的点数, 不用再 * 1.5 了
        val payers = activePlayers.filter { it != this }
        //四川: 每个还没胡牌的玩家各付一份完整分数; 日麻: 原本的立直棒/场棒算法
        val score = if (isSichuan) basicScore * payers.size
        else basicScore - playerRiichiStickPoints + extraScore
        scoreList += ScoreItem(
            mahjongPlayer = this,
            scoreOrigin = this.points,
            scoreChange = score
        )
        this.points += score
        //以下对其他玩家做计算
        //四川血战到底: 已经胡牌退出的人不用再付分, 由还在场的人平均分担 (非四川时 activePlayers 就是全部玩家)
        payers.forEach {
            val riichiStickPoints =
                if (it.riichi || it.doubleRiichi) ScoringStick.P1000.point else 0 //这个玩家这轮有没有立直,有->还要减去立直棒的点数
            if (isSichuan) { //四川: 每个还没胡牌的玩家各付一份
                val itsScore = basicScore + riichiStickPoints
                scoreList += ScoreItem(
                    mahjongPlayer = it,
                    scoreOrigin = it.points,
                    scoreChange = -itsScore
                )
                it.points -= itsScore
            } else if (tsumoPlayerIsDealer) { //自摸的那个玩家是否是庄家->分数平分
                val averageScore = (basicScore + honbaScore) / 3 //场棒的分数也要分担
                val itsScore = averageScore + riichiStickPoints
                scoreList += ScoreItem(
                    mahjongPlayer = it,
                    scoreOrigin = it.points,
                    scoreChange = -itsScore
                )
                it.points -= itsScore
            } else { //自摸的玩家不是庄家->庄家要承受一半的点数
                val isDealer = it == seatOrderFromDealer[0]
                if (isDealer) {
                    val halfScore = basicScore / 2
                    val itsScore = halfScore + honbaScore / 3 + riichiStickPoints //场棒的分数也要分担
                    scoreList += ScoreItem(
                        mahjongPlayer = it,
                        scoreOrigin = it.points,
                        scoreChange = -itsScore
                    )
                    it.points -= itsScore
                } else {
                    val quartScore = basicScore / 4
                    val itsScore = quartScore + honbaScore / 3 + riichiStickPoints //场棒的分数也要分担
                    scoreList += ScoreItem(
                        mahjongPlayer = it,
                        scoreOrigin = it.points,
                        scoreChange = -itsScore
                    )
                    it.points -= itsScore
                }
            }
        }
        //血战到底: 已经胡牌的人这次不用付分, 但结算画面上还是要出现 (分数变动 0), 排名才完整
        players.filter { it != this && it !in payers }.forEach {
            scoreList += ScoreItem(mahjongPlayer = it, scoreOrigin = it.points, scoreChange = 0)
        }
        val scoreSettlement = ScoreSettlement(
            titleTranslateKey = MahjongGameBehavior.TSUMO.translateKey,
            scoreList = scoreList
        )
        realPlayers.sendSettlePacketAndDelay(
            yakuSettlementList = yakuSettlementList,
            scoreSettlement = scoreSettlement
        )
    }

    /**
     * 玩家流局满贯,
     * 当成自摸处理, 要考虑多人流局满贯的情况 (机率超级小, 且应该最多两个人可以流局满贯)
     * */
    private suspend fun List<MahjongPlayerBase>.nagashiMangan() {
        val yakuSettlementList = mutableListOf<YakuSettlement>()
        val scoreList = mutableListOf<ScoreItem>()
        //计算 scoreList 并加减玩家的分数
        //多人流局满贯时积棒算谁的(? 目前应该算最靠近庄家的那个玩家的
        val atamahanePlayer = seatOrderFromDealer.find { it in this } //找到头跳的玩家 (从庄家开始找)
        val allRiichiStickQuantity = players.sumOf { it.riichiStickAmount } //所有立直棒的数量
        val honbaScore = round.honba * 300 //场棒的分数
        val extraScore = allRiichiStickQuantity * ScoringStick.P1000.point + honbaScore
        val originalScoreList = hashMapOf<String, Int>().apply { //以 <stringUUID, 分数> 储存原本的分数
            players.forEach { this[it.uuid] = it.points }
        }
        val dealer = seatOrderFromDealer[0]
        this.forEach {
            val yakuSettlement = YakuSettlement.nagashiMangan(
                mahjongPlayer = it,
                doraIndicators = board.doraIndicators.map { entity -> entity.mahjongTile },
                uraDoraIndicators = board.uraDoraIndicators.map { entity -> entity.mahjongTile },
                isDealer = it == dealer
            )
            yakuSettlementList += yakuSettlement
            val basicScore = yakuSettlement.score
            val score = basicScore + if (it == atamahanePlayer) extraScore else 0
            it.points += score
            players.filter { player -> player != it }.forEach { player ->
                player.points -= (basicScore / 3)
                if (it == atamahanePlayer) player.points -= (honbaScore / 3) //要分担场棒的分数
            }
        }
        players.forEach {
            val originalScore = originalScoreList[it.uuid]!!
            val scoreChange = it.points - originalScore
            scoreList += ScoreItem(
                mahjongPlayer = it,
                scoreOrigin = originalScore,
                scoreChange = -scoreChange
            )
        }
        val scoreSettlement = ScoreSettlement(
            titleTranslateKey = MahjongGameBehavior.TSUMO.translateKey,
            scoreList = scoreList
        )
        realPlayers.sendSettlePacketAndDelay(
            yakuSettlementList = yakuSettlementList,
            scoreSettlement = scoreSettlement
        )
    }

    /**
     * 发送给玩家结算的数据包, 会等待数据包应该等待的时间
     * */
    private suspend fun List<MahjongPlayer>.sendSettlePacketAndDelay(
        yakuSettlementList: List<YakuSettlement>? = null,
        scoreSettlement: ScoreSettlement? = null,
    ) {
        if (yakuSettlementList != null) {
            this.forEach { //发送给玩家役结算画面的数据
                sendPayloadToPlayer(
                    player = it.entity,
                    payload = MahjongGamePayload(
                        behavior = MahjongGameBehavior.YAKU_SETTLEMENT,
                        extraData = Json.encodeToString(yakuSettlementList)
                    )
                )
            }
            val yakuSettlementTime = YakuSettleHandler.defaultTime * 1000L * yakuSettlementList.size //显示役结算画面需要的时间
            delayOnServer(yakuSettlementTime)
        }
        if (scoreSettlement != null) {
            this.forEach { //发送给玩家分数结算画面的数据
                sendPayloadToPlayer(
                    player = it.entity,
                    payload = MahjongGamePayload(
                        behavior = MahjongGameBehavior.SCORE_SETTLEMENT,
                        extraData = Json.encodeToString(scoreSettlement)
                    )
                )
            }
            val scoreSettlementTime = ScoreSettleHandler.defaultTime * 1000L //显示分数结算画面需要的时间
            delayOnServer(scoreSettlementTime)
        }
    }

    /**
     * 玩家收到游戏结束的数据
     * */
    private fun MahjongPlayer.gameOver() {
        if (!isHost(this.entity)) ready = false //非 Host 玩家会取消准备
        cancelWaitingBehavior = true

        // 对玩家发送 GAME_OVER 的数据包
        sendPayloadToPlayer(
            player = this.entity,
            payload = MahjongGamePayload(behavior = MahjongGameBehavior.GAME_OVER)
        )
    }

    /**
     * 掷骰,
     * 并设定骰子点数 [dicePoints],
     * 最后会回传两个骰子
     * */
    private suspend fun rollDice(): List<DiceEntity> {
        val dices = List(2) {
            DiceEntity(
                world = world,
                pos = Vec3d(pos.x + 0.5, pos.y.toDouble() + 1.5, pos.z + 0.5),
                yaw = (-180 until 180).random().toFloat()
            ).apply {
                isSpawnedByGame = true
                gameBlockPos = this@MahjongGame.pos
                val sin = MathHelper.sin(yaw * 0.017453292F - 11)
                val cos = MathHelper.cos(yaw * 0.017453292F - 11)
                val range = if (it == 0) (15..90) else (-90..-15)
                val randomMoveXPercentage = ((range).random()) / 100.0
                val randomMoveZPercentage = ((range).random()) / 100.0
                setVelocity(0.1 * cos * randomMoveXPercentage, 0.03, 0.1 * sin * randomMoveZPercentage)
                ServerScheduler.scheduleDelayAction { world.spawnEntity(this) }
            }
        }
        //骰子的点数, 不管自动骰到的点数是多少, 骰完的时候都会使用这两个点数替代
        val dicePoints = dices.associateWith { DicePoint.random() }
        //这边等待骰子都结束 rolling 才会跳出 while 回圈
        while (dices.any { it.rolling }) delayOnServer(50)
        dices.forEach { diceEntity -> diceEntity.point = dicePoints[diceEntity]!! }
        //总和的点数
        val totalPoints = dicePoints.values.sumOf { it.value }
        delayOnServer(500) //给一个延迟, 让掷骰完不要马上显示骰到的点数
        val pointsSumText =
            Text.translatable("$MOD_ID.game.dice_points").formatted(Formatting.GOLD) + " §c$totalPoints"
        realPlayers.map { it.entity }.sendTitles(subtitle = pointsSumText)
        this@MahjongGame.dicePoints = totalPoints
        return dices
    }

    /**
     * 开始游戏
     * */
    override fun start(sync: Boolean) {
        //开始游戏
        status = GameStatus.PLAYING
        seat = players.take(seatCapacity) //三人麻将只取前三个座位
            .toMutableList().apply {
            shuffle() //随编排座位
            forEachIndexed { index, mjPlayer ->
                //传送到对应座位的
                with(mjPlayer) {
                    val yaw = 90 - 90f * index
                    val stoolX = pos.x + if (index == 0) 2 else if (index == 2) -2 else 0
                    val stoolZ = pos.z + if (index == 1) -2 else if (index == 3) 2 else 0
                    val stoolBlockPos = BlockPos(stoolX, pos.y, stoolZ) //麻将凳应该在的位置
                    val blockState = world.getBlockState(stoolBlockPos)
                    val block = blockState.block //在麻将凳应该在的位置上的方块
                    if (block is MahjongStool && SeatEntity.canSpawnAt(
                            world,
                            stoolBlockPos,
                            checkEntity = false
                        )
                    ) { //检查有没有麻将凳的存在和高度是否足够
                        ServerScheduler.scheduleDelayAction { //先传送再让玩家坐在椅子上, 因为先把玩家传到椅子后面, 所以玩家会自动看向麻将桌
                            val x = pos.x + 0.5 + if (index == 0) 3 else if (index == 2) -3 else 0
                            val y = pos.y.toDouble()
                            val z = pos.z + 0.5 + if (index == 1) -3 else if (index == 3) 3 else 0
                            this.teleport(world, x, y, z, yaw, 0f) //先将玩家传送到椅子后面
                            val offsetY = if (this is MahjongPlayer) 0.4 else 1.0 / 16.0 * 10.0
                            SeatEntity.forceSpawnAt(
                                entity = this.entity,
                                world = world,
                                pos = stoolBlockPos,
                                sitOffsetY = offsetY
                            )
                            if (this is MahjongBot) this.entity.isInvisible = false //Bot->传送后再解除隐形
                        }
                    } else { //没有麻将凳或者高度不够
                        fun BlockPos.collisionExists() =
                            this.let { !world.getBlockState(it).getCollisionShape(world, it).isEmpty }

                        val blockBelowCollisionExists = stoolBlockPos.offset(Direction.DOWN).collisionExists()
                        val blockCollisionDoesNotExist = !stoolBlockPos.collisionExists()
                        val blockAboveCollisionDoesNotExist = !stoolBlockPos.offset(Direction.UP).collisionExists()
                        if (blockBelowCollisionExists && blockCollisionDoesNotExist && blockAboveCollisionDoesNotExist) {
                            this.teleport(world, stoolX + 0.5, pos.y.toDouble(), stoolZ + 0.5, yaw, 0f) //将玩家传送到凳子的位置上
                        } else { //最后不能的话就生在桌子上, 会看向自己的凳子的方向
                            this.teleport(
                                world,
                                pos.x + 0.5,
                                pos.y + 1.2,
                                pos.z + 0.5,
                                yaw + 180, //yaw 会朝凳子方向
                                0f
                            )
                        }
                        if (this is MahjongBot) this.entity.isInvisible = false //Bot->传送后再解除隐形
                    }
                }
            }
        }
        round = rule.length.getStartingRound()
        players.forEach {
            it.points = rule.startingPoints //设置玩家的初始点数
            it.basicThinkingTime = rule.thinkingTime.base
            it.extraThinkingTime = rule.thinkingTime.extra
        }
        realPlayers.forEach {
            it.cancelWaitingBehavior = false
            sendPayloadToPlayer(
                player = it.entity,
                payload = MahjongGamePayload(behavior = MahjongGameBehavior.GAME_START)
            )
        }
        if (sync) syncMahjongTable()  //开始游戏同步麻将桌
        //延迟 0.5 秒后再开始
        val handler = CoroutineExceptionHandler { _, _ -> } //通常只有中途拆了麻将桌才会导致这的错误, 目前先直接无视
        jobWaitForStart = CoroutineScope(Dispatchers.IO).launch(handler) {
            delayOnServer(500)
            startRound()
        }
    }

    /**
     * 结束游戏,
     * 请在主线程上调用
     *
     * @param sync 是否要同步 [MahjongTableBlockEntity]
     * */
    override fun end(sync: Boolean) {
        //结束游戏
        endVoteAgreed.clear()
        status = GameStatus.WAITING
        jobWaitForStart?.cancel()
        jobRound?.cancel()
        seat.clear()
        clearStuffs()
        round = MahjongRound()
        realPlayers.forEach { it.gameOver() }
        botPlayers.forEach { //将电脑传回原本的位置
            it.entity.isInvisible = true
            it.entity.refreshPositionAfterTeleport(tableCenterPos.x, tableCenterPos.y, tableCenterPos.z)
        }
        if (sync) syncMahjongTable()  //结束游戏要同步麻将桌
    }

    /**
     * 当玩家破坏麻将桌时
     * */
    override fun onBreak() {
        if (isPlaying) {
            showGameResult()
            end(sync = false)
        }
        val message = PREFIX + Text.translatable("$MOD_ID.game.message.game_block_is_destroyed")
        realPlayers.forEach { //传给玩家->麻将桌被破坏的讯息
            it.sendMessage(message)
        }
        botPlayers.forEach {  //将电脑从游戏中移除
            it.entity.remove(Entity.RemovalReason.DISCARDED)
        }
        players.clear()
        GameManager.games -= this
    }

    /**
     * 有任何玩家离开游戏时结束游戏, 向玩家显示有人离开游戏
     * */
    override fun onPlayerDisconnect(player: ServerPlayerEntity) {
        if (isPlaying) {
            showGameResult()
            end(sync = false)
        }
        leave(player)
        val message = PREFIX + Text.translatable("$MOD_ID.game.message.player_left_game", player.displayName)
        realPlayers.forEach { //传给玩家-> [player] 离开游戏的讯息
            it.sendMessage(message)
        }
        syncMahjongTable() //有任何玩家离开游戏时同步
    }

    /**
     * 有任何玩家切换世界时结束游戏, 向玩家显示有人不在当前的世界
     * */
    override fun onPlayerChangedWorld(player: ServerPlayerEntity) {
        if (isPlaying) {
            showGameResult()
            end(sync = false)
        }
        leave(player)
        val message =
            PREFIX + Text.translatable("$MOD_ID.game.message.player_is_not_in_this_world", player.displayName)
        realPlayers.forEach {  //传给玩家-> [player] 改变世界的讯息
            it.sendMessage(message)
        }
        syncMahjongTable() //有任何玩家切换世界时同步
    }

    override fun onServerStopping(server: MinecraftServer) {
        if (isPlaying) end(sync = false)
        realPlayers.forEach { leave(it.entity) }
    }

    /**
     * 同步 [MahjongTableBlockEntity]
     * */
    private fun syncMahjongTable(invokeOnNextTick: Boolean = true) {
        MahjongTablePayloadListener.syncBlockEntityWithGame(invokeOnNextTick = invokeOnNextTick, game = this)
    }

    /**
     * 判断玩家是否是主持
     * */
    override fun isHost(player: ServerPlayerEntity): Boolean =
        players.firstOrNull()?.let { it.entity == player } ?: false

    /**
     * 取得 mahjong4j 判定用的 [PersonalSituation],
     *
     * @param isTsumo 是否自摸
     * @param isChankan 是否抢杠
     * @param isRinshanKaihoh 是否岭上开花
     * */
    private fun MahjongPlayerBase.getPersonalSituation(
        isTsumo: Boolean = false,
        isChankan: Boolean = false,
        isRinshanKaihoh: Boolean = false,
    ): PersonalSituation {
        val selfWindNumber = seatOrderFromDealer.indexOf(this) //从以庄家开始排序的座位中取得 this 玩家的座位编号 (与自风编号一样)
        val jikaze = Wind.entries[selfWindNumber].tile
        val isIppatsu: Boolean = isIppatsu(players, board.discards) //是否一发
        return PersonalSituation(
            isTsumo,
            isIppatsu,
            this.riichi,
            this.doubleRiichi,
            isChankan,
            isRinshanKaihoh,
            jikaze
        )
    }

    /**
     * 取得玩家在丢了 [tile] 之后会听的所有牌跟对应的翻数
     * */
    fun MahjongPlayerBase.getMachiAndHan(tile: MahjongTile): Map<MahjongTile, Int> {
        if (board.deadWall.isEmpty()) return emptyMap() //王牌区还没初始化, 直接回传空 map
        val handsForCalculate = this.hands.toMahjongTileList().toMutableList().apply { remove(tile) }
        return this.calculateMachiAndHan(
            hands = handsForCalculate,
            rule = rule,
            generalSituation = board.generalSituation,
            personalSituation = this.getPersonalSituation(
                isTsumo = false,
                isChankan = false,
                isRinshanKaihoh = false
            )
        )
    }

    /**
     * 计算是否振听, 接在 [getMachiAndHan] 使用
     *
     * @param machi 直接使用 [getMachiAndHan] 的 keys 即可
     * */
    fun MahjongPlayerBase.isFuriten(tile: MahjongTile, machi: List<MahjongTile>): Boolean =
        this.isFuriten(tile.mahjong4jTile, board.discards.map { it.mahjong4jTile }, machi.map { it.mahjong4jTile })

    companion object {
        /**
         * 最小等待时间,
         * 用来让玩家丢牌、思考、等待...的过程看起来更合理
         *
         * (单位: 毫秒)
         * */
        const val MIN_WAITING_TIME = 1200L

        /**
         * 积棒最多一层几个
         * */
        const val STICKS_PER_STACK = 5

        /** 血战到底: 花猪 (手上还有三门牌) 每家的赔付点数 */
        const val HUA_ZHU_PENALTY = 2000

        /** 血战到底: 查大叫 (没听牌赔听牌) 的点数 */
        const val CHA_DA_JIAO_PENALTY = 1000

        /** 血战到底本局结算的标题语言键 */
        const val BLOOD_BATTLE_SETTLEMENT_KEY = "$MOD_ID.game.blood_battle.settlement"

        /** 四川麻将的 27 种数牌 (查大叫判断听牌用) */
        private val SICHUAN_TILES: List<MahjongTile> = listOf(
            MahjongTile.M1, MahjongTile.M2, MahjongTile.M3, MahjongTile.M4, MahjongTile.M5,
            MahjongTile.M6, MahjongTile.M7, MahjongTile.M8, MahjongTile.M9,
            MahjongTile.P1, MahjongTile.P2, MahjongTile.P3, MahjongTile.P4, MahjongTile.P5,
            MahjongTile.P6, MahjongTile.P7, MahjongTile.P8, MahjongTile.P9,
            MahjongTile.S1, MahjongTile.S2, MahjongTile.S3, MahjongTile.S4, MahjongTile.S5,
            MahjongTile.S6, MahjongTile.S7, MahjongTile.S8, MahjongTile.S9,
        )

        val PREFIX get() = Text.translatable("$MOD_ID.game.riichi_mahjong.prefix")
    }
}

/**
 * 机器人名字的前缀, 后面接中文编号 (【电脑】一、【电脑】二 ...)
 * */
private const val BOT_NAME_PREFIX = "【电脑】"

/**
 * 数字转中文编号: 1 -> 一, 2 -> 二, 15 -> 十五, 20 -> 二十
 * */
private fun Int.toChineseNumber(): String {
    val digits = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    return when {
        this <= 0 -> digits[0]
        this < 10 -> digits[this]
        this == 10 -> "十"
        this < 20 -> "十" + digits[this % 10]
        this % 10 == 0 -> digits[this / 10] + "十"
        else -> digits[this / 10] + "十" + digits[this % 10]
    }
}
