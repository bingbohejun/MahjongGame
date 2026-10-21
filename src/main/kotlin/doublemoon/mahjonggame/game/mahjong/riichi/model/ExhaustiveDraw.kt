package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.util.TextFormatting
import net.minecraft.text.Text


/**
 * 流局,
 * 不包含 sanchahou (三家和)
 * */
enum class ExhaustiveDraw : TextFormatting {
    NORMAL,         //一般流局
    KYUUSHU_KYUUHAI,//九种九牌
    SUUFON_RENDA,   //四风连打
    SUUCHA_RIICHI,  //四家立直
    SUUKAIKAN,      //四开杠(四杠散了)
    ;

    val translateKey = "$MOD_ID.game.exhaustive_draw.${name.lowercase()}"
    override fun toText(): Text = Text.translatable(translateKey)
}