package github.freshchromatic.catbud_addons.island_assistive;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class MobAlertCommands {

    private MobAlertCommands() {
    }

    static void register(MobAlertSession session) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var alerts = literal("mob_alert");
            for (String action : new String[] { "ack", "ack_through" }) {
                var acknowledgement = argument("alert", LongArgumentType.longArg(1));
                acknowledgement.executes(new MobAlertCommand(session, action.equals("ack_through")));
                var sessionArgument = argument("session", StringArgumentType.word()).then(acknowledgement);
                alerts.then(literal(action).then(sessionArgument));
            }
            dispatcher.register(literal(MobAlertChat.COMMAND).then(alerts));
        });
    }

}
