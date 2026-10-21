package github.freshchromatic.catbud_addons.public_warps;

import java.util.*;

public final class WarpData {

    public String       serverId, uuid, name, snapshotHash, displayName, description, status = "candidate";
    public List<String> tags      = new ArrayList<>();
    public long         verifiedAt, reportedAt;
    public int          revision;
    public String       passState = "unknown", availability = "unknown";
    public long         checkedAt, nextCheck, lastSuccess, lastFailure;
    public boolean      locallyDiscovered, confirmedTeleport;
    public Boolean      officialAvailable;

    public boolean canTeleport() {
        if (officialAvailable != null) {
            return officialAvailable && !"unavailable".equals(availability);
        }
        return !"inactive".equals(passState) && !"unavailable".equals(availability) && !"invalid".equals(status);
    }

    public String warningKey() {
        if (officialAvailable != null) {
            return !officialAvailable || "unavailable".equals(availability) ? "warps.status.unavailable" : "";
        }
        if ("inactive".equals(passState)) {
            return "warps.status.no_pass";
        }
        if ("unavailable".equals(availability) || "invalid".equals(status)) {
            return "active".equals(passState) ? "warps.status.no_warp" : "warps.status.unavailable";
        }
        return "";
    }

    public boolean listed() {
        return officialAvailable != null || confirmedTeleport || lastSuccess > 0 || !"unavailable".equals(availability);
    }

    public void confirmTeleport() {
        confirmedTeleport = true;
        availability = "available";
    }

    public void failedTeleport(long now) {
        availability = "unavailable";
        lastFailure = now;
    }

    public void successfulArrival(long now) {
        confirmedTeleport = true;
        availability = "available";
        lastSuccess = now;
        if ("invalid".equals(status)) {
            status = "candidate";
        }
    }

    public String label() {
        return displayName == null || displayName.isBlank() ? name : displayName;
    }

    public boolean hasCustomName() {
        return displayName != null && !displayName.isBlank() && !displayName.equals(name);
    }

    public String searchText() {
        return name + " " + label() + " " + Objects.toString(description, "") + " " + String.join(" ", tags);
    }

    public String key() {
        return uuid == null ? name.toLowerCase(Locale.ROOT) : uuid;
    }

    public static final class Visit {

        public long count, last;
    }
}
