package github.freshchromatic.catbud_addons.public_warps;

import java.net.URI;
import java.util.Locale;

public enum WarpServerScope {

    CATBUD("catbud", "catbud.net", "貓芽"), CATREALM("catrealm", "catrealm.net", "貓域");

    public final String id, domain, label;

    WarpServerScope(String id, String domain, String label) {
        this.id = id;
        this.domain = domain;
        this.label = label;
    }

    public static WarpServerScope fromAddress(String address) {
        if (address == null) {
            return null;
        }
        String host = address.toLowerCase(Locale.ROOT).split(":", 2)[0];
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (var scope : values()) {
            if (host.equals(scope.domain) || host.endsWith("." + scope.domain)) {
                return scope;
            }
        }
        return null;
    }

    public URI playerEndpoint() {
        return URI.create("https://api." + domain + "/discord/player/");
    }

    public URI publicWarpsEndpoint() {
        return URI.create("https://api." + domain + "/publics");
    }
}
