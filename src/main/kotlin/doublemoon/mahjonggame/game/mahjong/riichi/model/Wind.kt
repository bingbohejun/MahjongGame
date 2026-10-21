package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.util.TextFormatting
import net.minecraft.text.Text
import org.mahjong4j.tile.Tile

enum class Wind(
    val tile: Tile
) : TextFormatting {
    EAST(Tile.TON),
    SOUTH(Tile.NAN),
    WEST(Tile.SHA),
    NORTH(Tile.PEI);

    override fun toText(): Text = Text.translatable("$MOD_ID.game.wind.${name.lowercase()}")
}