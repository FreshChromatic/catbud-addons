package github.freshchromatic.catbud_addons.magic_tower;

import java.util.OptionalInt;

final class TowerFloorState {

    private OptionalInt                   floor          = OptionalInt.empty();
    private TowerSession.EvacuationStatus evacuation     = TowerSession.EvacuationStatus.UNKNOWN;
    private long                          floorAt        = -1;
    private long                          announcementAt = -1;
    private long                          waitingAt      = -1;
    private boolean                       transitioning;
    private boolean                       waitingConsumed;

    OptionalInt floor() {
        return floor;
    }

    TowerSession.EvacuationStatus evacuation() {
        return evacuation;
    }

    void reset() {
        floor = OptionalInt.empty();
        evacuation = TowerSession.EvacuationStatus.UNKNOWN;
        floorAt = -1;
        announcementAt = -1;
        waitingAt = -1;
        transitioning = false;
        waitingConsumed = false;
    }

    boolean waiting(long now) {
        if (waitingAt >= 0 && now - waitingAt < 1000) {
            return false;
        }
        waitingAt = now;
        if (transitioning) {
            return false;
        }
        // The server may deliver the wait bar after this transition's title/subtitle pair.
        if (!waitingConsumed && floorAt >= 0 && now - floorAt <= 1000) {
            waitingConsumed = true;
            return false;
        }
        floor = OptionalInt.empty();
        evacuation = TowerSession.EvacuationStatus.UNKNOWN;
        transitioning = true;
        waitingConsumed = true;
        announcementAt = -1;
        return true;
    }

    void announce(TowerAnnouncementParser.Announcement announcement, long now) {
        if (announcement.floor().isPresent() && !announcement.floor().equals(floor)) {
            boolean freshPending = (transitioning || floor.isEmpty()) && announcementAt >= 0 && now
                - announcementAt <= 5000;
            if (!freshPending) {
                evacuation = TowerSession.EvacuationStatus.UNKNOWN;
                announcementAt = -1;
            }
            floor = announcement.floor();
            floorAt = now;
            waitingConsumed = transitioning;
            transitioning = false;
        }
        if (announcement.evacuation() != TowerSession.EvacuationStatus.UNKNOWN) {
            evacuation = announcement.evacuation();
            announcementAt = now;
        }
    }

    void entered(long now) {
        if (floorAt >= 0 && now - floorAt > 5000) {
            floor = OptionalInt.empty();
        }
        if (announcementAt < 0 || now - announcementAt > 5000) {
            evacuation = TowerSession.EvacuationStatus.UNKNOWN;
        }
    }
}
