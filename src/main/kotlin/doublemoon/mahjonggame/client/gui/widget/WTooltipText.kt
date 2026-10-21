package doublemoon.mahjonggame.client.gui.widget

import io.github.cottonmc.cotton.gui.widget.TooltipBuilder
import io.github.cottonmc.cotton.gui.widget.WText
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.text.Text


class WTooltipText(
    text: Text,
    var tooltip: Array<Text>
) : WText(text) {
    @OnlyIn(Dist.CLIENT)
    override fun addTooltip(tooltip: TooltipBuilder) {
        tooltip.add(*this.tooltip)
    }
}