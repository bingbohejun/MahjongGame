package doublemoon.mahjonggame.entity

import doublemoon.mahjonggame.blockentity.MahjongTableBlockEntity
import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongBoard
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongGameBehavior
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongTile
import doublemoon.mahjonggame.game.mahjong.riichi.player.MahjongPlayer
import doublemoon.mahjonggame.network.mahjong_tile_code.MahjongTileCodePayload
import doublemoon.mahjonggame.network.mahjong_tile_code.MahjongTileCodePayloadListener
import doublemoon.mahjonggame.network.sendPayloadToServer
import doublemoon.mahjonggame.registry.EntityTypeRegistry
import doublemoon.mahjonggame.registry.ItemRegistry
import doublemoon.mahjonggame.scheduler.client.OptionalBehaviorHandler
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.NbtComponent
import net.minecraft.entity.EntityDimensions
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EntityType
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import org.mahjong4j.tile.Tile


//方便用
fun List<MahjongTileEntity>.toMahjongTileList(): List<MahjongTile> = this.map { it.mahjongTile }

/**
 * 麻将牌实体
 * */
class MahjongTileEntity(
    type: EntityType<*> = EntityTypeRegistry.mahjongTile,
    world: World,
) : GameEntity(type, world) {

    constructor(world: World, code: Int) : this(world = world) {
        this.code = code
    }

    /**
     * 专门给 [MahjongBoard] 产生实体用
     * */
    constructor(
        world: World,
        code: Int,
        gameBlockPos: BlockPos,
        isSpawnedByGame: Boolean,
        inGameTilePosition: TilePosition,
        gamePlayers: List<String>,
        canSpectate: Boolean,
        facing: TileFacing,
    ) : this(world = world) {
        this.gameBlockPos = gameBlockPos
        this.isSpawnedByGame = isSpawnedByGame
        this.code = code
        this.inGameTilePosition = inGameTilePosition
        this.gamePlayers = gamePlayers
        this.canSpectate = canSpectate
        this.facing = facing
    }

    /**
     * 暂时存取 由游戏产生且在伺服端中 的麻将牌的 code,
     * 不能用 [dataTracker] 直接更新, 这样只能一次对所有玩家, 没办法针对个体
     * */
    private var spawnedByGameServerSideCode: Int = MahjongTile.UNKNOWN.code

    /**
     * 暂时存取 由游戏产生且在客户端中 的麻将牌的 code,不是每个玩家都一样, 透过数据包对 [code] 操作时进行同步
     * */
    private var spawnedByGameClientSideCode: Int = MahjongTile.UNKNOWN.code

    /**
     * 麻将牌编号,
     * 控制麻将牌外观
     * */
    var code: Int
        set(value) {
            if (isSpawnedByGame) {
                if (!world.isClient) spawnedByGameServerSideCode = value
                else {
                    if (value != MahjongTile.UNKNOWN.code) mahjongTable?.calculateRemainingTiles(value)
                    spawnedByGameClientSideCode = value
                }
            } else {
                dataTracker.set(CODE, value)
            }
        }
        get() = if (isSpawnedByGame) {
            if (!world.isClient) spawnedByGameServerSideCode
            else spawnedByGameClientSideCode
        } else {
            dataTracker[CODE]
        }

    /**
     * 这张牌主人的 UUID "字串"
     * 如果这张牌是游戏产生的, 只有主人跟其他非游戏中玩家才看得见,
     * 字串为空白表示没有人能看见(还在牌山中)
     * */
    var ownerUUID: String
        set(value) = dataTracker.set(OWNER_UUID, value)
        get() = dataTracker[OWNER_UUID]

    /**
     * 麻将游戏中的玩家的 UUID 字串列表,
     * 利用 [Json.encodeToString] 编译成 [Json] 格式的字串, 再进行储存
     * 再利用 [Json.decodeFromString] 解译成 UUID 字串
     * */
    var gamePlayers: List<String>
        set(value) = dataTracker.set(GAME_PLAYERS, Json.encodeToString(value))
        get() = Json.decodeFromString(dataTracker[GAME_PLAYERS])

    /**
     * 面朝的方向,水平或垂直
     * 改变的时候会影响 hitbox,
     * 也会影响渲染的时候, 牌的渲染位置
     * */
    var facing: TileFacing
        set(value) = dataTracker.set(FACING, Json.encodeToString(value))
        get() = Json.decodeFromString(dataTracker[FACING])

    /**
     * 这张牌所在的麻将游戏是否允许旁观
     * */
    var canSpectate: Boolean
        set(value) = dataTracker.set(GAME_CAN_SPECTATE, value)
        get() = dataTracker[GAME_CAN_SPECTATE]

    /**
     * 这张牌在游戏中的位置
     * */
    var inGameTilePosition: TilePosition
        set(value) = dataTracker.set(GAME_TILE_POSITION, Json.encodeToString(value))
        get() = Json.decodeFromString(dataTracker[GAME_TILE_POSITION])

    /**
     * 取得对应 [code] 的 [MahjongTile]
     * */
    val mahjongTile: MahjongTile
        get() = MahjongTile.entries[code]

    /**
     * 取得对应 [code] 的 [Tile]
     * */
    val mahjong4jTile: Tile
        get() = mahjongTile.mahjong4jTile

    /**
     * 这张牌如果是由游戏产生的,
     * 这个会指到游戏的 [MahjongTableBlockEntity] 上
     * */
    val mahjongTable: MahjongTableBlockEntity?
        get() = if (isSpawnedByGame) world.getBlockEntity(gameBlockPos) as MahjongTableBlockEntity? else null

    /**
     * 伺服端的游戏专用,
     * 用来取得 [player] 应该看到这张麻将牌的 code, 通常在 [MahjongTileCodePayloadListener] 使用
     * */
    fun getCodeForPlayer(player: ServerPlayerEntity): Int {
        if (world.isClient) throw IllegalStateException("Cannot get code from client side")
        return if (!isSpawnedByGame) {  // 不是游戏生成的
            throw IllegalStateException("This MahjongTileEntity must be spawned by game")
        } else when (inGameTilePosition) { // 是游戏生成的
            TilePosition.WALL -> MahjongTile.UNKNOWN.code // 是牌山的牌
            TilePosition.HAND -> // 是手牌
                when {
                    player.uuidAsString == ownerUUID -> code  // 这张牌是这玩家的
                    player.uuidAsString in gamePlayers -> MahjongTile.UNKNOWN.code  //如果是这游戏中的其他玩家
                    canSpectate -> code   //开放观战
                    else -> MahjongTile.UNKNOWN.code     // 不开放观战
                }
            else -> code  // 是其他剩下的牌 (ex: 副露, 或者已经打出去的牌)
        }
    }

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)

        // 确保在 FACING 更新时, boundingBox 马上更新
        if (FACING == data) boundingBox = calculateBoundingBox()

        // 以下在所有 tracked 的资料改变的时候都会执行, 以便尽可能拿到最新与正确的 tile code
        if (!world.isClient || !isSpawnedByGame) return // 只限定在客户端且必须是游戏产生的牌
        sendPayloadToServer(
            payload = MahjongTileCodePayload(id = id)
        )
    }

    //实体碰撞
    override fun isCollidable(): Boolean = true

    override fun getDimensions(pose: EntityPose): EntityDimensions =
        if (facing == TileFacing.HORIZONTAL) {
            super.getDimensions(pose)
        } else {
            EntityDimensions.fixed(MAHJONG_TILE_HEIGHT, MAHJONG_TILE_DEPTH)
        }

    //玩家与实体互动 (右键)
    override fun interact(player: PlayerEntity, hand: Hand): ActionResult {
        if (!world.isClient) {
            player as ServerPlayerEntity
            if (!isSpawnedByGame) {  // 不是由游戏产生, 是手动放置的一般实体
                if (player.isSneaking) {  // 有蹲下, 把牌捡起来
                    val itemStack = ItemRegistry.mahjongTile.defaultStack.also {
                        val nbt = NbtCompound()
                        nbt.putInt("code", code)
                        it.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt))
                    }
                    player.giveItemStack(itemStack)
                    playSound(SoundEvents.ENTITY_ITEM_PICKUP, 1f, 1f)
                    remove(RemovalReason.DISCARDED)
                } else {  // 没蹲下, 改变牌立著的方向
                    facing = facing.next
                }
            } else if (inGameTilePosition == TilePosition.HAND && ownerUUID == player.uuidAsString) {
                // 由游戏产生的, 自己手牌以外的牌不会执行任何动作
                val game = GameManager.getGame<MahjongGame>(player) ?: return ActionResult.FAIL
                val mjPlayer = game.getPlayer(player) as MahjongPlayer? ?: return ActionResult.FAIL
                if (MahjongGameBehavior.DISCARD in mjPlayer.waitingBehavior) { //客户端正在等待丢牌
                    if (this.mahjongTile !in mjPlayer.cannotDiscardTiles) { //这张牌是可以丢的
                        mjPlayer.behaviorResult = MahjongGameBehavior.DISCARD to "$code" //丢牌
                    }
                }
            }
        } else {
            if (OptionalBehaviorHandler.waiting) OptionalBehaviorHandler.setScreen()
        }
        return ActionResult.SUCCESS
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(CODE, MahjongTile.UNKNOWN.code)
        builder.add(OWNER_UUID, "")
        builder.add(GAME_PLAYERS, Json.encodeToString(mutableListOf<String>()))
        builder.add(FACING, Json.encodeToString(TileFacing.HORIZONTAL))
        builder.add(GAME_CAN_SPECTATE, true)
        builder.add(GAME_TILE_POSITION, Json.encodeToString(TilePosition.WALL))
    }

    override fun readCustomDataFromNbt(nbt: NbtCompound) {
        super.readCustomDataFromNbt(nbt)
        code = nbt.getInt("Code")
        ownerUUID = nbt.getString("OwnerUUID")
        gamePlayers = Json.decodeFromString(nbt.getString("GamePlayers"))
        facing = Json.decodeFromString(nbt.getString("Facing"))
        canSpectate = nbt.getBoolean("GameCanSpectate")
        inGameTilePosition = Json.decodeFromString(nbt.getString("GameTilePosition"))
    }

    override fun writeCustomDataToNbt(nbt: NbtCompound) {
        super.writeCustomDataToNbt(nbt)
        nbt.putInt("Code", code)
        nbt.putString("OwnerUUID", ownerUUID)
        nbt.putString("GamePlayers", Json.encodeToString(gamePlayers))
        nbt.putString("Facing", Json.encodeToString(facing))
        nbt.putBoolean("GameCanSpectate", canSpectate)
        nbt.putString("GameTilePosition", Json.encodeToString(inGameTilePosition))
    }

    companion object {
        //麻将牌的宽高深和比例
        const val MAHJONG_TILE_SCALE = 0.15f
        const val MAHJONG_TILE_WIDTH = 1f / 16 * 12 * MAHJONG_TILE_SCALE
        const val MAHJONG_TILE_HEIGHT = 1f / 16 * 16 * MAHJONG_TILE_SCALE
        const val MAHJONG_TILE_DEPTH = 1f / 16 * 8 * MAHJONG_TILE_SCALE
        const val MAHJONG_TILE_SMALL_PADDING = 0.0025

        private val CODE: TrackedData<Int> =
            DataTracker.registerData(MahjongTileEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        private val OWNER_UUID: TrackedData<String> =
            DataTracker.registerData(MahjongTileEntity::class.java, TrackedDataHandlerRegistry.STRING)

        private val GAME_PLAYERS: TrackedData<String> =
            DataTracker.registerData(MahjongTileEntity::class.java, TrackedDataHandlerRegistry.STRING)

        private val FACING: TrackedData<String> =
            DataTracker.registerData(MahjongTileEntity::class.java, TrackedDataHandlerRegistry.STRING)

        private val GAME_CAN_SPECTATE: TrackedData<Boolean> =
            DataTracker.registerData(MahjongTileEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        private val GAME_TILE_POSITION: TrackedData<String> =
            DataTracker.registerData(MahjongTileEntity::class.java, TrackedDataHandlerRegistry.STRING)
    }
}

/**
 * 麻将牌面向的方向, 会牵涉到 HitBox, 以及实体的渲染
 *
 * @param angleForDegreesQuaternionFromPositiveX 渲染时选转的角度
 * */
enum class TileFacing(
    val angleForDegreesQuaternionFromPositiveX: Float,
) {
    HORIZONTAL(0f),
    UP(90f),
    DOWN(-90f);

    val next: TileFacing
        get() = entries[(this.ordinal + 1) % entries.size]
}


/**
 * 麻将牌的位置
 *
 * 牌山, 手牌, 或者其他
 * */
enum class TilePosition {
    WALL, HAND, OTHER
}