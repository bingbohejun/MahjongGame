package doublemoon.mahjonggame.game.mahjong.riichi

import doublemoon.mahjonggame.entity.MahjongScoringStickEntity
import doublemoon.mahjonggame.entity.MahjongScoringStickEntity.Companion.MAHJONG_POINT_STICK_DEPTH
import doublemoon.mahjonggame.entity.MahjongScoringStickEntity.Companion.MAHJONG_POINT_STICK_HEIGHT
import doublemoon.mahjonggame.entity.MahjongScoringStickEntity.Companion.MAHJONG_POINT_STICK_WIDTH
import doublemoon.mahjonggame.entity.MahjongTileEntity
import doublemoon.mahjonggame.entity.MahjongTileEntity.Companion.MAHJONG_TILE_DEPTH
import doublemoon.mahjonggame.entity.MahjongTileEntity.Companion.MAHJONG_TILE_HEIGHT
import doublemoon.mahjonggame.entity.MahjongTileEntity.Companion.MAHJONG_TILE_SMALL_PADDING
import doublemoon.mahjonggame.entity.MahjongTileEntity.Companion.MAHJONG_TILE_WIDTH
import doublemoon.mahjonggame.entity.TileFacing
import doublemoon.mahjonggame.entity.TilePosition
import doublemoon.mahjonggame.game.mahjong.riichi.model.*
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayerBase
import doublemoon.mahjonggame.scheduler.server.ServerScheduler
import doublemoon.mahjonggame.util.delayOnServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.entity.Entity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import org.mahjong4j.GeneralSituation
import org.mahjong4j.hands.Kantsu
import org.mahjong4j.tile.Tile
import org.mahjong4j.tile.TileType

/**
 * 在 [MahjongGame] 中所有与 [MahjongTileEntity] (麻将牌) 或者 [MahjongScoringStickEntity] (积棒) 有关的操作大部分都在这
 * */
class MahjongBoard(
    val game: MahjongGame,
) {
    /**
     * [game] 的所有麻将牌, 用来装所有牌的实体 [MahjongTileEntity],
     * 包含玩家的手牌
     * */
    val allTiles = mutableListOf<MahjongTileEntity>()

    /**
     * 牌山,
     * 不包含玩家手牌,
     * 也不包含被摸走的岭上牌,
     * */
    val wall = mutableListOf<MahjongTileEntity>()

    /** 牌墙四边各自的墩数与在 [wall] 中的起始索引 (generateAllTilesAndSpawnWall 时计算) */
    private var sideStacks = IntArray(4) { 17 }
    private var sideStartIndex = IntArray(4) { it * 34 }

    /**
     * 王牌区,
     * 王牌区的顺序是按照原本在牌山的顺序排序的
     * */
    val deadWall = mutableListOf<MahjongTileEntity>()

    /**
     * 杠牌计数
     * */
    var kanCount = 0
        private set

    /**
     * 丢的牌的列表, 会按照丢的顺序排列
     * */
    val discards: MutableList<MahjongTileEntity> = mutableListOf()

    /**
     * 丢牌计数
     * */
    private val discardsCount: Int
        get() = game.players.sumOf { it.discardedTiles.size }

    /**
     * 目前没有人有副露
     * */
    private val noFuuro: Boolean
        get() = game.players.none { it.fuuroList.size > 0 }

    /**
     * 是否第一轮就胡,
     * (丢出的牌小于等于 4 张且没有人有副露)
     * */
    val isFirstRound: Boolean
        get() = discardsCount <= 4 && noFuuro

    /**
     * 现在是否是河底
     * */
    val isHoutei: Boolean
        get() = wall.size <= 4

    /**
     * 连续 4 次丢牌是否是同张风连打
     * */
    val isSuufonRenda: Boolean
        get() {
            if (discards.size < 4) return false
            val discards = discards.asReversed() //先将丢过的牌的列表倒过来牌, 方便处理
            val fonTile = discards[0]
            if (fonTile.mahjong4jTile.type != TileType.FONPAI) return false
            repeat(4) { if (discards[it].code != fonTile.code) return false }
            return true
        }

    /**
     * 场风牌
     * */
    private val bakaze: Tile
        get() = game.round.wind.tile

    /**
     * 宝牌指示牌,
     * 根据 [kanCount] 和 [deadWall] 取得对应的宝牌指示牌,
     * 宝牌指示牌的数量应该要比 [kanCount] 多 1 个
     * */
    val doraIndicators: List<MahjongTileEntity>
        get() = List(kanCount + 1) {
            val doraIndicatorIndex = (4 - it) * 2 + kanCount //加上杠补进王牌区的牌
            deadWall[doraIndicatorIndex]
        }

    /**
     * 里牌指示牌,
     * 根据 [kanCount] 和 [deadWall] 取得对应的里宝牌指示牌,
     * 里宝牌指示牌的数量应该要比 [kanCount] 多 1 个
     * */
    val uraDoraIndicators: List<MahjongTileEntity>
        get() = List(kanCount + 1) {
            val uraDoraIndicatorIndex = (4 - it) * 2 + 1 + kanCount //加上杠补进王牌区的牌
            deadWall[uraDoraIndicatorIndex]
        }

    /**
     * 宝牌列表,
     * 根据 [doraIndicators] 取得对应的宝牌
     * */
    private val doraList: List<Tile>
        get() = doraIndicators.map { it.mahjongTile.nextTile.mahjong4jTile }

    /**
     * 宝牌列表,
     * 根据 [uraDoraIndicators] 取得对应的里宝牌
     * */
    private val uraDoraList: List<Tile>
        get() = uraDoraIndicators.map { it.mahjongTile.nextTile.mahjong4jTile }

    /**
     * 根据 [kanCount] 来翻出宝牌指示牌
     * */
    private fun flipDoraIndicators() {
        doraIndicators.forEach {
            if (it.facing != TileFacing.UP) it.facing = TileFacing.UP
            if (it.inGameTilePosition != TilePosition.OTHER) it.inGameTilePosition = TilePosition.OTHER
        }
    }

    /**
     * 取得 mahjong4j 判断用的 [GeneralSituation]
     * */
    val generalSituation: GeneralSituation
        get() = GeneralSituation(
            isFirstRound,
            isHoutei,
            bakaze,
            doraList,
            uraDoraList
        )

    /**
     * 清除所有牌 跟 [kanCount]
     * */
    fun clear() {
        kanCount = 0
        discards.clear()
        wall.clear()
        deadWall.clear()
        //最后清除掉所有牌的实体
        //有时候会同时触发游戏中的清除跟 MahjongGame.onBreak() 或 MahjongGame.onServerStopping() 的清除,
        //会导致有一个 allTiles.clear() 先执行, 后面另外一个正在执行 forEach 的直接报错, 这里直接先复制后再执行, 避免上述情况
        allTiles.toList().forEach { if (!it.isRemoved) it.remove(Entity.RemovalReason.DISCARDED) }
        allTiles.clear()
    }

    /**
     * 产生所有的牌 [allTiles],
     * 并将所有 [allTiles] 加进 [wall] 按照位置生成
     * */
    fun generateAllTilesAndSpawnWall() {
        val tableCenterPos = game.tableCenterPos
        val world = game.world
        allTiles.apply {
            MahjongTile.styleWall(style = game.rule.gameStyle, redFive = game.rule.redFive).shuffled().forEach {
                //打乱牌后一个个加入
                this += MahjongTileEntity(
                    world = world,
                    code = it.code,
                    gameBlockPos = game.pos,
                    isSpawnedByGame = true,
                    inGameTilePosition = TilePosition.WALL,
                    gamePlayers = game.players.map { player -> player.uuid },
                    canSpectate = game.rule.spectate,
                    facing = TileFacing.DOWN
                )
            }
        }
        wall.apply {
            this += allTiles
            //传送到对应的位置, 并在世界生成实体
            //牌墙布局: 总墩数 (每墩 2 张) 分给四边, 余数给东/西两边 (对称)
            //136 张 -> 17 墩 x 4 边; 108 张 -> 东14/南13/西14/北13 墩
            //每边都从自己的角落对齐 (跟原本 17 墩的起点一致), 短边的空缺落在该边尾端的角落
            val totalStacks = wall.size / 2
            val baseStacks = totalStacks / 4
            val extraStacks = totalStacks % 4
            sideStacks = IntArray(4) { side -> baseStacks + if (side % 2 == 0) (extraStacks + 1) / 2 else extraStacks / 2 }
            sideStartIndex = IntArray(4).also { acc ->
                var sum = 0
                for (side in 0..3) {
                    acc[side] = sum
                    sum += sideStacks[side]
                }
            }
            forEachIndexed { index, tile ->
                //调整牌的位置跟方向
                val directionOffset = 1.0
                val topOrBottom = (1 - index % 2) //这张牌在上面还是下面, 上面->1 下面->0
                val yOffset =
                    (topOrBottom * MAHJONG_TILE_DEPTH).toDouble() + (if (topOrBottom == 1) MAHJONG_TILE_SMALL_PADDING else 0.0)
                val globalStack = index / 2
                val side = (0..3).first { globalStack < sideStartIndex[it] + sideStacks[it] }
                val stackNum = globalStack - sideStartIndex[side]
                val startingPos = (17.0 * MAHJONG_TILE_WIDTH) / 2.0 - MAHJONG_TILE_HEIGHT
                val stackWidth = stackNum * (MAHJONG_TILE_WIDTH + MAHJONG_TILE_SMALL_PADDING)
                when (side) { //这里面朝的方向就是以游戏内的东为准，按照麻将桌"顺"时针东南西北的方向
                    0 -> { //游戏中面朝东
                        tableCenterPos.add(directionOffset, yOffset, -startingPos + stackWidth).apply {
                            tile.refreshPositionAfterTeleport(this)
                            tile.yaw = -90f
                        }
                    }
                    1 -> { //游戏中面朝南
                        tableCenterPos.add(startingPos - stackWidth, yOffset, directionOffset).apply {
                            tile.refreshPositionAfterTeleport(this)
                            tile.yaw = 0f
                        }
                    }
                    2 -> { //游戏中面朝西
                        tableCenterPos.add(-directionOffset, yOffset, startingPos - stackWidth).apply {
                            tile.refreshPositionAfterTeleport(this)
                            tile.yaw = 90f
                        }
                    }
                    else -> { //游戏中面朝北
                        tableCenterPos.add(-startingPos + stackWidth, yOffset, -directionOffset).apply {
                            tile.refreshPositionAfterTeleport(this)
                            tile.yaw = 180f
                        }
                    }
                }
            }
            ServerScheduler.scheduleDelayAction { this.forEach { world.spawnEntity(it) } } //将牌生成到游戏中
        }
        game.playSound(soundEvent = SoundEvents.ENTITY_ITEM_PICKUP)
    }

    /**
     * 从 [wall] 移除最后 7 敦并将其加入进 [deadWall],
     * 再对王牌区进行一点点偏移, 让他与 [wall] 的牌产生间隔
     * 王牌会集中到与最后一敦岭上牌同一个方向
     * */
    fun assignDeadWall() {
        with(deadWall) {
            for (index in 0 until 14) {
                this += wall.last()
                wall.removeLast()
            }
            reverse()
            forEach {
                //偏移王牌区
                val gap = MAHJONG_TILE_SMALL_PADDING * 20
                when (it.horizontalFacing) {
                    Direction.EAST -> it.refreshPositionAfterTeleport(it.x, it.y, it.z + gap)
                    Direction.SOUTH -> it.refreshPositionAfterTeleport(it.x - gap, it.y, it.z)
                    Direction.WEST -> it.refreshPositionAfterTeleport(it.x, it.y, it.z - gap)
                    Direction.NORTH -> it.refreshPositionAfterTeleport(it.x + gap, it.y, it.z)
                    else -> {
                    }
                }
            }
            // 王牌区方向以最后一敦为准
            val direction = deadWall.last().horizontalFacing
            // 将与王牌区不同方向的捕到王牌区旁边并将方向转正
            reversed().let {
                it.forEachIndexed { index, mahjongTile ->
                    with(mahjongTile) {
                        if (this.horizontalFacing != direction) {
                            //整除 2 表示牌在下面,反之牌在上面
                            yaw = direction.asRotation()
                            if (index % 2 == 0) {
                                it.forEach { tile ->
                                    when (direction) {
                                        Direction.EAST -> tile.refreshPositionAfterTeleport(
                                            tile.x,
                                            tile.y,
                                            tile.z + MAHJONG_TILE_WIDTH
                                        )
                                        Direction.SOUTH -> tile.refreshPositionAfterTeleport(
                                            tile.x - MAHJONG_TILE_WIDTH,
                                            tile.y,
                                            tile.z
                                        )
                                        Direction.WEST -> tile.refreshPositionAfterTeleport(
                                            tile.x,
                                            tile.y,
                                            tile.z - MAHJONG_TILE_WIDTH
                                        )
                                        Direction.NORTH -> tile.refreshPositionAfterTeleport(
                                            tile.x + MAHJONG_TILE_WIDTH,
                                            tile.y,
                                            tile.z
                                        )
                                        else -> {
                                        }
                                    }
                                }
                            }
                            val posY = if (index % 2 == 0) it[0].y else it[1].y
                            val offset = (MAHJONG_TILE_WIDTH + MAHJONG_TILE_SMALL_PADDING) * (index / 2)
                            when (direction) {
                                Direction.EAST -> mahjongTile.refreshPositionAfterTeleport(
                                    it[0].x,
                                    posY,
                                    it[0].z - offset
                                )
                                Direction.SOUTH -> mahjongTile.refreshPositionAfterTeleport(
                                    it[0].x + offset,
                                    posY,
                                    it[0].z
                                )
                                Direction.WEST -> mahjongTile.refreshPositionAfterTeleport(
                                    it[0].x,
                                    posY,
                                    it[0].z + offset
                                )
                                Direction.NORTH -> mahjongTile.refreshPositionAfterTeleport(
                                    it[0].x - offset,
                                    posY,
                                    it[0].z
                                )
                                else -> {
                                }
                            }
                        }
                    }
                }
            }
            // 翻开宝牌指示牌
            flipDoraIndicators()
        }
    }


    /**
     * 根据骰子点数 [dicePoints] 跟庄家的位置分配手牌,最后再将手牌移出牌山 [wall],
     * 这里并没有一次发 4 张牌, 都是直接把整副牌发完的
     **/
    suspend fun assignWallAndHands(dicePoints: Int) {
        withContext(Dispatchers.Default) {
            val directionIndex = (4 - ((dicePoints % 4 - 1) + game.round.round) % 4) //从哪边开始拿牌
            val startingStackIndex = 2 * dicePoints //从开始处数第几张牌开始拿
            val dealer = game.seat[game.round.round] //庄家
            //该边在牌墙里的起始索引 (考量 108 张时四边墩数不同的情况)
            val sideStartTileIndex = sideStartIndex.getOrElse(directionIndex) { 0 } * 2
            val newWall = MutableList(wall.size) {
                //按照摸牌的顺序, 建立 newWall
                val tileIndex = (sideStartTileIndex + startingStackIndex + it) % wall.size
                wall[tileIndex]
            }
            //分配玩家的手牌
            game.seat.forEach { mjPlayer ->
                //庄家 14 张,剩下每个人 13 张牌, 共 13 * 3 + 14 = 53
                val tileAmount = if (mjPlayer == dealer) 14 else 13
                repeat(tileAmount) {
                    val tile = newWall.removeFirst() //摸走最前面的牌
                    tile.isInvisible = true //先暂时隐形一下
                    mjPlayer.drawTile(tile)
                    if (it == 13) sortHands(player = mjPlayer, lastTile = tile)
                    else sortHands(player = mjPlayer)
                }
            }
            //将牌山替换成新的牌山
            wall.clear()
            wall += newWall
            //从庄家开始显示牌, 这样看起来比较牛逼
            repeat(game.seat.size) { times ->
                if (game.seat.size == 0) return@repeat //可能是游戏中途消失了
                val seatIndex = (game.round.round + times) % game.seat.size
                game.seat[seatIndex].hands.forEach { it.isInvisible = false }
                game.playSound(soundEvent = SoundEvents.ENTITY_ITEM_PICKUP, volume = 0.3f, pitch = 2.0f)
                delayOnServer(250)
            }
        }
    }

    /**
     * 将指定的玩家的"手牌"按顺序整理,
     * 会排好放在玩家面前
     *
     * @param lastTile 指定的最后一张牌, 不会被排序进手牌堆中
     * */
    fun sortHands(
        player: MahjongPlayerBase,
        lastTile: MahjongTileEntity? = null,
    ) {
        if (player !in game.seat) return
        val seatIndex = game.seat.indexOf(player)
        val tableCenterPos = game.tableCenterPos
        with(player) {
            val tileAmount = hands.size
            if (player.autoArrangeHands) hands.sortBy { it.mahjongTile.sortOrder }
            lastTile?.let { //将指定的最后一张牌,移到最后的位置
                hands -= it
                hands += it
            }
            hands.forEachIndexed { index, hTile ->
                //调整牌的位置跟方向
                val directionOffset = 1.0 + MAHJONG_TILE_DEPTH + MAHJONG_TILE_HEIGHT
                val fuuroOffset = //防止副露太长挡到手牌
                    if (fuuroList.size < 3) 0.0 else (fuuroList.size - 2.0) * (MAHJONG_TILE_WIDTH)
                val sticksOffset = //防止积棒太长
                    if (sticks.size < 3) 0.0 else (sticks.size - 2.0) * (MAHJONG_POINT_STICK_DEPTH)
                val startingPos =
                    (tileAmount * MAHJONG_TILE_WIDTH + (tileAmount - 1) * MAHJONG_TILE_SMALL_PADDING) / 2.0 + fuuroOffset + sticksOffset
                val stackOffset = //加了一点小间隙, 如果是指定的最后一张牌会偏移多一点点
                    index * (MAHJONG_TILE_WIDTH + MAHJONG_TILE_SMALL_PADDING) + if (hTile == lastTile) MAHJONG_TILE_SMALL_PADDING * 15.0 else 0.0
                when (seatIndex) { //这里的方向就是以游戏内的面朝东为准，按照麻将桌"逆"时针东南西北的方向
                    0 -> { //东
                        tableCenterPos.add(directionOffset, 0.0, startingPos - stackOffset).apply {
                            hTile.refreshPositionAfterTeleport(this)
                            hTile.yaw = -90f
                        }
                    }
                    3 -> { //南
                        tableCenterPos.add(-startingPos + stackOffset, 0.0, directionOffset).apply {
                            hTile.refreshPositionAfterTeleport(this)
                            hTile.yaw = 0f
                        }
                    }
                    2 -> { //西
                        tableCenterPos.add(-directionOffset, 0.0, -startingPos + stackOffset).apply {
                            hTile.refreshPositionAfterTeleport(this)
                            hTile.yaw = 90f
                        }
                    }
                    else -> { //北
                        tableCenterPos.add(startingPos - stackOffset, 0.0, -directionOffset).apply {
                            hTile.refreshPositionAfterTeleport(this)
                            hTile.yaw = 180f
                        }
                    }
                }
            }
        }
    }


    /**
     * 将指定的玩家的"显示用弃牌堆"整理,
     * 会排好放在玩家面前
     *
     * @param openDoorPlayer 开门玩家, 用来避免开门那方的玩家放不下所有弃牌
     * */
    fun sortDiscardedTilesForDisplay(
        player: MahjongPlayerBase,
        openDoorPlayer: MahjongPlayerBase,
    ) {
        if (player !in game.seat) return
        val seatIndex = game.seat.indexOf(player)
        val tableCenterPos = game.tableCenterPos
        val halfWidthOfSixTiles = (MAHJONG_TILE_WIDTH * 6 / 2.0)
        val paddingFromCenter =
            halfWidthOfSixTiles + MAHJONG_TILE_HEIGHT / 2.0 + MAHJONG_TILE_HEIGHT / 4.0 //距离牌桌中心到弃牌堆第 1 张牌的距离 y
        val basicOffset = halfWidthOfSixTiles - MAHJONG_TILE_WIDTH / 2.0 //实体的座标在实体正中心, 所以给一半牌的宽度当作偏移
        val halfTileWidthAndHalfTileHeight = (MAHJONG_TILE_HEIGHT + MAHJONG_TILE_WIDTH) / 2.0
        val startingPos = when (seatIndex) { //这个是第一张牌的座标
            //这里的方向就是以游戏内的面朝东为准，按照麻将桌"逆"时针东南西北的方向
            0 -> tableCenterPos.add(paddingFromCenter, 0.0, basicOffset) //东
            3 -> tableCenterPos.add(-basicOffset, 0.0, paddingFromCenter) //南
            2 -> tableCenterPos.add(-paddingFromCenter, 0.0, -basicOffset) //西
            else -> tableCenterPos.add(basicOffset, 0.0, -paddingFromCenter) //北
        }
        val tileOffset = when (seatIndex) { //以牌桌边缘为底, 垂直摆放的牌的偏移量 (由左手往右手方向)
            0 -> Vec3d(0.0, 0.0, -MAHJONG_TILE_WIDTH.toDouble()) //东
            3 -> Vec3d(MAHJONG_TILE_WIDTH.toDouble(), 0.0, 0.0) //南
            2 -> Vec3d(0.0, 0.0, MAHJONG_TILE_WIDTH.toDouble()) //西
            else -> Vec3d(-MAHJONG_TILE_WIDTH.toDouble(), 0.0, 0.0) //北
        }
        val riichiTileOffset = when (seatIndex) { //以牌桌边缘为底, 垂直摆放的牌的偏移量 (由左手往右手方向)
            0 -> Vec3d(0.0, 0.0, -halfTileWidthAndHalfTileHeight) //东
            3 -> Vec3d(halfTileWidthAndHalfTileHeight, 0.0, 0.0) //南
            2 -> Vec3d(0.0, 0.0, halfTileWidthAndHalfTileHeight) //西
            else -> Vec3d(-halfTileWidthAndHalfTileHeight, 0.0, 0.0) //北
        }
        val lineOffset = when (seatIndex) { //以牌桌边缘为底, 垂直摆放的牌的偏移量 (由上往下方向)
            0 -> Vec3d(MAHJONG_TILE_HEIGHT.toDouble() + MAHJONG_TILE_SMALL_PADDING, 0.0, 0.0) //东
            3 -> Vec3d(0.0, 0.0, MAHJONG_TILE_HEIGHT.toDouble() + MAHJONG_TILE_SMALL_PADDING) //南
            2 -> Vec3d(-MAHJONG_TILE_HEIGHT.toDouble() - MAHJONG_TILE_SMALL_PADDING, 0.0, 0.0) //西
            else -> Vec3d(0.0, 0.0, -MAHJONG_TILE_HEIGHT.toDouble() - MAHJONG_TILE_SMALL_PADDING) //北
        }
        val smallGapOffset = when (seatIndex) { //摆放的牌的间隔偏移量 (由左手往右手方向)
            0 -> Vec3d(0.0, 0.0, -MAHJONG_TILE_SMALL_PADDING) //东
            3 -> Vec3d(MAHJONG_TILE_SMALL_PADDING, 0.0, 0.0) //南
            2 -> Vec3d(0.0, 0.0, MAHJONG_TILE_SMALL_PADDING) //西
            else -> Vec3d(-MAHJONG_TILE_SMALL_PADDING, 0.0, 0.0) //北
        }
        val tileRot = when (seatIndex) { //牌的面朝方向
            0 -> -90f       //东
            3 -> 0f         //南
            2 -> 90f        //西
            else -> 180f    //北
        }
        var nowPos = startingPos
        val riichiSengenTile = player.riichiSengenTile?.let {
            if (it !in player.discardedTilesForDisplay) { //立直宣言牌不在显示用弃牌堆, 表示被鸣牌走了
                val indexOfRiichiSengenTile = player.discardedTiles.indexOf(it) //立直宣言牌在玩家丢过的牌的索引
                player.discardedTiles.let { tiles ->
                    tiles.find { tile -> //找到第 1 个索引大于立直宣言牌的牌
                        tiles.indexOf(tile) > indexOfRiichiSengenTile
                    }
                }
            } else it
        }
        player.discardedTilesForDisplay.forEachIndexed { index, tileEntity ->
            val firstTileInThisLine = index % 6 == 0 //是否是那行的第 1 张牌
            val isRiichiTile = tileEntity == riichiSengenTile //这张是不是立直宣告牌
            val lastTileIsRiichiTile = //判断上一张牌是不是立直宣告牌
                if (index == 0) false else player.discardedTilesForDisplay[index - 1] == riichiSengenTile
            val lineCount = index / 6 //第几行
            if (lineCount > 0 && firstTileInThisLine) {  //每行的第 1 张牌 (不包括第 1 行)
                if (!(openDoorPlayer == player && index >= 18)) {
                    //如果不是开门玩家的第 3 行,会往下偏移一行
                    nowPos = startingPos
                    repeat(lineCount) { nowPos = nowPos.add(lineOffset) }
                }
            }
            nowPos =
                if (firstTileInThisLine) {
                    if (isRiichiTile) nowPos.add(riichiTileOffset).subtract(tileOffset)
                    else nowPos
                } else {
                    if (isRiichiTile || lastTileIsRiichiTile) nowPos.add(riichiTileOffset)
                    else nowPos.add(tileOffset)
                }
            nowPos = if (firstTileInThisLine) nowPos else nowPos.add(smallGapOffset) //第 1 张牌不会有缝隙偏移
            val yRot = if (isRiichiTile) tileRot - 90 else tileRot
            tileEntity.refreshPositionAndAngles(nowPos.x, nowPos.y, nowPos.z, yRot, 0f)
            tileEntity.facing = TileFacing.UP
        }
    }

    /**
     * 将指定玩家的"副露"整理,
     * 会排好放在玩家面前, 靠近右手的麻将桌角落
     * 调整副露的位置, 配合积棒的位置
     * */
    fun sortFuuro(
        player: MahjongPlayerBase,
    ) {
        //记得实体的座标是以实体本身底部的正中央为主
        if (player !in game.seat) return
        val seatIndex = game.seat.indexOf(player)
        val tableCenterPos = game.tableCenterPos
        val halfTableLengthNoBorder = 0.5 + 15.0 / 16.0 //没有包含边框的半张桌子长度
        val halfHeightOfTile = MAHJONG_TILE_HEIGHT / 2.0 //麻将牌高度的一半
        val startingPos = when (seatIndex) { //计算玩家右手方的牌桌角落靠边的座标
            // 这里的`东南西北`方向是指以麻将桌为中心，向外看的方向
            0 -> tableCenterPos.add(halfTableLengthNoBorder - halfHeightOfTile, 0.0, -halfTableLengthNoBorder) //东
            3 -> tableCenterPos.add(halfTableLengthNoBorder, 0.0, halfTableLengthNoBorder - halfHeightOfTile) //南
            2 -> tableCenterPos.add(-halfTableLengthNoBorder + halfHeightOfTile, 0.0, halfTableLengthNoBorder) //西
            else -> tableCenterPos.add(-halfTableLengthNoBorder, 0.0, -halfTableLengthNoBorder + halfHeightOfTile) //北
        }.let {
            //计算出角落座标后, 检查有没有积棒的存在

            //寻找第一叠最后一根积棒的位置, null 表示没有任何积棒
            val lastStickOfFirstStack =
                player.sticks.findLast { stick -> player.sticks.indexOf(stick) < MahjongGame.STICKS_PER_STACK }
                    ?: return@let it  //没有积棒就维持原本的座标

            //如果有积棒的存在
            val stickPos = lastStickOfFirstStack.pos //积棒的位置
            val offset = stickPos.subtract(it) //算出点棒到角落座标的偏移
            val halfDepthOfStick = MAHJONG_POINT_STICK_DEPTH / 2.0 //积棒深度的一半
            val pos = when (seatIndex) { //计算出只求 与积棒摆放方向平行的位置的偏移 后的角落座标
                0 -> it.add(offset.multiply(0.0, 0.0, 1.0))    //东, 只取 z 轴方向偏移
                3 -> it.add(offset.multiply(1.0, 0.0, 0.0))    //南, 只取 x 轴方向偏移
                2 -> it.add(offset.multiply(0.0, 0.0, 1.0))    //西, 只取 z 轴方向偏移
                else -> it.add(offset.multiply(1.0, 0.0, 0.0)) //北, 只取 x 轴方向偏移
            }
            when (seatIndex) { //再将 积棒的深度 补上, 让他对齐积棒的边
                0 -> pos.add(0.0, 0.0, halfDepthOfStick)
                3 -> pos.add(-halfDepthOfStick, 0.0, 0.0)
                2 -> pos.add(0.0, 0.0, -halfDepthOfStick)
                else -> pos.add(halfDepthOfStick, 0.0, 0.0)
            }
        }
        val tileGap = MAHJONG_TILE_SMALL_PADDING
        val verticalTileOffset = when (seatIndex) { //以牌桌边缘为底, 垂直摆放的牌的偏移量 (由右手往左手方向)
            0 -> Vec3d(0.0, 0.0, MAHJONG_TILE_WIDTH.toDouble() + tileGap)      //东
            3 -> Vec3d(-MAHJONG_TILE_WIDTH.toDouble() - tileGap, 0.0, 0.0)     //南
            2 -> Vec3d(0.0, 0.0, -MAHJONG_TILE_WIDTH.toDouble() - tileGap)     //西
            else -> Vec3d(MAHJONG_TILE_WIDTH.toDouble() + tileGap, 0.0, 0.0)   //北
        }
        val halfVerticalTileOffset = when (seatIndex) { //以牌桌边缘为底, 垂直摆放的半张牌的偏移量 (由右手往左手方向)
            0 -> verticalTileOffset.multiply(1.0, 1.0, 0.5)      //东
            3 -> verticalTileOffset.multiply(0.5, 1.0, 1.0)     //南
            2 -> verticalTileOffset.multiply(1.0, 1.0, 0.5)     //西
            else -> verticalTileOffset.multiply(0.5, 1.0, 1.0)   //北
        }
        val horizontalTileOffset = when (seatIndex) { //以牌桌边缘为底, 水平摆放的牌的偏移量 (由右手往左手方向)
            0 -> Vec3d(0.0, 0.0, MAHJONG_TILE_HEIGHT.toDouble() + tileGap)         //东
            3 -> Vec3d(-MAHJONG_TILE_HEIGHT.toDouble() - tileGap, 0.0, 0.0)        //南
            2 -> Vec3d(0.0, 0.0, -MAHJONG_TILE_HEIGHT.toDouble() - tileGap)        //西
            else -> Vec3d(MAHJONG_TILE_HEIGHT.toDouble() + tileGap, 0.0, 0.0)      //北
        }
        val halfHorizontalTileOffset = when (seatIndex) { //以牌桌边缘为底, 水平摆放的牌的偏移量 (由右手往左手方向)
            0 -> horizontalTileOffset.multiply(1.0, 1.0, 0.5)         //东
            3 -> horizontalTileOffset.multiply(0.5, 1.0, 1.0)        //南
            2 -> horizontalTileOffset.multiply(1.0, 1.0, 0.5)        //西
            else -> horizontalTileOffset.multiply(0.5, 1.0, 1.0)      //北
        }
        val kakanOffset = when (seatIndex) {
            0 -> Vec3d(-MAHJONG_TILE_WIDTH.toDouble() - tileGap, 0.0, 0.0)         //东
            3 -> Vec3d(0.0, 0.0, -MAHJONG_TILE_WIDTH.toDouble() - tileGap)        //南
            2 -> Vec3d(MAHJONG_TILE_WIDTH.toDouble() + tileGap, 0.0, 0.0)        //西
            else -> Vec3d(0.0, 0.0, MAHJONG_TILE_WIDTH.toDouble() + tileGap)      //北
        }
        val halfGapBetweenHeightAndWidth = (MAHJONG_TILE_HEIGHT - MAHJONG_TILE_WIDTH) / 2.0
        val horizontalTileGravityOffset = when (seatIndex) { //以牌桌边缘为底, 水平摆放的牌的偏移量 (由右手往左手方向)
            0 -> Vec3d(halfGapBetweenHeightAndWidth, 0.0, 0.0)         //东
            3 -> Vec3d(0.0, 0.0, halfGapBetweenHeightAndWidth)        //南
            2 -> Vec3d(-halfGapBetweenHeightAndWidth, 0.0, 0.0)        //西
            else -> Vec3d(0.0, 0.0, -halfGapBetweenHeightAndWidth)      //北
        }

        val tileRot = when (seatIndex) { //牌的面朝方向
            0 -> -90f       //东
            3 -> 0f         //南
            2 -> 90f        //西
            else -> 180f    //北
        }
        var nowPos = startingPos
        var tileCount = 0
        var lastTile: MahjongTileEntity? = null
        var lastClaimTile: MahjongTileEntity? = null

        fun placeEachTile(fuuro: Fuuro) {
            // 是不是加杠
            val isKakan = fuuro.mentsu is Kakantsu

            // 所有要进行遍历的牌
            val tiles =
                if (isKakan) fuuro.tileMjEntities.toMutableList()  // 加杠的副露是不进行 sortedByDescending 排序的
                else fuuro.tileMjEntities.sortedByDescending { it.mahjongTile.sortOrder }.toMutableList()

            // 加杠牌必定是最后一张, 先将加杠牌移出列表, 最后再加入
            val kakanTile = if (isKakan) tiles.removeLast() else null

            // 再将被鸣的牌移出列表
            tiles -= fuuro.claimTile

            // 再将被鸣的牌放回列表上应该存放的位置
            when (fuuro.claimTarget) {
                ClaimTarget.RIGHT -> tiles.add(0, fuuro.claimTile)
                ClaimTarget.LEFT -> tiles.add(fuuro.claimTile)
                ClaimTarget.ACROSS -> tiles.add(1, fuuro.claimTile)
                else -> {}
            }

            // 遍历每个牌并设置位置
            tiles.forEach {
                // 这张牌是不是被鸣的牌
                val isClaimTile = it == fuuro.claimTile

                // 上个牌是不是水平摆设的
                val isLastTileHorizontal = lastTile != null && lastClaimTile != null && lastTile == lastClaimTile

                // 位置
                nowPos = when {
                    // 这是第一张牌
                    tileCount == 0 -> {
                        if (isClaimTile) nowPos.add(halfHorizontalTileOffset)
                        else nowPos.add(halfVerticalTileOffset)
                    }
                    // 这是被鸣的牌 或者 上一张牌是水平的
                    isClaimTile || isLastTileHorizontal -> {
                        if (isClaimTile && isLastTileHorizontal) nowPos.add(horizontalTileOffset)
                        else nowPos.add(halfHorizontalTileOffset).add(halfVerticalTileOffset)
                    }
                    // 其他剩下的情况，牌都是垂直摆设的
                    else -> nowPos.add(verticalTileOffset)
                }

                val pos = if (!isClaimTile) nowPos else nowPos.add(horizontalTileGravityOffset)
                val yRot =  // 牌的面向方向
                    if (it == fuuro.claimTile)
                        when (fuuro.claimTarget) {  // 根据鸣牌对象转向
                            ClaimTarget.RIGHT -> tileRot + 90
                            ClaimTarget.LEFT -> tileRot - 90
                            ClaimTarget.ACROSS -> tileRot + 90
                            else -> tileRot
                        }
                    else tileRot

                // 将牌传送
                it.refreshPositionAndAngles(pos.x, pos.y, pos.z, yRot, 0f)
                it.facing = TileFacing.UP

                lastTile = it
                if (isClaimTile) lastClaimTile = it
                tileCount++
            }

            // 有加杠牌的话，最后再放到被鸣的牌的上方
            kakanTile?.let {
                val claimTile = fuuro.claimTile
                val claimTilePos = fuuro.claimTile.pos
                val pos = claimTilePos.add(kakanOffset)
                it.refreshPositionAndAngles(pos.x, pos.y, pos.z, claimTile.yaw, 0f) //传送到鸣的牌的上面
                it.facing = TileFacing.UP
            }
        }

        // 将副露按照顺序摆放
        player.fuuroList.forEach { fuuro ->
            if (fuuro.mentsu is Kantsu && fuuro.claimTarget == ClaimTarget.SELF) { // 是暗杠
                // 暗杠的牌都是直立的，所以比较好处理
                fuuro.tileMjEntities.forEachIndexed { index, tileMjEntity ->
                    // 上个牌是不是水平摆设的
                    val isLastTileHorizontal = lastTile != null && lastClaimTile != null && lastTile == lastClaimTile

                    // 位置
                    nowPos = when {
                        tileCount == 0 -> nowPos.add(halfVerticalTileOffset)
                        isLastTileHorizontal -> nowPos.add(halfHorizontalTileOffset).add(halfVerticalTileOffset)
                        else -> nowPos.add(verticalTileOffset)
                    }

                    // 将牌传送
                    tileMjEntity.refreshPositionAndAngles(nowPos.x, nowPos.y, nowPos.z, tileRot, 0f)
                    // index == 1 or 2 是中间两张牌->面朝上, 否则是旁边两张->面朝下
                    tileMjEntity.facing = if (index == 1 || index == 2) TileFacing.UP else TileFacing.DOWN

                    lastTile = tileMjEntity
                    tileCount++
                }
            } else { // 不是暗杠
                placeEachTile(fuuro = fuuro)
            }
        }
    }

    /**
     * 三麻抜北: 从牌山尾补一张牌,
     * 和岭上牌不同, 这里不会增加 [kanCount], 也不会翻新的宝牌指示牌
     *
     * @return 补到的牌 (牌山已经空了的极端情况会回传手牌最后一张, 避免流程炸掉)
     * */
    fun drawNukiTile(player: MahjongPlayerBase): MahjongTileEntity {
        val tile = wall.removeLastOrNull() ?: return player.hands.last()
        player.drawTile(tile)
        return tile
    }

    /**
     * 三麻抜北: 把拔出去的北牌摆在手牌外侧 (靠玩家右手边)
     * */
    fun sortNuki(player: MahjongPlayerBase) {
        if (player !in game.seat) return
        if (player.nukiTiles.isEmpty()) return
        val seatIndex = game.seat.indexOf(player)
        val tableCenterPos = game.tableCenterPos
        val outOffset = 1.0 + MAHJONG_TILE_DEPTH + MAHJONG_TILE_HEIGHT * 1.75
        val step = MAHJONG_TILE_WIDTH + MAHJONG_TILE_SMALL_PADDING
        val handHalfWidth = (player.hands.size * step) / 2.0
        player.nukiTiles.forEachIndexed { index, tile ->
            val offset = handHalfWidth + index * step
            when (seatIndex) { //方向和 [sortHands] 一致, 从手牌右端继续往外排
                0 -> tableCenterPos.add(outOffset, 0.0, offset).also {
                    tile.refreshPositionAfterTeleport(it)
                    tile.yaw = -90f
                } //东
                3 -> tableCenterPos.add(-offset, 0.0, outOffset).also {
                    tile.refreshPositionAfterTeleport(it)
                    tile.yaw = 0f
                } //南
                2 -> tableCenterPos.add(-outOffset, 0.0, -offset).also {
                    tile.refreshPositionAfterTeleport(it)
                    tile.yaw = 90f
                } //西
                else -> tableCenterPos.add(offset, 0.0, -outOffset).also {
                    tile.refreshPositionAfterTeleport(it)
                    tile.yaw = 180f
                } //北
            }
            tile.facing = TileFacing.UP
            tile.inGameTilePosition = TilePosition.OTHER
        }
    }

    /**
     * 玩家摸岭上牌,
     * 在玩家进行杠之后使用
     *
     * @return 摸到的岭上牌
     * */
    fun drawRinshanTile(player: MahjongPlayerBase): MahjongTileEntity {
        val tile = if (kanCount % 2 == 0) deadWall[deadWall.size - 2] else deadWall[deadWall.size - 1]
        val lastWallTile = wall.removeLast()
        val direction = deadWall.last().horizontalFacing // 王牌区方向以最后一敦为准
        val baseTile = deadWall[1] //补进来的岭上牌都固定会在第 2 张牌的旁边
        val basePos = baseTile.pos
        val tilePos = when (direction) {
            Direction.EAST -> basePos.add(0.0, 0.0, -MAHJONG_TILE_WIDTH.toDouble())
            Direction.SOUTH -> basePos.add(MAHJONG_TILE_WIDTH.toDouble(), 0.0, 0.0)
            Direction.WEST -> basePos.add(0.0, 0.0, MAHJONG_TILE_WIDTH.toDouble())
            Direction.NORTH -> basePos.add(-MAHJONG_TILE_WIDTH.toDouble(), 0.0, 0.0)
            else -> basePos
        }
        lastWallTile.refreshPositionAfterTeleport(tilePos)
        lastWallTile.yaw = direction.asRotation()
        deadWall.add(0, lastWallTile)
        deadWall -= tile
        kanCount++
        flipDoraIndicators()
        player.drawTile(tile)
        if (kanCount % 2 == 1) {
            val nowLastWallTile = wall.last() //这张牌要往下降, 不然会浮空
            nowLastWallTile.refreshPositionAfterTeleport(
                nowLastWallTile.pos.add(
                    0.0,
                    -MAHJONG_TILE_DEPTH.toDouble(),
                    0.0
                )
            )
        }
        return tile
    }

    /**
     * 放置这个玩家的立直棒,
     * 只有宣告立直的时候会呼叫这边,
     * 这一根立直棒会在牌桌中间, 多的立直棒会在副露的位置
     * */
    fun putRiichiStick(player: MahjongPlayerBase) {
        if (player !in game.seat) return
        val seatIndex = game.seat.indexOf(player)
        val tableCenterPos = game.tableCenterPos
        val halfWidthOfSixTiles = (MAHJONG_TILE_WIDTH * 6 / 2.0)
        val paddingFromCenter = //距离牌桌中心到点棒的距离 y
            halfWidthOfSixTiles - MAHJONG_POINT_STICK_DEPTH / 2.0
        val stickPos = when (seatIndex) { //这个是点棒的座标
            //这里的方向就是以游戏内的面朝东为准，按照麻将桌"逆"时针东南西北的方向
            0 -> tableCenterPos.add(paddingFromCenter, 0.0, 0.0) //东
            3 -> tableCenterPos.add(0.0, 0.0, paddingFromCenter) //南
            2 -> tableCenterPos.add(-paddingFromCenter, 0.0, 0.0) //西
            else -> tableCenterPos.add(0.0, 0.0, -paddingFromCenter) //北
        }
        val stickYaw = when (seatIndex) {
            0 -> -90f //东
            3 -> 0f //南
            2 -> 90f //西
            else -> 180f //北
        }
        player.sticks += MahjongScoringStickEntity(world = game.world).apply {
            code = ScoringStick.P1000.code //1000 点棒的编号
            gameBlockPos = game.pos
            isSpawnedByGame = true
            refreshPositionAfterTeleport(stickPos) //先移到位置上
            yaw = stickYaw //再转个方向
            ServerScheduler.scheduleDelayAction { world.spawnEntity(this) } //再生成到世界上
        }
    }

    /**
     * 将指定的 [stick] 传送到积棒区的 [index] 位置上,
     * 积棒区在副露的位置
     * */
    private fun moveStickTo(player: MahjongPlayerBase, index: Int, stick: MahjongScoringStickEntity) {
        if (player !in game.seat) return
        val seatIndex = game.seat.indexOf(player)
        val tableCenterPos = game.tableCenterPos
        //记得实体的座标是以实体本身底部的正中央为主
        val halfTableLengthNoBorder = 0.5 + 15.0 / 16.0 //没有包含边框的半张桌子长度
        val halfWidthOfStick = MAHJONG_POINT_STICK_WIDTH / 2.0 //积棒长度的一半
        val halfDepthOfStick = MAHJONG_POINT_STICK_DEPTH / 2.0 //积棒深度的一半
        val startingPos = when (seatIndex) { //这个是玩家右手方的牌桌角落的第一根积棒的座标
            //这里的方向就是以游戏内的面朝东为准，按照麻将桌"逆"时针东南西北的方向
            0 -> tableCenterPos.add(
                halfTableLengthNoBorder - halfWidthOfStick,
                0.0,
                -halfTableLengthNoBorder + halfDepthOfStick
            ) //东
            3 -> tableCenterPos.add(
                halfTableLengthNoBorder - halfDepthOfStick,
                0.0,
                halfTableLengthNoBorder - halfWidthOfStick
            ) //南
            2 -> tableCenterPos.add(
                -halfTableLengthNoBorder + halfWidthOfStick,
                0.0,
                halfTableLengthNoBorder - halfDepthOfStick
            ) //西
            else -> tableCenterPos.add(
                -halfTableLengthNoBorder + halfDepthOfStick,
                0.0,
                -halfTableLengthNoBorder + halfWidthOfStick
            ) //北
        }
        val stickGap = MAHJONG_TILE_SMALL_PADDING
        val stickOffset = when (seatIndex) { //以牌桌边缘为底, 垂直摆放的积棒的偏移量 (由右手往左手方向)
            0 -> Vec3d(0.0, 0.0, MAHJONG_POINT_STICK_DEPTH + stickGap)      //东
            3 -> Vec3d(-MAHJONG_POINT_STICK_DEPTH - stickGap, 0.0, 0.0)     //南
            2 -> Vec3d(0.0, 0.0, -MAHJONG_POINT_STICK_DEPTH - stickGap)     //西
            else -> Vec3d(MAHJONG_POINT_STICK_DEPTH + stickGap, 0.0, 0.0)   //北
        }
        val stackOffset = //摆放的积棒同一叠超过 STICKS_PER_STACK 就会往上叠
            Vec3d(0.0, MAHJONG_POINT_STICK_HEIGHT + stickGap, 0.0)
        val stickYaw = when (seatIndex) {
            0 -> -90f //东
            3 -> 0f //南
            2 -> 90f //西
            else -> 180f //北
        } - 90f //调整积棒的方向
        val stackIndex = (index / MahjongGame.STICKS_PER_STACK).toDouble() //这是第几叠
        val stickIndex = (index % MahjongGame.STICKS_PER_STACK).toDouble() //这根积棒在这叠的第几个位置
        val offsetXZ = stickOffset.multiply(stickIndex, stickIndex, stickIndex) // XZ平面上的偏移
        val offsetY = stackOffset.multiply(stackIndex, stackIndex, stackIndex)  // Y轴上的偏移
        val pos = startingPos.add(offsetXZ).add(offsetY)
        stick.yaw = stickYaw
        stick.refreshPositionAfterTeleport(pos)
    }

    /**
     * 将指定的 [stick] 放到积棒区的最后位置上
     * */
    private fun moveStickToLast(player: MahjongPlayerBase, stick: MahjongScoringStickEntity) =
        moveStickTo(player = player, stick = stick, index = player.sticks.lastIndex + 1)

    /**
     * 重新排列这个玩家的积棒
     * */
    fun resortSticks(player: MahjongPlayerBase) =
        player.sticks.sortedBy { it.code } //先按照外观排列
            .forEachIndexed { index, stick ->
                moveStickTo( //再移动到对应位置
                    player = player,
                    index = index,
                    stick = stick
                )
            }

    /**
     * 放置这个玩家的场棒,
     * 只有流局的时候会呼叫这边,
     * 这一根场棒会放在这个玩家的最后一根的积棒的旁边
     * */
    fun addHonbaStick(player: MahjongPlayerBase) {
        if (player !in game.seat) return
        player.sticks += MahjongScoringStickEntity(world = game.world).apply {
            code = ScoringStick.P100.code
            gameBlockPos = game.pos
            isSpawnedByGame = true
            moveStickToLast(player = player, stick = this) //这时候场棒还没生到世界上
            ServerScheduler.scheduleDelayAction { world.spawnEntity(this) }
        }
    }

    /**
     * 移除这个玩家积棒中的所有场棒
     * 只有庄家下庄会呼叫
     * */
    fun removeHonbaSticks(player: MahjongPlayerBase) {
        if (player !in game.seat) return
        player.sticks.filter { it.scoringStick == ScoringStick.P100 }.forEach { //场棒是 100 点棒
            it.remove(Entity.RemovalReason.DISCARDED)
            player.sticks -= it
        }
        resortSticks(player) //移除完所有场棒, 重新排列一下剩下的积棒
    }
}