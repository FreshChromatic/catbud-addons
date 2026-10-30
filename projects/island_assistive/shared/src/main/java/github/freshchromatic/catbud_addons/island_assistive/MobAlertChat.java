package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.catbud_core.ClientPlatform;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.UUID;

public final class MobAlertChat implements MobAlertOutput {

    public static final String COMMAND = "catbud_island_assistive";

    private static final Style BUTTON_STYLE = Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true);

    private final Minecraft client;

    public MobAlertChat(Minecraft client) {
        this.client = client;
    }

    @Override
    public void initial(List<MobSnapshot> mobs) {
        var message = Component.translatable("island.chat.initial", mobs.size()).withStyle(ChatFormatting.YELLOW);
        for (MobSnapshot mob : mobs) {
            message.append(Component.literal("\n")).append(details(mob));
        }
        ClientPlatform.chat(client, message);
    }

    @Override
    public void discovered(UUID session, MobAlertSession.Alert alert) {
        String arguments = " " + session + " " + alert.id();
        var message = Component.translatable("island.chat.found").withStyle(ChatFormatting.GOLD);
        message.append(details(alert.mob()));
        message.append(Component.literal("\n"));
        message.append(button("island.chat.ack", "/" + COMMAND + " mob_alert ack" + arguments));
        message.append(Component.literal(" "));
        message.append(button("island.chat.ack_all", "/" + COMMAND + " mob_alert ack_through" + arguments));
        ClientPlatform.chat(client, message);
    }

    private static MutableComponent details(MobSnapshot mob) {
        return Component.translatable("island.chat.details", mob.displayName(), (int) Math.round(mob.distance()),
            (int) Math.floor(mob.x()), (int) Math.floor(mob.y()), (int) Math.floor(mob.z())).withStyle(
                ChatFormatting.WHITE);
    }

    private static MutableComponent button(String key, String command) {
        return Component.translatable(key).setStyle(BUTTON_STYLE.withClickEvent(new ClickEvent.RunCommand(command)));
    }
}
