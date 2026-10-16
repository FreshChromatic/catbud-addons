package github.freshchromatic.catbud_addons.public_warps;

public final class OwnerEditModel {

    public enum State {
        IDLE, VERIFYING, SAVING, ERROR, CONFLICT, SAVED
    }

    public String            displayName, description, messageKey = "warps.owner.ready";
    public int               revision;
    public State             state = State.IDLE;
    public WarpData          latest;
    private volatile boolean closed;

    public OwnerEditModel(WarpData warp) {
        displayName = warp.displayName == null ? "" : warp.displayName;
        description = warp.description == null ? "" : warp.description;
        revision = warp.revision;
    }

    public boolean busy() {
        return state == State.VERIFYING || state == State.SAVING;
    }

    public boolean closed() {
        return closed;
    }

    public void close() {
        closed = true;
    }
}
