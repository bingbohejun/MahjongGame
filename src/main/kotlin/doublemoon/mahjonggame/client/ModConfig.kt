package doublemoon.mahjonggame.client

import doublemoon.mahjonggame.MOD_ID
import me.shedaniel.autoconfig.ConfigData
import me.shedaniel.autoconfig.annotation.Config

private const val DEFAULT_COLOR_RED = 0x7f271c1d
private const val DEFAULT_COLOR_GREEN = 0x7f597d2a

/**
 * 这个模组的设定档
 *
 * @param displayTableLabels 是否要显示麻将桌方块关于麻将游戏的标签
 * */
@Config(name = MOD_ID)
data class ModConfig(
    var displayTableLabels: Boolean = true,
    val tileHints: TileHints = TileHints(),
    val quickActions: QuickActions = QuickActions()
) : ConfigData {

    /**
     * 牌的提示
     *
     * @param displayHud 显示 HUD
     * @param hudAttribute 这个 HUD 是与画面宽度同宽, 置中显示的, 所以 [HudAttribute.x] 是不能调整的
     * */
    data class TileHints(
        var displayHud: Boolean = true,
        val hudAttribute: HudAttribute = HudAttribute(0.0, 0.6, DEFAULT_COLOR_GREEN)
    ) : ConfigData

    /**
     * 快捷操作
     *
     * @param displayHudWhenPlaying 在游玩中的时候显示 HUD
     * @param hudAttribute 一些 HUD 的属性, 这里不能调整 [HudAttribute.scale]
     * @param autoArrange 自动理牌
     * @param autoCallWin 自动和牌 (每回合结束时自动重置)
     * @param noChiiPonKan 不吃碰杠 (每回合结束时自动重置)
     * @param autoDrawAndDiscard 自动摸切 (每回合结束时自动重置)
     * */
    data class QuickActions(
        var displayHudWhenPlaying: Boolean = true,
        val hudAttribute: HudAttribute = HudAttribute(0.0, 0.6, DEFAULT_COLOR_RED),
        var autoArrange: Boolean = true,
        var autoCallWin: Boolean = false,
        var noChiiPonKan: Boolean = false,
        var autoDrawAndDiscard: Boolean = false
    ) : ConfigData
}

/**
 * HUD 的一些属性
 *
 * @param x x 轴的百分比座标 (max: 1.0, min: 0.0)
 * @param y y 轴的百分比座标 (max: 1.0, min: 0.0)
 * @param backgroundColor 背景颜色
 * @param scale HUD 的大小缩放倍率, 不适用于文字
 * */
data class HudAttribute(
    var x: Double,
    var y: Double,
    var backgroundColor: Int,
    var scale: Double = 1.0
) : ConfigData