package doublemoon.mahjonggame.game.mahjong.riichi.player

import doublemoon.mahjonggame.entity.*
import doublemoon.mahjonggame.game.GamePlayer
import doublemoon.mahjonggame.game.mahjong.riichi.model.*
import doublemoon.mahjonggame.logger
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import org.mahjong4j.*
import org.mahjong4j.hands.*
import org.mahjong4j.tile.Tile
import org.mahjong4j.tile.TileType
import org.mahjong4j.yaku.normals.NormalYaku
import org.mahjong4j.yaku.yakuman.Yakuman
import kotlin.math.absoluteValue

abstract class MahjongPlayerBase : GamePlayer {
    /**
     * 手牌
     * */
    val hands: MutableList<MahjongTileEntity> = mutableListOf()

    /**
     * 自动整理手牌 [hands]
     * */
    var autoArrangeHands: Boolean = true

    /**
     * 副露
     * */
    val fuuroList: MutableList<Fuuro> = mutableListOf()

    /**
     * 玩家的立直宣告牌,
     * 出现在 [discardedTilesForDisplay] 会横著摆的那张
     * */
    var riichiSengenTile: MahjongTileEntity? = null

    /**
     * 丢弃过的牌 (被人吃、碰、杠的时候,不会影响其中的内容),
     * 拿来计算振听的情况
     * */
    val discardedTiles: MutableList<MahjongTileEntity> = mutableListOf()

    /**
     * 显示用的丢牌堆 (被人吃、碰、杠的时候,会对这里的内容进行处理)
     * */
    val discardedTilesForDisplay: MutableList<MahjongTileEntity> = mutableListOf()

    /**
     * 在麻将桌上准备好开始游戏
     * */
    open var ready: Boolean = false

    /**
     * 立直状态
     * */
    var riichi: Boolean = false

    /**
     * 双立直状态
     * */
    var doubleRiichi: Boolean = false

    /**
     * 四川血战到底: 这一局已经胡牌并退出,
     * 人还在座位上 (手牌摊开), 但不再摸牌 / 出牌 / 鸣牌
     * */
    var hasWon: Boolean = false

    /**
     * 三麻抜北: 已经拔出去的北牌 (摆在手牌外侧)
     * */
    val nukiTiles: MutableList<MahjongTileEntity> = mutableListOf()

    /**
     * 三麻抜北: 抜北宝牌的数量, 每张算 1 番
     * */
    var nukiDoraCount: Int = 0

    /**
     * 四川血戰到底: 本局中透過「刮風下雨」(槓牌) 收取到的總點數,
     * 本局結束時沒胡牌的人要退稅 (把這些分退回去)
     * */
    var kanScoreCollected: Int = 0

    /**
     * 摆放在这个玩家面前的 立直棒 数量
     * */
    val riichiStickAmount: Int
        get() = sticks.count { it.scoringStick == ScoringStick.P1000 } //立直棒是 1000 点棒

    /**
     * 这个玩家的所有 立直棒, 场棒
     * */
    val sticks: MutableList<MahjongScoringStickEntity> = mutableListOf()

    /**
     * 玩家的点数
     * */
    var points: Int = 0

    /**
     * 门前清,
     * 暗杠也算门清
     * */
    val isMenzenchin: Boolean
        get() = fuuroList.isEmpty() ||  // 没有副露
                fuuroList.all { it.mentsu is Kantsu && !it.mentsu.isOpen }  // 副露都是暗杠

    /**
     * 当前能不能立直,
     * 必须 门前清 且 尚未立直或双立直 且 [tilePairsForRiichi] 大小大于 0 且 [points] >= 1000
     * */
    val isRiichiable: Boolean
        get() = isMenzenchin && !(riichi || doubleRiichi) && tilePairsForRiichi.isNotEmpty() && points >= 1000

    /**
     * 基本思考时间
     * */
    var basicThinkingTime = 0

    /**
     * 额外思考时间
     * */
    var extraThinkingTime = 0

    /**
     * 手牌中的幺九牌的种类的数量
     * */
    val numbersOfYaochuuhaiTypes: Int
        get() = hands.map { it.mahjong4jTile }.distinct().count { it.isYaochu }

    /**
     * 传送的功能, 因为玩家的实体 [ServerPlayerEntity] 有内键玩家特殊的传送方法,
     * 这边写一个都有的呼叫方法, 方便统一使用
     * */
    abstract fun teleport(
        targetWorld: ServerWorld,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
    )

    /**
     * 玩家吃
     * */
    fun chii(
        mjTileEntity: MahjongTileEntity,
        tilePair: Pair<MahjongTile, MahjongTile>,
        target: MahjongPlayerBase,
        onChii: (MahjongPlayerBase) -> Unit = {},
    ) {
        onChii.invoke(this)
        val tileMj4jCodePair: Pair<Tile, Tile> = tilePair.first.mahjong4jTile to tilePair.second.mahjong4jTile
        val claimTarget = ClaimTarget.LEFT //只能吃上家, 这是绝对的结果
        val tileShuntsu = mutableListOf(
            mjTileEntity,
            hands.find { it.mahjong4jTile == tileMj4jCodePair.first }!!,
            hands.find { it.mahjong4jTile == tileMj4jCodePair.second }!!
        ).also {
            it.sortBy { tile -> tile.mahjong4jTile.code }
            it.forEach { tile -> tile.inGameTilePosition = TilePosition.OTHER }
        }
        val middleTile = tileShuntsu[1].mahjong4jTile
        val shuntsu = Shuntsu(true, middleTile)
        val fuuro =
            Fuuro(mentsu = shuntsu, tileMjEntities = tileShuntsu, claimTarget = claimTarget, claimTile = mjTileEntity)
        hands -= tileShuntsu.toMutableList().also { it -= mjTileEntity }.toSet() //将吃的牌从手牌中去除
        target.discardedTilesForDisplay -= mjTileEntity
        fuuroList += fuuro //添加到副露
    }

    /**
     * 玩家碰
     * */
    fun pon(
        mjTileEntity: MahjongTileEntity,
        claimTarget: ClaimTarget,
        target: MahjongPlayerBase,
        onPon: (MahjongPlayerBase) -> Unit = {},
    ) {
        onPon.invoke(this)
        val kotsu = Kotsu(true, mjTileEntity.mahjong4jTile)
        val tilesForPon = tilesForPon(mjTileEntity).onEach { it.inGameTilePosition = TilePosition.OTHER }
        val fuuro =
            Fuuro(mentsu = kotsu, tileMjEntities = tilesForPon, claimTarget = claimTarget, claimTile = mjTileEntity)
        hands -= tilesForPon.toSet() //将碰的牌从手牌中去除
        target.discardedTilesForDisplay -= mjTileEntity
        fuuroList += fuuro //添加到副露
    }

    /**
     * 玩家明杠
     * */
    fun minkan(
        mjTileEntity: MahjongTileEntity,
        claimTarget: ClaimTarget,
        target: MahjongPlayerBase,
        onMinkan: (MahjongPlayerBase) -> Unit = {},
    ) {
        onMinkan.invoke(this)
        val kantsu = Kantsu(true, mjTileEntity.mahjong4jTile)
        val tilesForMinkan = tilesForMinkan(mjTileEntity).onEach { it.inGameTilePosition = TilePosition.OTHER }
        val fuuro =
            Fuuro(mentsu = kantsu, tileMjEntities = tilesForMinkan, claimTarget = claimTarget, claimTile = mjTileEntity)
        hands -= tilesForMinkan.toSet() //将明杠的牌从手牌中去除
        target.discardedTilesForDisplay -= mjTileEntity
        fuuroList += fuuro //添加到副露
    }

    /**
     * 玩家暗杠
     * */
    fun ankan(mjTileEntity: MahjongTileEntity, onAnkan: (MahjongPlayerBase) -> Unit = {}) {
        onAnkan.invoke(this)
        val kantsu = Kantsu(false, mjTileEntity.mahjong4jTile)
        val tilesForAnkan = tilesForAnkan(mjTileEntity).onEach { it.inGameTilePosition = TilePosition.OTHER }
        val fuuro = Fuuro(
            mentsu = kantsu,
            tileMjEntities = tilesForAnkan,
            claimTarget = ClaimTarget.SELF,
            claimTile = mjTileEntity
        )
        hands -= tilesForAnkan.toSet() //将暗杠的牌从手牌中去除
        discardedTilesForDisplay -= mjTileEntity
        fuuroList += fuuro //添加到副露
    }

    /**
     * 玩家加杠明杠
     * */
    fun kakan(mjTileEntity: MahjongTileEntity, onKakan: (MahjongPlayerBase) -> Unit = {}) {
        onKakan.invoke(this)
        val minKotsu =
            fuuroList.find { mjTileEntity.mahjongTile in it.tileMjEntities.toMahjongTileList() && it.mentsu is Kotsu }
        fuuroList -= minKotsu!!
        val kakantsu = Kakantsu(mjTileEntity.mahjong4jTile)
        val tiles = minKotsu!!.tileMjEntities.toMutableList().also { it += mjTileEntity }
        val fuuro = Fuuro(
            mentsu = kakantsu,
            tileMjEntities = tiles,
            claimTarget = minKotsu.claimTarget, //加杠的鸣牌对象以原本的为主
            claimTile = minKotsu.claimTile ////加杠的鸣的牌以原本的为主
        )
        hands -= mjTileEntity //将暗杠的牌从手牌中去除
        mjTileEntity.inGameTilePosition = TilePosition.OTHER
        fuuroList += fuuro //添加到副露
    }

    /**
     * 手牌 [hands] 中有与 [mjTileEntity] 相同的 2 张牌以上就可以"碰",
     * 并且 [mjTileEntity] "不包含在" [hands] 之中
     * */
    fun canPon(mjTileEntity: MahjongTileEntity): Boolean =
        !(riichi || doubleRiichi) && sameTilesInHands(mjTileEntity).size >= 2

    /**
     * 手牌 [hands] 中有与 [mjTileEntity] 相同的 3 张牌以上就可以"明杠",
     * 并且 [mjTileEntity] "不包含在" [hands] 之中
     * */
    fun canMinkan(mjTileEntity: MahjongTileEntity): Boolean =
        !(riichi || doubleRiichi) && sameTilesInHands(mjTileEntity).size == 3

    /**
     * 手牌 [hands] 中有与副露 [fuuroList] 中的刻子相同的第 4 张牌就可以"加杠",
     * 加杠的情况不应该传入任何参数,只检查手上有没有任何一张在副露里的刻子的第 4 张牌 (因为加杠可以扣著, 轮到自己摸牌的时候随时可以杠)
     * */
    val canKakan: Boolean
        get() = tilesCanKakan.size > 0

    /**
     * 手牌 [hands] 中有相同的 4 张牌就可以"暗杠",
     * 暗杠的情况不应该传入任何参数,只检查手上有没有任何一组超过 4 张相同的牌 (因为暗杠可以扣著, 轮到自己摸牌的时候随时可以杠)
     * */
    val canAnkan: Boolean
        get() = tilesCanAnkan.isNotEmpty()

    /**
     * 手牌 [hands] 中有与 [mjTileEntity] 可以组顺子的牌就可以"吃",
     * */
    fun canChii(mjTileEntity: MahjongTileEntity): Boolean =
        !(riichi || doubleRiichi) && tilePairsForChii(mjTileEntity).isNotEmpty()

    /**
     * 执行前请先用 [canPon] 检查,
     * 返回在手牌 [hands] 之中可以与 [mjTileEntity] 进行"碰"的牌的列表,
     * 如果有红宝牌,除了 [mjTileEntity] 本身以外, 红宝牌会在列表中的最前方
     *
     * @return [mjTileEntity] 也包含在列表之中, 结果应该有 3 项
     * */
    private fun tilesForPon(mjTileEntity: MahjongTileEntity): List<MahjongTileEntity> =
        sameTilesInHands(mjTileEntity).apply {
            if (size > 2) { //赤宝牌会优先进行碰的动作
                this -= first { !it.mahjongTile.isRed } //减去第一张不是红宝牌的牌
                sortBy { it.mahjongTile.isRed } //红宝牌优先排在第一个
            }
            this += mjTileEntity
        }

    /**
     * 执行前请先用 [canMinkan] 检查,
     * 返回在手牌 [hands] 之中可以与 [mjTileEntity] 进行"明杠"的牌的列表,
     *
     * @return [mjTileEntity] 也包含在列表之中, 结果应该有 4 项
     * */
    private fun tilesForMinkan(mjTileEntity: MahjongTileEntity): List<MahjongTileEntity> =
        sameTilesInHands(mjTileEntity).also { it += mjTileEntity }

    /**
     * 执行前请先用 [canAnkan] 和 [tilesCanAnkan] 检查,
     * 返回在手牌 [hands] 之中可以与 [mjTileEntity] 进行"暗杠"的牌的列表,
     * [mjTileEntity] 也包含在列表之中, 结果应该有 4 项
     * */
    private fun tilesForAnkan(mjTileEntity: MahjongTileEntity): List<MahjongTileEntity> =
        sameTilesInHands(mjTileEntity)

    /**
     * [hands] 中可以进行暗杠的牌,
     * 在立直的时候,不会改变 [machi] 以及 暗杠的牌不能与手牌中组成任意顺子(即必须是独立的暗刻) 才可以暗杠
     *
     * @return 可以直接对在集合中的牌可以直接使用 [ankan]
     *
     * */
    val tilesCanAnkan: Set<MahjongTileEntity>
        get() = buildSet {
            hands.distinct().forEach { //先取得牌数符合暗杠门槛的牌
                val count = hands.count { tile -> tile.mahjong4jTile.code == it.mahjong4jTile.code }
                if (count == 4) this += it
            }
            if (!riichi && !doubleRiichi) return@buildSet //没有立直就结束计算
            /*
             * 在立直可以暗杠的时候, 绝对不会听可以暗杠的那张牌 (it.mahjong4jTile),
             * 如果没过滤掉会造成计算的时候可能会出现一张牌同时有 5 个在这个玩家上的 Bug,
             * 会触发 org.mahjong4j.MahjongTileOverFlowException: 麻雀の牌は4枚までしかありません
             * */
            forEach { //计算暗杠之后的 machi
                val handsCopy = hands.toMutableList()
                val anKanTilesInHands = hands.filter { tile -> tile.mahjong4jTile == it.mahjong4jTile }.toMutableList()
                handsCopy -= anKanTilesInHands.toSet()
                val fuuroListCopy = fuuroList.toMutableList().apply {
                    this += Fuuro(
                        mentsu = Kantsu(false, it.mahjong4jTile),
                        tileMjEntities = anKanTilesInHands,
                        claimTarget = ClaimTarget.SELF,
                        it
                    )
                }
                val mentsuList = fuuroListCopy.map { fuuro -> fuuro.mentsu }
                val calculatedMachi = buildList {
                    MahjongTile.entries.filter { mjTile ->
                        mjTile.mahjong4jTile != it.mahjong4jTile
                    }.forEach { mjTile -> //遍历所有牌
                        val mj4jTile = mjTile.mahjong4jTile
                        val nowHands =
                            handsCopy.toIntArray().apply { this[mj4jTile.code]++ }
                        val tilesWinnable = tilesWinnable(
                            hands = nowHands,
                            mentsuList = mentsuList,
                            lastTile = mj4jTile
                        )
                        if (tilesWinnable) this += mjTile //这张牌构成胜利的牌型, 加入列表中
                    }
                }
                if (calculatedMachi != machi) { //如果计算后的 machi 与计算前不相同, 不能暗杠这张牌
                    this -= it
                } else { //如果计算后的 machi 与计算前相同, 计算会不会影响其他的面子
                    /*
                    * 计算出来的 machi 相同的话也有例外情况, 像是 一色三同顺 要暗杠的情况 或者手拿 333 345 摸到 6 把 3 暗杠起来,
                    * 以上情况也不允许暗杠, 待补上
                    * 使用 machi 判断, 只要要暗杠的牌在手牌中可以组成顺子就不能暗杠，必须是独立存在的暗刻才能暗杠
                    * */
                    val otherTiles = hands.toIntArray()
                    val mentsuList1 = fuuroList.map { fuuro -> fuuro.mentsu } //从副露取得已经确定的面子
                    calculatedMachi.forEach { machiTile ->
                        val tile = machiTile.mahjong4jTile
                        val mj4jHands = Hands(otherTiles, tile, mentsuList1) //先初步计算牌型
                        val mentsuCompSet = mj4jHands.mentsuCompSet
                        val shuntsuList = //从所有可能的手牌中取得所有可以形成的顺子
                            mentsuCompSet.flatMap { mentsuComp -> mentsuComp.shuntsuList }
                        shuntsuList.forEach { shuntsu ->
                            val middleTile = shuntsu.tile //顺子中间这张牌
                            val previousTile = MahjongTile.entries[middleTile.code].previousTile.mahjong4jTile
                            val nextTile = MahjongTile.entries[middleTile.code].nextTile.mahjong4jTile
                            val shuntsuTiles = listOf(previousTile, middleTile, nextTile)
                            if (it.mahjong4jTile in shuntsuTiles) this -= it //立直后可以暗刻的牌必须不能在手牌中组成任何顺子
                        }
                    }
                }
            }
        }

    /**
     * [hands] 中可以进行加杠的牌,
     *
     * @return 可以直接对在集合中的牌可以直接使用 [kakan]
     * */
    private val tilesCanKakan: MutableSet<Pair<MahjongTileEntity, ClaimTarget>>
        get() = mutableSetOf<Pair<MahjongTileEntity, ClaimTarget>>().apply {
            fuuroList.filter { it.mentsu is Kotsu }.forEach { fuuro -> //遍历所有是刻子的副露
                val tile =
                    hands.find { it.mahjong4jTile.code == fuuro.claimTile.mahjong4jTile.code } //寻找手牌中与刻子的宣告牌 code 一样的牌
                if (tile != null) this += tile to fuuro.claimTarget
            }
        }

    /**
     * 手牌 [hands] 中有与 [mjTileEntity] 可以组顺子的牌就可以"吃",
     * 分三个情况判断: 1.被吃的牌在头 2.被吃的牌在中间 3.被吃的牌在尾,
     * 只要有一种情况存在就表示可以吃,
     * 并且 [mjTileEntity] "不包含在" [hands] 之中
     *
     * @return 玩家可以对 [mjTileEntity] 进行"吃"的所有组合
     * */
    private fun tilePairsForChii(mjTileEntity: MahjongTileEntity): List<Pair<MahjongTile, MahjongTile>> {
        val mj4jTile = mjTileEntity.mahjong4jTile
        if (mj4jTile.number == 0) return emptyList() //只有字牌 number 是 0, 不能吃
        val mjTile = mjTileEntity.mahjongTile
        val next = hands.find { it.mahjongTile == mjTile.nextTile }?.mahjongTile
        val nextNext = hands.find { it.mahjongTile == mjTile.nextTile.nextTile }?.mahjongTile
        val previous = hands.find { it.mahjongTile == mjTile.previousTile }?.mahjongTile
        val previousPrevious = hands.find { it.mahjongTile == mjTile.previousTile.previousTile }?.mahjongTile
        val pairs = mutableListOf<Pair<MahjongTile, MahjongTile>>()
        //被吃的牌在头, number 需小于 8, 只有在下张牌存在跟下下张牌存在时才成立
        if (mj4jTile.number < 8 && next != null && nextNext != null) pairs += next to nextNext
        //被吃的牌在中间, number 需在 2..8 之间, 只有在上张牌存在跟下张牌存在时才成立
        if (mj4jTile.number in 2..8 && previous != null && next != null) pairs += previous to next
        //被吃的牌在尾, number 需大于 2,只有在上张牌存在跟上上张牌存在时才成立
        if (mj4jTile.number > 2 && previous != null && previousPrevious != null) pairs += previous to previousPrevious

        val sameTypeRedFiveTile = //手中的同个种类的赤宝牌, (同种类的赤宝牌取第一张就好, 因为牌都一样是赤5)
            hands.filter { it.mahjongTile.isRed && it.mahjong4jTile.type == mj4jTile.type }.getOrNull(0)
        val canChiiWithRedFive = //可以使用红宝牌吃的情况, 被吃的牌编号是 3,4,6,7 且 手中有同个种类的赤宝牌
            (mj4jTile.number in 3..4 || mj4jTile.number in 6..7) && sameTypeRedFiveTile != null
        if (canChiiWithRedFive) { //如果可以用赤宝牌吃的话
            val redFiveTile = sameTypeRedFiveTile!! //赤宝牌
            val redFiveTileCode = redFiveTile.mahjong4jTile.code
            val targetCode = mj4jTile.code
            // 求赤宝牌跟被吃的牌的差距, 1 为赤宝牌与被吃的牌连续, 2 为赤宝牌与被吃的牌组成中洞 (不会出现 0 或 3 以上)
            val gap = redFiveTileCode - targetCode
            if (gap.absoluteValue == 1) { //赤宝牌与被吃的牌连续, 检查前后两张牌有没有存在
                val firstTile = MahjongTile.entries[minOf(redFiveTileCode, targetCode)].previousTile //开头的牌
                val lastTile = MahjongTile.entries[maxOf(redFiveTileCode, targetCode)].nextTile //结尾的牌
                val allTileInHands = //开头跟结尾的牌都存在手牌中
                    hands.any { it.mahjongTile == firstTile } && hands.any { it.mahjongTile == lastTile }
                if (allTileInHands) {
                    pairs += firstTile to lastTile
                }
            } else { //赤宝牌与被吃的牌组成中洞, 检查手牌有没有中间牌存在
                val midTileCode = (redFiveTileCode + targetCode) / 2 //中间牌的 code
                val midTile = MahjongTile.entries[midTileCode]
                val midTileInHands = hands.any { it.mahjongTile == midTile }
                if (midTileInHands) { //手牌中有存在中洞的这张牌
                    pairs += if (gap > 0) { //手中有 5, 6 万吃 7 万
                        redFiveTile.mahjongTile to midTile
                    } else { //手中有 4, 5 万吃 3 万
                        midTile to redFiveTile.mahjongTile
                    }
                }
            }
        }
        return pairs
    }

    /**
     * 需要在确保要碰的时候调用
     * */
    private fun tilePairForPon(mjTileEntity: MahjongTileEntity): Pair<MahjongTile, MahjongTile> {
        val tiles = tilesForPon(mjTileEntity)
        return tiles[0].mahjongTile to tiles[1].mahjongTile
    }

    /**
     * 询问玩家立直时使用的方法,
     * 资料是 <可以打的牌,打了之后听的牌>,
     * 调用的时候, 手牌应该会正好 14 张
     * */
    private val tilePairsForRiichi
        get() = buildList {
            if (hands.size != 14) return@buildList
            val listToAdd = buildList {
                hands.forEach { entity ->
                    val nowHands = hands.toMutableList().also { it -= entity }.toMahjongTileList()
                    val nowMachi = calculateMachi(hands = nowHands) //取得丢了这张牌后, 会听的牌的列表
                    if (nowMachi.isNotEmpty()) { //丢了这张牌会听
                        this += entity.mahjongTile to nowMachi
                    }
                }
            }
            addAll(listToAdd.distinct()) //这个列表内容要不能重复
        }

    /**
     * 取得 [hands] 中,
     * 与 [mjTileEntity] 相同编号的手牌,
     * */
    private fun sameTilesInHands(mjTileEntity: MahjongTileEntity): MutableList<MahjongTileEntity> =
        hands.filter { it.mahjong4jTile == mjTileEntity.mahjong4jTile }.toMutableList()

    /**
     * 是否听牌
     * */
    val isTenpai: Boolean
        get() = machi.isNotEmpty()

    /**
     * 听的牌的列表,
     * 同日文 "待ち" 的意思
     * */
    private val machi: List<MahjongTile>
        get() = calculateMachi()

    /**
     * 根据条件得到玩家听哪张牌, 只计算听的牌, 没计算有没有超过起胡翻数
     * 门清状态下传进来的 [hands] 加上 [fuuroList] 的牌应该只有 13 张, 第 14 张就是拿来侦测用的
     * */
    private fun calculateMachi(
        hands: List<MahjongTile> = this.hands.toMahjongTileList(),
        fuuroList: List<Fuuro> = this.fuuroList,
    ): List<MahjongTile> = MahjongTile.entries.filter { //过滤列表
        val tileInHandsCount = hands.count { tile -> tile.mahjong4jTile == it.mahjong4jTile }
        val tileInFuuroCount =
            fuuroList.sumOf { fuuro -> fuuro.tileMjEntities.count { entity -> entity.mahjong4jTile == it.mahjong4jTile } }
        val allTileHere = (tileInHandsCount + tileInFuuroCount) == 4 //这张牌的所有牌都在这个玩家手上
        if (allTileHere) return@filter false //如果玩家持有这张牌的 4 张就直接跳过计算这张牌
        val nowHands = hands.toIntArray().apply { this[it.mahjong4jTile.code]++ }
        val mentsuList = fuuroList.map { fuuro -> fuuro.mentsu }
        if (nowHands.sum() > 14) {
            logger.error("计算 machi 时, 手牌大于 14 张牌, 请检查错误")
            logger.error(nowHands.joinToString(prefix = "Hands: ") { code -> "$code" })
        }
        tilesWinnable(
            hands = nowHands,
            mentsuList = mentsuList,
            lastTile = it.mahjong4jTile,
        ) //这张牌构成胜利的牌型
    }

    /**
     * 计算 machi 与对应的翻数(役), 计算 machi 请参考 [calculateMachi]
     *
     * @return 回传听的牌与对应的翻数, -1 表示役满
     * */
    fun calculateMachiAndHan(
        hands: List<MahjongTile> = this.hands.toMahjongTileList(),
        fuuroList: List<Fuuro> = this.fuuroList,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Map<MahjongTile, Int> {
        val allMachi = calculateMachi(hands, fuuroList)
        return allMachi.associateWith { machiTile ->
            val yakuSettlement = calculateYakuSettlement(
                winningTile = machiTile,
                isWinningTileInHands = false,
                hands = hands,
                fuuroList = fuuroList,
                rule = rule,
                generalSituation = generalSituation,
                personalSituation = personalSituation,
                doraIndicators = emptyList(),
                uraDoraIndicators = emptyList()
            )
            yakuSettlement.let { if (it.yakuList.isNotEmpty() || it.yakumanList.isNotEmpty()) -1 else it.han }
        }
    }

    /**
     * 指定的牌 [tile] 是否振听,
     * 与 [canWin] 分开使用,在荣和前使用
     *
     * @param discards 所有玩家丢过的牌, 须按照丢的顺序排序
     * */
    fun isFuriten(tile: MahjongTileEntity, discards: List<MahjongTileEntity>): Boolean =
        isFuriten(tile.mahjong4jTile, discards.map { it.mahjong4jTile })

    /**
     * 振听使用 mahjong4j 的 [Tile] 处理, 避免出现赤宝牌没有算到的情况
     * */
    fun isFuriten(
        tile: Tile, discards: List<Tile>,
        machi: List<Tile> = this.machi.map { it.mahjong4jTile },
    ): Boolean {
        val discardedTiles = discardedTiles.map { it.mahjong4jTile }
        if (tile in discardedTiles) return true //一般振听
        //考虑同巡振听
        val lastDiscard = discardedTiles.last() //玩家丢过的最后一张牌
        val sameTurnStartIndex = discards.indexOf(lastDiscard) //取得丢过的最后一张牌在所有人丢过的牌中的索引
        for (index in sameTurnStartIndex until discards.lastIndex) { //从玩家最后丢的这张牌开始算到所有人丢过的牌的倒数第 2 张牌
            // (算到倒数第 2 张的意思是, 因为会在倒数第 1 张的时候才会调用这里询问玩家要不要荣和, 玩家如果没选择荣和才会振听)
            if (discards[index] in machi) return true //有丢过的牌是玩家听的牌, 同巡振听成立
        }
        //考虑立直振听
        val riichiSengenTile = riichiSengenTile?.mahjong4jTile ?: return false
        if (riichi || doubleRiichi) {
            val riichiStartIndex = discards.indexOf(riichiSengenTile)
            for (index in riichiStartIndex until discards.lastIndex) { //从玩家丢的立直宣言牌开始算到所有人丢过的牌的倒数第 2 张牌
                if (discards[index] in machi) return true //有丢过的牌是玩家听的牌, 立直振听成立
            }
        }
        return false
    }

    /**
     * 是否是一发
     *
     * @param players 所有玩家
     * @param discards 所有玩家丢过的牌, 须按照丢的顺序排序
     * */
    fun isIppatsu(players: List<MahjongPlayerBase>, discards: List<MahjongTileEntity>): Boolean {
        if (riichi) { //有立直
            val riichiSengenIndex = discards.indexOf(riichiSengenTile!!)
            if (discards.lastIndex - riichiSengenIndex > 4) return false //相差大于 4 次丢牌, 即不可能 1 发, 刚好差 4 次表示一发自摸
            //检查从立直宣言到最后丢的牌中间有任意一人鸣牌
            val someoneCalls = discards.slice(riichiSengenIndex..discards.lastIndex).any { tile ->
                players.any {
                    it.fuuroList.any { fuuro ->
                        tile in fuuro.tileMjEntities
                    }
                }
            }
            return !someoneCalls //只要有人鸣牌, 一发就不成立
        }
        return false
    }

    /**
     * 手上的牌 [hands] 加上 [tile] 是否构成国世无双的牌型 ([tile] 不包含在 [hands] 之中)
     * */
    fun isKokushimuso(tile: Tile): Boolean {
        val otherTiles = hands.toIntArray()
        //从副露取得已经确定的面子
        val mentusList = fuuroList.toMentsuList()
        //先初步计算牌型是否有符合赢的牌型
        val mj4jHands = Hands(otherTiles, tile, mentusList)
        return mj4jHands.isKokushimuso
    }


    /**
     * 将装著 [MahjongTileEntity] 的列表转成 Mahjong4j 判断手牌用的 [IntArray]
     * */
    @JvmName("toIntArrayMahjongTileEntity")
    private fun List<MahjongTileEntity>.toIntArray() =
        IntArray(Tile.entries.size) { code -> this.count { it.mahjong4jTile.code == code } }

    /**
     * 将装著 [MahjongTile] 的列表转成 Mahjong4j 判断手牌用的 [IntArray]
     * */
    private fun List<MahjongTile>.toIntArray() =
        IntArray(Tile.entries.size) { code -> this.count { it.mahjong4jTile.code == code } }

    private fun List<Fuuro>.toMentsuList() = this.map { it.mentsu }

    /**
     * 判断胜利的方法, 从所有输入的参数计算这个组合能赢吗,
     * 振听需另外使用 [isFuriten] 来判断
     * */
    fun canWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile> = this.hands.toMahjongTileList(),
        fuuroList: List<Fuuro> = this.fuuroList,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
    ): Boolean {
        //四川麻将的“缺一门”是胡牌的前置条件, 不符合就直接不能胡 (也不用算役了)
        if (rule.gameStyle == GameStyle.SICHUAN &&
            !isMissingOneSuit(winningTile, isWinningTileInHands, hands, fuuroList)
        ) return false
        val yakuSettlement = calculateYakuSettlement(
            winningTile = winningTile,
            isWinningTileInHands = isWinningTileInHands,
            hands = hands,
            fuuroList = fuuroList,
            rule = rule,
            generalSituation = generalSituation,
            personalSituation = personalSituation
        )
        return with(yakuSettlement) { yakumanList.isNotEmpty() || doubleYakumanList.isNotEmpty() || han >= rule.minimumHan.han }
    }

    /**
     * 四川麻将: 这副牌是不是“缺一门”
     * 手牌 + 副露 + 要胡的那张牌, 不能同时出现 万 / 筒 / 条 三门
     *
     * @param winningTile 要胡的牌
     * @param isWinningTileInHands [winningTile] 是否已经在 [hands] 里
     * */
    fun isMissingOneSuit(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile> = this.hands.toMahjongTileList(),
        fuuroList: List<Fuuro> = this.fuuroList,
    ): Boolean {
        val suits = mutableSetOf<TileType>()
        hands.forEach { suits += it.mahjong4jTile.type }
        fuuroList.forEach { fuuro -> fuuro.tileMjEntities.forEach { suits += it.mahjongTile.mahjong4jTile.type } }
        if (!isWinningTileInHands) suits += winningTile.mahjong4jTile.type
        val numberSuits = suits.count {
            it == TileType.MANZU || it == TileType.PINZU || it == TileType.SOHZU
        }
        return numberSuits <= 2
    }

    /**
     * 四川麻将: 现在的手牌 (含副露) 是不是已经缺一门了
     * 用在血战到底结束时的“花猪”判定
     * */
    val isMissingOneSuitNow: Boolean
        get() {
            val suits = mutableSetOf<TileType>()
            hands.forEach { suits += it.mahjong4jTile.type }
            fuuroList.forEach { fuuro -> fuuro.tileMjEntities.forEach { suits += it.mahjongTile.mahjong4jTile.type } }
            return suits.count {
                it == TileType.MANZU || it == TileType.PINZU || it == TileType.SOHZU
            } <= 2
        }

    /**
     * 三麻: 手上有没有北 (能不能抜北)
     * */
    val hasNorthTile: Boolean
        get() = hands.any { it.mahjongTile == MahjongTile.NORTH }

    /**
     * 三麻抜北: 把手上的一张北拔出去, 变成抜北宝牌,
     * 牌会留在 [nukiTiles] 里, 由 [MahjongBoard.sortNuki] 摆到手牌外侧
     *
     * @return 被拔出去的北, 手上没有北则回传 null
     * */
    fun nukiNorth(): MahjongTileEntity? {
        val tile = hands.findLast { it.mahjongTile == MahjongTile.NORTH } ?: return null
        hands -= tile
        tile.facing = TileFacing.UP
        tile.inGameTilePosition = TilePosition.OTHER
        nukiTiles += tile
        nukiDoraCount++
        return tile
    }

    /**
     * 判断输入的这组资料是否组成能赢的状态, 不计算任何其他条件
     *
     * @param [hands] 必须包含 [lastTile]
     * @return [hands] 和 [mentsuList] 和 [lastTile] , 有没有构成胜利的基本条件
     * */
    private fun tilesWinnable(
        hands: IntArray,
        mentsuList: List<Mentsu>,
        lastTile: Tile,
    ): Boolean = Hands(hands, lastTile, mentsuList).canWin //在 Hands 初始化的时候就有计算牌型是否有符合赢的牌型

    /**
     * 计算所有规则后得到的结果
     *
     * @param winningTile 赢的牌
     * @param isWinningTileInHands 赢的牌是否在手牌中
     * */
    private fun calculateYakuSettlement(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        hands: List<MahjongTile>,
        fuuroList: List<Fuuro>,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        doraIndicators: List<MahjongTile> = listOf(),
        uraDoraIndicators: List<MahjongTile> = listOf(),
    ): YakuSettlement {
        val handsIntArray = hands.toIntArray().also { if (!isWinningTileInHands) it[winningTile.mahjong4jTile.code]++ }
        val mentsuList = fuuroList.toMentsuList() //从副露取得已知的面子
        val mj4jHands = Hands(handsIntArray, winningTile.mahjong4jTile, mentsuList)
        if (!mj4jHands.canWin) return YakuSettlement.NO_YAKU
        val mj4jPlayer = Player(mj4jHands, generalSituation, personalSituation).apply { calculate() }
        var finalHan = 0 //最后计算完的翻数, 役满不会计算
        var finalFu = 0 //最后计算完的符数, 役满不会计算
        var finalRedFiveCount = 0 //最后计算完的赤宝牌数量, 役满不会计算
        var finalNormalYakuList = mutableListOf<NormalYaku>() //最后计算完的役种
        val finalYakumanList = mj4jPlayer.yakumanList.toMutableList()
        val finalDoubleYakumanList = mutableListOf<DoubleYakuman>()
        //下面开始进行各种计算
        if (finalYakumanList.isNotEmpty()) { //有役满出现, 只计算役满
            if (!rule.localYaku) { //没开启古役
                if (Yakuman.RENHO in finalYakumanList) finalYakumanList -= Yakuman.RENHO //去掉人和
            }
            //有双倍役满的情况 (ex: 大四喜, 四暗刻单骑, 纯正九莲宝灯, 国士无双十三面)
            val handsWithoutWinningTile =
                hands.toMutableList().also { if (isWinningTileInHands) it -= winningTile } //拿到一个没有包含 winningTile 的手牌
            val machiBeforeWin = calculateMachi(handsWithoutWinningTile, fuuroList) //计算还没包含 winningTile 时候的 machi
            when {
                Yakuman.DAISUSHI in finalYakumanList -> { //大四喜当作双倍役满
                    finalYakumanList -= Yakuman.DAISUSHI
                    finalDoubleYakumanList += DoubleYakuman.DAISUSHI
                }
                Yakuman.KOKUSHIMUSO in finalYakumanList && machiBeforeWin.size == 13 -> { //如果国士无双是十三面听当作双倍役满
                    finalYakumanList -= Yakuman.KOKUSHIMUSO
                    finalDoubleYakumanList += DoubleYakuman.KOKUSHIMUSO_JUSANMENMACHI
                }
                Yakuman.CHURENPOHTO in finalYakumanList && machiBeforeWin.size == 9 -> { //如果纯正九莲当作双倍役满
                    finalYakumanList -= Yakuman.CHURENPOHTO
                    finalDoubleYakumanList += DoubleYakuman.JUNSEI_CHURENPOHTO
                }
                Yakuman.SUANKO in finalYakumanList && machiBeforeWin.size == 1 -> { //如果四暗刻单骑当作双倍役满
                    finalYakumanList -= Yakuman.SUANKO
                    finalDoubleYakumanList += DoubleYakuman.SUANKO_TANKI
                }
            }
        } else { //没有役满, 只有一般役
            //先计算出最好的 翻数 and 役种 and 牌组-> finalHan, finalNormalYakuList, finalComp
            //finalComp 只要有听牌就不可能为 null, hands 初始化就计算过 mentsuCompSet 了
            var finalComp: MentsuComp =
                mj4jHands.mentsuCompSet.firstOrNull() ?: throw IllegalStateException("输入的手牌没有构成胜利的牌型")
            mj4jHands.mentsuCompSet.forEach { comp ->
                val yakuStock = mutableListOf<NormalYaku>()
                val resolverSet =
                    Mahjong4jYakuConfig.getNormalYakuResolverSet(comp, generalSituation, personalSituation)
                resolverSet.filter { it.isMatch }.forEach { yakuStock += it.normalYaku }

                //没开启食断 且 没有门前清 且 有断幺九, 去掉多的断幺九
                //这个 mj4jHands.isOpen 表示有面子是 open 的状态, 即 没有门前清
                if (!rule.openTanyao && mj4jHands.isOpen && NormalYaku.TANYAO in yakuStock) yakuStock -= NormalYaku.TANYAO

                val hanSum = if (mj4jHands.isOpen) yakuStock.sumOf { it.kuisagari } else yakuStock.sumOf { it.han }
                if (hanSum > finalHan) {
                    finalHan = hanSum
                    finalNormalYakuList = yakuStock
                    finalComp = comp
                }
            }
            //四川麻将: 自摸限定, 自摸本身就当 1 番
            //(不然有副露、又没有日本麻将的役时, 就算牌型成立+缺一门也会因为 0 番而不能胡)
            if (rule.gameStyle == GameStyle.SICHUAN &&
                personalSituation.isTsumo &&
                NormalYaku.TSUMO !in finalNormalYakuList
            ) {
                finalNormalYakuList += NormalYaku.TSUMO
                finalHan += NormalYaku.TSUMO.han
            }
            //计算加入宝牌的翻数
            if (finalHan >= rule.minimumHan.han) { //宝牌不能用于计算起胡翻数, 至少要有起胡翻数后才能算
                val handsComp = mj4jHands.handsComp
                val isRiichi = NormalYaku.REACH in finalNormalYakuList
                //宝牌
                val doraAmount = generalSituation.dora.sumOf { handsComp[it.code] }
                repeat(doraAmount) {
                    NormalYaku.DORA.apply { finalNormalYakuList += this; finalHan += this.han }
                }
                //里宝牌
                if (isRiichi) {
                    val uraDoraAmount = generalSituation.uradora.sumOf { handsComp[it.code] }
                    repeat(uraDoraAmount) {
                        NormalYaku.URADORA.apply { finalNormalYakuList += this; finalHan += this.han }
                    }
                }
                //赤宝牌
                if (rule.redFive != MahjongRule.RedFive.NONE) {
                    val handsRedFiveCount = this@MahjongPlayerBase.hands.count { it.mahjongTile.isRed }
                    val fuuroListRedFiveCount =
                        fuuroList.sumOf { it.tileMjEntities.count { tile -> tile.mahjongTile.isRed } }
                    finalRedFiveCount = handsRedFiveCount + fuuroListRedFiveCount //取得手上总共有多少红宝牌
                    finalHan += finalRedFiveCount
                }
                //三麻抜北: 每张北算 1 番 (规则上等同宝牌, 不能用来满足起胡番数, 所以放在这里)
                repeat(this@MahjongPlayerBase.nukiDoraCount) {
                    finalNormalYakuList += NormalYaku.DORA
                    finalHan += NormalYaku.DORA.han
                }
            }
            //计算符数
            finalFu = when {
                finalNormalYakuList.size == 0 -> 0
                NormalYaku.PINFU in finalNormalYakuList && NormalYaku.TSUMO in finalNormalYakuList -> 20
                NormalYaku.CHITOITSU in finalNormalYakuList -> 25
                else -> {
                    var tmpFu = 20
                    tmpFu += when {
                        personalSituation.isTsumo -> 2
                        !mj4jHands.isOpen -> 10
                        else -> 0
                    }
                    tmpFu += finalComp.allMentsu.sumOf { it.fu }
                    tmpFu +=
                        if (finalComp.isKanchan(mj4jHands.last) ||
                            finalComp.isPenchan(mj4jHands.last) ||
                            finalComp.isTanki(mj4jHands.last)
                        ) 2 else 0
                    val jantoTile = finalComp.janto.tile
                    if (jantoTile == generalSituation.bakaze) tmpFu += 2
                    if (jantoTile == personalSituation.jikaze) tmpFu += 2
                    if (jantoTile.type == TileType.SANGEN) tmpFu += 2
                    tmpFu
                }
            }
        }
        val fuuroListForSettlement = fuuroList.map { fuuro ->
            val isAnkan = fuuro.mentsu is Kantsu && !fuuro.mentsu.isOpen
            isAnkan to fuuro.tileMjEntities.toMahjongTileList()
        }
        val score = //只会计算这个玩家应该拿到多少点数
            if (finalYakumanList.isNotEmpty() || finalDoubleYakumanList.isNotEmpty()) { //有役满
                val yakumanScore = finalYakumanList.size * 32000
                val doubleYakumanScore = finalDoubleYakumanList.size * 64000
                val scoreSum = yakumanScore + doubleYakumanScore
                if (personalSituation.isParent) (scoreSum * 1.5).toInt() else scoreSum
            } else { //没有役满
                Score.calculateScore(personalSituation.isParent, finalHan, finalFu).ron
            }
        return YakuSettlement(
            mahjongPlayer = this@MahjongPlayerBase,
            yakuList = finalNormalYakuList,
            yakumanList = finalYakumanList,
            doubleYakumanList = finalDoubleYakumanList,
            redFiveCount = finalRedFiveCount,
            winningTile = winningTile,
            fuuroList = fuuroListForSettlement,
            doraIndicators = doraIndicators,
            uraDoraIndicators = uraDoraIndicators,
            fu = finalFu,
            han = finalHan,
            score = score
        )
    }

    /**
     * 根据条件计算出应该赢得的点数,
     * 请在荣和或自摸时调用
     * */
    fun calcYakuSettlementForWin(
        winningTile: MahjongTile,
        isWinningTileInHands: Boolean,
        rule: MahjongRule,
        generalSituation: GeneralSituation,
        personalSituation: PersonalSituation,
        doraIndicators: List<MahjongTile>,
        uraDoraIndicators: List<MahjongTile>,
    ): YakuSettlement = calculateYakuSettlement(
        winningTile = winningTile,
        isWinningTileInHands = isWinningTileInHands,
        hands = this.hands.toMahjongTileList(),
        fuuroList = this.fuuroList,
        rule = rule,
        generalSituation = generalSituation,
        personalSituation = personalSituation,
        doraIndicators = doraIndicators,
        uraDoraIndicators = uraDoraIndicators
    )

    /**
     * 询问并让玩家丢牌的功能
     *
     * @param timeoutTile 当超时的时候会丢出去的牌
     * @param cannotDiscardTiles 不能丢掉的牌的列表,需要输入 [MahjongTile]
     * @param skippable 客户端可以跳过的, 会影响到自动摸切而已
     * @return 要丢出的牌的编号 [MahjongTile.code]
     * */
    open suspend fun askToDiscardTile(
        timeoutTile: MahjongTile,
        cannotDiscardTiles: List<MahjongTile>,
        skippable: Boolean,
    ): MahjongTile = discardHint?.takeIf { it !in cannotDiscardTiles }
        ?: hands.findLast { it.mahjongTile !in cannotDiscardTiles }?.mahjongTile
        ?: timeoutTile

    /**
     * 出牌提示,
     * 四川麻将的机器人不知道要“缺一门”, 由 [MahjongGame] 在询问出牌前设定,
     * 机器人 (使用预设 [askToDiscardTile]) 会优先丢这张, 真人玩家的实作不受影响
     * */
    var discardHint: MahjongTile? = null

    /**
     * 询问玩家 [tile] 是否要 吃
     *
     * @param tilePairs 是能与 [tile] 编号可以组成顺子的牌的编号对
     * @return 要与之配对的两张牌, null 表示没有要吃
     * */
    open suspend fun askToChii(
        tile: MahjongTile,
        tilePairs: List<Pair<MahjongTile, MahjongTile>>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = null

    suspend fun askToChii(entity: MahjongTileEntity, target: ClaimTarget): Pair<MahjongTile, MahjongTile>? =
        askToChii(tile = entity.mahjongTile, tilePairs = tilePairsForChii(entity), target = target)

    /**
     * 询问玩家 [tile] 是否要 碰或吃
     *
     * @param tilePairsForChii 是能与 [tile] 可以组成顺子的牌对列表
     * @param tilePairForPon 是能与 [tile] 可以组成刻子的牌对
     * @return 要与之配对的两张牌,两个牌相同表示要碰, null 表示没有要碰或吃
     * */
    open suspend fun askToPonOrChii(
        tile: MahjongTile,
        tilePairsForChii: List<Pair<MahjongTile, MahjongTile>>,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Pair<MahjongTile, MahjongTile>? = null

    suspend fun askToPonOrChii(entity: MahjongTileEntity, target: ClaimTarget): Pair<MahjongTile, MahjongTile>? =
        askToPonOrChii(
            tile = entity.mahjongTile,
            tilePairsForChii = tilePairsForChii(entity),
            tilePairForPon = tilePairForPon(entity),
            target = target
        )

    /**
     * 询问玩家 编号 [tile] 的牌是否要 碰
     * @param tilePairForPon 是能与 [tile] 可以组成刻子的牌对
     * */
    open suspend fun askToPon(
        tile: MahjongTile,
        tilePairForPon: Pair<MahjongTile, MahjongTile>,
        target: ClaimTarget,
    ): Boolean = true

    suspend fun askToPon(entity: MahjongTileEntity, target: ClaimTarget): Boolean =
        askToPon(
            tile = entity.mahjongTile,
            tilePairForPon = tilePairForPon(entity),
            target = target
        )

    /**
     * 询问玩家是否要 暗杠 或 加杠 (因为两种情况有机会同时出现)
     *
     * @param canAnkanTiles 可以进行暗杠的牌
     * @param canKakanTiles 可以进行加杠的牌,必须附带原本的碰牌对象
     * @param rule 拿来处里有红宝牌出现的情况
     * @return 要暗杠或加杠的牌的编号 [MahjongTile.code], null 表示没有要暗杠或加杠
     * */
    open suspend fun askToAnkanOrKakan(
        canAnkanTiles: Set<MahjongTile>,
        canKakanTiles: Set<Pair<MahjongTile, ClaimTarget>>,
        rule: MahjongRule,
    ): MahjongTile? = null

    suspend fun askToAnkanOrKakan(rule: MahjongRule): MahjongTile? =
        askToAnkanOrKakan(
            canAnkanTiles = tilesCanAnkan.toList().toMahjongTileList().toSet(),
            canKakanTiles = tilesCanKakan.map { it.first.mahjongTile to it.second }.toSet(),
            rule = rule
        )


    /**
     * 询问玩家 [tile] 是否要 明杠
     * */
    open suspend fun askToMinkanOrPon(
        tile: MahjongTile,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior =
        MahjongGameBehavior.MINKAN

    suspend fun askToMinkanOrPon(
        mjTileEntity: MahjongTileEntity,
        target: ClaimTarget,
        rule: MahjongRule,
    ): MahjongGameBehavior =
        askToMinkanOrPon(tile = mjTileEntity.mahjongTile, target = target, rule = rule)

    /**
     * 询问玩家是否要立直
     * (询问立直应该要传入要丢的牌和对应会听的牌)
     * */
    open suspend fun askToRiichi(
        tilePairsForRiichi: List<Pair<MahjongTile, List<MahjongTile>>> = this.tilePairsForRiichi,
    ): MahjongTile? = null

    /**
     * 询问玩家是否要 自摸
     * */
    open suspend fun askToTsumo(): Boolean = true

    /**
     * 询问玩家是否要 抜北 (三麻, 手上有北的时候)
     * */
    open suspend fun askToNuki(): Boolean = true

    /**
     *  询问玩家 [tile] 是否要 荣和
     * */
    open suspend fun askToRon(tile: MahjongTile, target: ClaimTarget): Boolean = true

    suspend fun askToRon(mjTileEntity: MahjongTileEntity, target: ClaimTarget): Boolean =
        askToRon(tile = mjTileEntity.mahjongTile, target = target)

    /**
     * 询问玩家是否要九种九牌和局
     * */
    open suspend fun askToKyuushuKyuuhai(): Boolean = true

    /**
     * 拿牌的功能
     * */
    fun drawTile(tile: MahjongTileEntity) {
        hands += tile
        tile.ownerUUID = uuid
        tile.facing = TileFacing.HORIZONTAL
        tile.inGameTilePosition = TilePosition.HAND
        //目前拿牌没有音效
    }

    /**
     * 立直的时候执行的动作, 在立直成立之后才执行,
     * [sticks] 会增加一根立直棒
     * */
    fun riichi(riichiSengenTile: MahjongTileEntity, isFirstRound: Boolean) {
        this.riichiSengenTile = riichiSengenTile //设定立直宣言牌
        if (isFirstRound) doubleRiichi = true else riichi = true //设定立直状态
    }

    /**
     * 丢牌的功能, 仅需要麻将牌 [MahjongTile] 的 code 编号即可, 会回传丢出去的牌
     *
     * @return 特殊情况会打出 null 的牌, ex: 在打牌的瞬间把桌子拆了
     * */
    fun discardTile(tile: MahjongTile): MahjongTileEntity? =
        hands.findLast { it.mahjongTile == tile }?.also {
            hands -= it
            it.facing = TileFacing.UP
            it.inGameTilePosition = TilePosition.OTHER
            discardedTiles += it
            discardedTilesForDisplay += it
            playSoundAtHandsMiddle(soundEvent = SoundEvents.BLOCK_BAMBOO_PLACE) //播放把牌打出去的声音
        }

    /**
     * 将玩家的手牌翻出来,
     * 用在游戏即将结束的时候
     * */
    fun openHands() {
        hands.forEach {
            it.facing = TileFacing.UP
            it.inGameTilePosition = TilePosition.OTHER
        }
        playSoundAtHandsMiddle(soundEvent = SoundEvents.ITEM_ARMOR_EQUIP_GENERIC.value())
    }

    /**
     * 将玩家的手牌盖起来,
     * 用在游戏即将结束的时候
     * */
    fun closeHands() {
        hands.forEach {
            it.facing = TileFacing.DOWN
            it.inGameTilePosition = TilePosition.OTHER
        }
        playSoundAtHandsMiddle(soundEvent = SoundEvents.ITEM_ARMOR_EQUIP_GENERIC.value())
    }

    /**
     * 在手牌中间的牌的位置上播放声音
     * */
    private fun playSoundAtHandsMiddle(
        soundEvent: SoundEvent,
        category: SoundCategory = SoundCategory.VOICE,
        volume: Float = 1f,
        pitch: Float = 1f,
    ) {
        if (hands.size > 0) {
            val handsMiddleIndex = hands.size / 2
            hands[handsMiddleIndex].let {
                it.world.playSound(
                    null,
                    it.x,
                    it.y,
                    it.z,
                    soundEvent,
                    category,
                    volume,
                    pitch
                )
            }
        }
    }
}
