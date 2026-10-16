package github.freshchromatic.catbud_addons.public_warps;

import java.util.regex.Pattern;

/** One operation, cancelled on ambiguous subsequent commands and disconnected sessions. */
public final class TeleportAttempt {

    private static final Pattern COMMAND = Pattern.compile("^/?to\\s+by\\s+([A-Za-z0-9_]{1,16})\\s*$");
    public final String          target;
    public final long            started;
    private boolean              boss, action, cancelled, arrived, bossEnded;
    private long                 launchedAt, correctionAt;
    private boolean              retainedLaunch;

    public TeleportAttempt(String target, long now) {
        this.target = target;
        started = now;
    }

    public static String target(String command) {
        var m = COMMAND.matcher(command);
        return m.matches() && !m.group(1).equalsIgnoreCase("set") ? m.group(1) : null;
    }

    public void launch(boolean bossbar, long now) {
        if (!live(now)) {
            return;
        }
        if (bossbar) {
            boss = true;
        } else {
            action = true;
        }
        if (boss && action && launchedAt == 0) {
            launchedAt = now;
        }
    }

    public boolean qualified(long now) {
        return live(now) && launchedAt != 0;
    }

    /** Rapid replacement may reuse visible translated text; a fresh matching Bossbar update is still required. */
    public boolean retainLaunchFrom(TeleportAttempt previous, long now) {
        retainedLaunch = previous != null && previous.qualified(now) && now - previous.started <= 5000;
        return retainedLaunch;
    }

    public boolean refreshRetainedLaunch(long now) {
        if (retainedLaunch && live(now)) {
            launch(true, now);
            launch(false, now);
            retainedLaunch = false;
            return true;
        }
        return false;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public void bossEnded(long now) {
        if (qualified(now)) {
            bossEnded = true;
        }
    }

    public boolean live(long now) {
        return !cancelled && !arrived && now - started <= 30000;
    }

    public void positionCorrection(long now) {
        if (qualified(now) && correctionAt == 0) {
            correctionAt = now;
        }
    }

    /** Confirmation requires the server correction after both signals and loaded destination. */
    public boolean confirmArrival(long now, boolean destinationLoaded) {
        if (!qualified(now) || !bossEnded || correctionAt == 0 || now - correctionAt < 500 || !destinationLoaded) {
            return false;
        }
        arrived = true;
        return true;
    }
}
