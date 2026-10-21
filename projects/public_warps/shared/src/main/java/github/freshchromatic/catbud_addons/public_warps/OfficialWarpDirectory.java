package github.freshchromatic.catbud_addons.public_warps;

import java.util.Map;

public final class OfficialWarpDirectory {

    public Map<String, String> players;
    public long                refreshed;

    public OfficialWarpDirectory(Map<String, String> players, long refreshed) {
        this.players = Map.copyOf(players);
        this.refreshed = refreshed;
    }

    public void applyIdentity(WarpData warp) {
        String name = warp.uuid == null ? null : players.get(warp.uuid);
        warp.officialAvailable = name != null;
        if (name != null) {
            warp.name = name;
        }
    }
}
