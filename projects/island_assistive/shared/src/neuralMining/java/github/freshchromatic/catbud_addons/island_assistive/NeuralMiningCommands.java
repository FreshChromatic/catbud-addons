package github.freshchromatic.catbud_addons.island_assistive;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.core.BlockPos;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;

final class NeuralMiningCommands {

    static void register(NeuralMiningClient client) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var probe = literal("probe").then(argument("x", IntegerArgumentType.integer()).then(argument("y",
                IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes(
                    command -> client.probe(new BlockPos(IntegerArgumentType.getInteger(command, "x"),
                        IntegerArgumentType.getInteger(command, "y"), IntegerArgumentType.getInteger(command,
                            "z")))))));
            var correction = literal("correct").executes(command -> client.correct(8)).then(argument("seconds",
                IntegerArgumentType.integer(2, 30)).executes(command -> client.correct(IntegerArgumentType.getInteger(
                    command, "seconds"))));
            dispatcher.register(literal("neuralmotion").then(literal("mean").executes(command -> client.enable(false)))
                .then(literal("sample").executes(command -> client.enable(true))).then(literal("off").executes(
                    command -> client.disable())).then(literal("status").executes(command -> client.status())).then(
                        probe).then(correction).then(literal("verify-auto").executes(command -> client
                            .verifyAutomaticControl())));
        });
    }
}
