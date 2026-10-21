package doublemoon.mahjonggame.registry

import doublemoon.mahjonggame.MOD_ID
import doublemoon.mahjonggame.id
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.SoundEvent
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

object SoundRegistry {

    private val SOUND_EVENTS: DeferredRegister<SoundEvent> =
        DeferredRegister.create(RegistryKeys.SOUND_EVENT, MOD_ID)

    //TODO 缺少了一些音效，待日后补上
    val chii = SoundEvent.of(id("chii"))
    val pon = SoundEvent.of(id("pon"))
    val kan = SoundEvent.of(id("kan"))
    val riichi = SoundEvent.of(id("riichi"))
    val ron = SoundEvent.of(id("ron"))
    val tsumo = SoundEvent.of(id("tsumo"))

    fun register(bus: IEventBus) {
        SOUND_EVENTS.register("chii", Supplier { chii })
        SOUND_EVENTS.register("pon", Supplier { pon })
        SOUND_EVENTS.register("kan", Supplier { kan })
        SOUND_EVENTS.register("riichi", Supplier { riichi })
        SOUND_EVENTS.register("ron", Supplier { ron })
        SOUND_EVENTS.register("tsumo", Supplier { tsumo })
        SOUND_EVENTS.register(bus)
    }
}
