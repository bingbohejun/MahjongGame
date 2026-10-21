package doublemoon.mahjonggame.client

import doublemoon.mahjonggame.client.gui.screen.ConfigScreen
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.fml.ModContainer
import net.neoforged.neoforge.client.gui.IConfigScreenFactory

/**
 * 在 NeoForge 的模组列表界面中挂接本模组的配置屏幕,
 * 取代 Fabric 上 Mod Menu 的集成方式
 */
@OnlyIn(Dist.CLIENT)
fun registerConfigScreen(modContainer: ModContainer) {
    //registerExtensionPoint 是泛型方法, Kotlin 无法从尾随 lambda 推断型别, 必须显式 SAM 转换
    modContainer.registerExtensionPoint(
        IConfigScreenFactory::class.java,
        IConfigScreenFactory { _, parent ->
            ConfigScreen.build(parent)
        }
    )
}
