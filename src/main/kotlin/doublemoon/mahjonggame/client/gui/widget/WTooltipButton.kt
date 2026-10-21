package doublemoon.mahjonggame.client.gui.widget

import io.github.cottonmc.cotton.gui.widget.TooltipBuilder
import io.github.cottonmc.cotton.gui.widget.WButton
import io.github.cottonmc.cotton.gui.widget.icon.Icon
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.text.Text


class WTooltipButton(
    icon: Icon? = null,
    label: Text? = null,
    var tooltip: Array<Text>
) : WButton(icon, label) {
    @OnlyIn(Dist.CLIENT)
    override fun addTooltip(tooltip: TooltipBuilder) {
        tooltip.add(*this.tooltip)
    }
}