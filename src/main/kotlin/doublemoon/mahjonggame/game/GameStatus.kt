package doublemoon.mahjonggame.game

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.util.TextFormatting
import net.minecraft.text.Text


/**
 * 游戏的状态
 * */
enum class GameStatus : TextFormatting {
    WAITING,
    PLAYING;

    override fun toText(): Text = Text.translatable("$MOD_ID.game.status.${name.lowercase()}")
}