package github.freshchromatic.catbud_addons.public_warps;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class OwnerEditSession {

    public record Context(BooleanSupplier current, Consumer<WarpData> saved, String playerName) {}

    private final Executor             io, ui;
    private final OwnerWarpApiClient   api;
    private final OfficialAccountProof proof;
    private final BooleanSupplier      current;
    private final Consumer<WarpData>   saved;
    private final String               playerName;
    private OwnerWarpApiClient.Session session;

    public OwnerEditSession(Executor io, Executor ui, OwnerWarpApiClient api, OfficialAccountProof proof, Context context) {
        this.io = io;
        this.ui = ui;
        this.api = api;
        this.proof = proof;
        this.current = context.current();
        this.saved = context.saved();
        this.playerName = context.playerName();
    }

    public void save(OwnerEditModel model) {
        if (model.busy() || model.closed() || !current.getAsBoolean()) {
            return;
        }
        String name = model.displayName, description = model.description;
        int revision = model.revision;
        model.state = OwnerEditModel.State.VERIFYING;
        model.messageKey = "warps.owner.verifying";
        io.execute(() -> perform(model, name, description, revision));
    }

    private void perform(OwnerEditModel model, String name, String description, int revision) {
        try {
            if (session == null || session.expiresAt() <= System.currentTimeMillis() + 10000) {
                var challenge = api.challenge(playerName);
                if (model.closed() || !current.getAsBoolean()) {
                    return;
                }
                var signedProof = proof.sign(challenge.payload());
                if (model.closed() || !current.getAsBoolean()) {
                    return;
                }
                session = api.verify(challenge, signedProof);
            }
            if (model.closed() || !current.getAsBoolean()) {
                return;
            }
            ui.execute(() -> {
                if (!current.getAsBoolean() || model.closed()) {
                    return;
                }
                model.state = OwnerEditModel.State.SAVING;
                model.messageKey = "warps.owner.saving";
            });
            var warp = api.save(session, name, description, revision);
            ui.execute(() -> {
                if (!current.getAsBoolean() || model.closed()) {
                    return;
                }
                saved.accept(warp);
                model.state = OwnerEditModel.State.SAVED;
            });
        } catch (Exception error) {
            var failure = error instanceof OwnerEditException owned ? owned : new OwnerEditException(503);
            WarpData latest = null;
            if (failure.status() == 409 && session != null && !model.closed()) {
                try {
                    latest = api.current(session);
                } catch (Exception ignored) {
                }
            }
            if (failure.status() == 401 || failure.status() == 403) {
                session = null;
            }
            var currentWarp = latest;
            ui.execute(() -> {
                if (!current.getAsBoolean() || model.closed()) {
                    return;
                }
                model.latest = currentWarp;
                model.state = failure.status() == 409 ? OwnerEditModel.State.CONFLICT : OwnerEditModel.State.ERROR;
                model.messageKey = failure.messageKey();
            });
        }
    }
}
