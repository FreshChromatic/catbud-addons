package github.freshchromatic.catbud_addons.public_warps;

public final class OwnerEditException extends Exception {

    private final int status;

    public OwnerEditException(int status) {
        super("Owner editing request failed: " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public String messageKey() {
        return switch (status) {
            case 400 -> "warps.owner.invalid_text";
            case 401, 403 -> "warps.owner.authentication_failed";
            case 404 -> "warps.owner.not_found";
            case 409 -> "warps.owner.conflict";
            case 422 -> "warps.owner.no_pass";
            case 429 -> "warps.owner.limited";
            default -> "warps.owner.network_failed";
        };
    }
}
