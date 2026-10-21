package doublemoon.mahjonggame.blockentity

import doublemoon.mahjonggame.block.MahjongTable
import doublemoon.mahjonggame.block.enums.MahjongTablePart
import doublemoon.mahjonggame.client.gui.screen.MahjongTableGui
import doublemoon.mahjonggame.client.gui.screen.MahjongTableWaitingScreen
import doublemoon.mahjonggame.entity.MahjongTileEntity
import doublemoon.mahjonggame.game.GameManager
import doublemoon.mahjonggame.game.mahjong.riichi.MahjongGame
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongRound
import doublemoon.mahjonggame.game.mahjong.riichi.model.MahjongRule
import doublemoon.mahjonggame.network.mahjong_table.MahjongTablePayloadListener
import doublemoon.mahjonggame.registry.BlockEntityTypeRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.nbt.NbtCompound
import net.minecraft.network.listener.ClientPlayPacketListener
import net.minecraft.network.packet.Packet
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import org.mahjong4j.tile.Tile
import java.util.concurrent.ConcurrentHashMap

class MahjongTableBlockEntity(
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(BlockEntityTypeRegistry.mahjongTable, pos, state) {
    private var gameInitialized = false
    val players = arrayListOf("", "", "", "") //以玩家的 stringUUID 储存, 先以 4 个空字串储存, (空字串表示空位)
    val playerEntityNames = arrayListOf("", "", "", "") //以实体的 entityName 储存
    val bots = arrayListOf(false, false, false, false) //这个玩家是否是机器人
    val ready = arrayListOf(false, false, false, false) //这个玩家是否准备了
    var rule = MahjongRule() //这是为了能够储存在桌子上, 直到开启麻将桌时套用到新游戏上

    /** 这张桌子的座位数 (三人麻将 = 3, 其余 = 4) */
    val capacity: Int
        get() = if (rule.gameStyle == doublemoon.mahjonggame.game.mahjong.riichi.model.GameStyle.RIICHI_3P) 3 else 4
    var playing = false
    var round = MahjongRound() //当前的回合
    var seat = arrayListOf("", "", "", "") //座位上的玩家的 stringUUID, 按照游戏开始的座位顺序编排
    var dealer = "" //庄家的 stringUUID
    var points = arrayListOf(0, 0, 0, 0) //玩家的点数, 排列顺序比照 seat

    /**
     * 牌山剩余张数 (不含王牌区), 由伺服端同步, 供桌面中央面板显示
     * */
    var tilesLeft = 0

    /**
     * “结束游戏”投票: 已同意人数 / 需要人数 (机器人自动同意, 不计入需要人数)
     * */
    var endVoteAgreed = 0
    var endVoteNeeded = 0

    /**
     * 剩余的牌, index 为 [Tile.code], value 为剩下的张数,
     * 这里存的资料是只有面向客户端的, 每个人看到剩下的张数都不一样, 要经过计算才能确定
     * */
    val remainingTiles = ConcurrentHashMap<Int, Int>().apply {
        Tile.entries.forEach { this[it.code] = 0 }
    }

    /**
     * 从桌子中心取得牌桌大小内所有 牌的实体,
     * 经过计算后修正 [remainingTiles]
     * */
    @OnlyIn(Dist.CLIENT)
    fun calculateRemainingTiles(code: Int) {
        val tableCenter = with(this.pos) { Vec3d(x + 0.5, y + 1.0, z + 0.5) }
        val tiles = world?.getEntitiesByClass(
            MahjongTileEntity::class.java,
            Box.of(tableCenter, 3.0, 2.0, 3.0)
        ) { it.isSpawnedByGame && it.gameBlockPos == this.pos && it.mahjongTile.code == code } ?: return
        remainingTiles[code] = 4 - tiles.size
    }

    override fun markDirty() {
        super.markDirty()
        //对伺服端要同步的方块实体 markDirty() 就好
        if (this.hasWorld() && !this.world!!.isClient) {
            (world as ServerWorld).chunkManager.markForUpdate(getPos())
        }
    }

    override fun readNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup) {
        with(nbt) {
            repeat(4) {
                players[it] = getString("PlayerStringUUID$it")
                playerEntityNames[it] = getString("PlayerEntityName$it")
                bots[it] = getBoolean("Bot$it")
                ready[it] = getBoolean("PlayerReady$it")
                seat[it] = getString("Seat$it")
                points[it] = getInt("Point$it")
            }
            dealer = getString("Dealer")
            rule = MahjongRule.fromJsonString(getString("Rule"))
            playing = getBoolean("Playing")
            round = Json.decodeFromString(getString("Round"))
            tilesLeft = getInt("TilesLeft")
            endVoteAgreed = getInt("EndVoteAgreed")
            endVoteNeeded = getInt("EndVoteNeeded")
        }
        world?.isClient?.let { isClient ->
            if (isClient) { //当有同步 tag 的情况出现
                val screen = MinecraftClient.getInstance().currentScreen
                if (screen is MahjongTableWaitingScreen && (screen.description as MahjongTableGui).mahjongTable == this) {
                    if (!playing) {  //游戏还没开始, 刷新开著这个麻将桌视窗的玩家的视窗
                        screen.refresh()
                    } else {  //游戏开始就关闭游戏视窗
                        screen.close()
                    }
                }
            }
        }
    }

    override fun writeNbt(nbt: NbtCompound, registryLookup: RegistryWrapper.WrapperLookup) {
        if (cachedState[MahjongTable.PART] == MahjongTablePart.BOTTOM_CENTER) {
            with(nbt) {
                repeat(4) {
                    putString("PlayerStringUUID$it", players[it])
                    putString("PlayerEntityName$it", playerEntityNames[it])
                    putBoolean("Bot$it", bots[it])
                    putBoolean("PlayerReady$it", ready[it])
                    putString("Seat$it", seat[it])
                    putInt("Point$it", points[it])
                }
                putString("Dealer", dealer)
                putString("Rule", rule.toJsonString())
                putBoolean("Playing", playing)
                putString("Round", Json.encodeToString(round))
                putInt("TilesLeft", tilesLeft)
                putInt("EndVoteAgreed", endVoteAgreed)
                putInt("EndVoteNeeded", endVoteNeeded)
            }
        }
    }

    override fun toInitialChunkDataNbt(registryLookup: RegistryWrapper.WrapperLookup): NbtCompound =
        NbtCompound().also { writeNbt(it, registryLookup) }

    override fun toUpdatePacket(): Packet<ClientPlayPacketListener> = BlockEntityUpdateS2CPacket.create(this)

    companion object {
        fun tick(world: World, pos: BlockPos, blockEntity: MahjongTableBlockEntity) {
            if (!world.isClient && !blockEntity.gameInitialized) {
                MahjongTablePayloadListener.syncBlockEntityDataWithGame(
                    blockEntity = blockEntity,
                    game = GameManager.getGameOrDefault(
                        world = world as ServerWorld,
                        pos = pos,
                        default = MahjongGame(world, pos, blockEntity.rule)
                    )
                )
                blockEntity.gameInitialized = true
            }
            // 牌山剩余张数每次摸牌都会变, 这里侦测到变化就同步给客户端 (让桌面中央的 "余 N" 保持即时)
            if (!world.isClient && blockEntity.playing) {
                val game = GameManager.getGame<MahjongGame>(world as ServerWorld, pos)
                if (game != null && blockEntity.tilesLeft != game.wallTilesLeft) {
                    MahjongTablePayloadListener.syncBlockEntityDataWithGame(blockEntity, game)
                }
            }
        }
    }
}