package github.freshchromatic.catbud_addons.island_assistive;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.UUID;

final class MobAlertCommand implements Command<FabricClientCommandSource> {

    private final MobAlertSession session;
    private final boolean         through;

    MobAlertCommand(MobAlertSession session, boolean through) {
        this.session = session;
        this.through = through;
    }

    @Override
    public int run(CommandContext<FabricClientCommandSource> context) {
        UUID token;
        try {
            token = UUID.fromString(StringArgumentType.getString(context, "session"));
        } catch (IllegalArgumentException error) {
            return 0;
        }
        long id = LongArgumentType.getLong(context, "alert");
        return session.acknowledge(token, id, through) ? 1 : 0;
    }
}
