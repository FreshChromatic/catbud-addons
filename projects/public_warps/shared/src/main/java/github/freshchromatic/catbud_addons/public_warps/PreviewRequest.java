package github.freshchromatic.catbud_addons.public_warps;

import java.util.Objects;

/** Immutable source identity for an asynchronous preview read. */
public record PreviewRequest(String local, String hash) {

    public boolean matches(String currentLocal, String currentHash) {
        return local != null ? Objects.equals(local, currentLocal)
            : currentLocal == null && Objects.equals(hash, currentHash);
    }
}
