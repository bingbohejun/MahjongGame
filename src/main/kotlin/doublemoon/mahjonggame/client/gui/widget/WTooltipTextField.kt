package doublemoon.mahjonggame.client.gui.widget

import io.github.cottonmc.cotton.gui.widget.TooltipBuilder
import io.github.cottonmc.cotton.gui.widget.WTextField
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.text.Text


class WTooltipTextField(
    suggestion: Text?,
    var tooltip: Array<Text>
) : WTextField(suggestion) {
    @OnlyIn(Dist.CLIENT)
    override fun addTooltip(tooltip: TooltipBuilder) {
        tooltip.add(*this.tooltip)
    }
}