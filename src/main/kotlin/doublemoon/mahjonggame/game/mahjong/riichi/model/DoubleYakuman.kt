package doublemoon.mahjonggame.game.mahjong.riichi.model

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.util.TextFormatting
import net.minecraft.text.Text


/**
 * 双倍役满 (大四喜, 四暗刻单骑, 纯正九莲宝灯, 国士无双十三面)
 * */
enum class DoubleYakuman : TextFormatting {
    DAISUSHI,                   //大四喜
    SUANKO_TANKI,               //四暗刻单骑
    JUNSEI_CHURENPOHTO,         //纯正九莲宝灯
    KOKUSHIMUSO_JUSANMENMACHI //国士无双十三面
    ;

    override fun toText(): Text = Text.translatable("$MOD_ID.game.yaku.${name.lowercase()}")
}