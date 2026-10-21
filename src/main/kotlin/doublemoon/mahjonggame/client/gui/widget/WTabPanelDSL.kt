package doublemoon.mahjonggame.client.gui.widget

import io.github.cottonmc.cotton.gui.client.LightweightGuiDescription
import io.github.cottonmc.cotton.gui.widget.WTabPanel
import io.github.cottonmc.cotton.gui.widget.WWidget
import io.github.cottonmc.cotton.gui.widget.icon.Icon
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.text.Text


/**
 * 将 [WTabPanel] 作为根使用
 * */
@OnlyIn(Dist.CLIENT)
fun LightweightGuiDescription.rootTabPanel(
    width: Int,
    height: Int,
    init: WTabPanel.() -> Unit
): WTabPanel {
    val panel = WTabPanel().also {
        it.setSize(width, height)
        it.init()
    }
    rootPanel = panel
    rootPanel.validate(this)
    return panel
}

/**
 * [WTabPanel.Tab.Builder],
 * [title] 和 [icon] 不能同时为 null
 * */
@OnlyIn(Dist.CLIENT)
fun WTabPanel.tab(
    widget: WWidget,
    title: Text? = null,
    icon: Icon? = null,
    tooltip: Collection<Text>? = null
): WTabPanel.Tab {
    val tab = WTabPanel.Tab.Builder(widget).also { builder ->
        title?.also { builder.title(it) }
        icon?.also { builder.icon(it) }
        tooltip?.also { builder.tooltip(it) }
    }.build()
    this.add(tab)
    return tab
}