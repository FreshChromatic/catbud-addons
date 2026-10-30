package github.freshchromatic.catbud_addons.island_assistive;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class MobAlertSession {

    public static final long BASELINE_MILLIS = 2_000;
    public static final long REPEAT_MILLIS   = 5_000;

    private final LongSupplier   clock;
    private final MobAlertOutput output;
    private final AlertSound     sound;

    private final Set<EntityKey>              seen     = new HashSet<>();
    private final Map<EntityKey, MobSnapshot> baseline = new LinkedHashMap<>();
    private final Map<Long, Alert>            pending  = new LinkedHashMap<>();

    private UUID       token          = UUID.randomUUID();
    private boolean    enabled;
    private boolean    initialNotices = true;
    private double     radius         = 256;
    private MobMatcher matcher        = new MobMatcher(List.of());
    private String     dimension;
    private boolean    buildingBaseline;
    private long       baselineDeadline;
    private long       nextSound;
    private long       sequence;

    public MobAlertSession(LongSupplier clock, MobAlertOutput output, AlertSound sound) {
        this.clock = clock;
        this.output = output;
        this.sound = sound;
    }

    public void configure(boolean enabled, double radius, boolean initialNotices, List<MobWatchRule> rules) {
        boolean rebuild = this.radius != radius || !matcher.rules().equals(rules);
        this.radius = radius;
        this.initialNotices = initialNotices;
        matcher = new MobMatcher(rules);
        if (this.enabled != enabled) {
            this.enabled = enabled;
            reset();
        } else if (enabled && rebuild && dimension != null) {
            startBaseline();
        }
    }

    public void reset() {
        sound.stop();
        seen.clear();
        pending.clear();
        baseline.clear();
        token = UUID.randomUUID();
        dimension = null;
        buildingBaseline = false;
        sequence = 0;
        nextSound = 0;
    }

    public void worldChanged(String dimension) {
        if (!enabled) {
            return;
        }
        this.dimension = dimension;
        startBaseline();
    }

    private void startBaseline() {
        baseline.clear();
        buildingBaseline = true;
        baselineDeadline = clock.getAsLong() + BASELINE_MILLIS;
    }

    public void observe(List<MobSnapshot> mobs) {
        if (!enabled || dimension == null) {
            return;
        }
        long now = clock.getAsLong();
        if (buildingBaseline && now >= baselineDeadline) {
            buildingBaseline = false;
            if (initialNotices && !baseline.isEmpty()) {
                output.initial(List.copyOf(baseline.values()));
            }
            baseline.clear();
        }
        for (MobSnapshot mob : mobs) {
            if (mob.distance() > radius || !matcher.matches(mob)) {
                continue;
            }
            var key = new EntityKey(dimension, mob.uuid());
            if (!seen.add(key)) {
                continue;
            }
            if (buildingBaseline) {
                baseline.put(key, mob);
                continue;
            }
            var alert = new Alert(++sequence, dimension, mob);
            boolean first = pending.isEmpty();
            pending.put(alert.id(), alert);
            output.discovered(token, alert);
            if (first) {
                sound.play();
                nextSound = now + REPEAT_MILLIS;
            }
        }
    }

    public void tick() {
        if (!enabled || pending.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        if (now >= nextSound) {
            sound.play();
            nextSound = now + REPEAT_MILLIS;
        }
    }

    public boolean acknowledge(UUID token, long id, boolean through) {
        if (!this.token.equals(token) || id < 1 || id > sequence || !through && !pending.containsKey(id)) {
            return false;
        }
        if (through) {
            pending.keySet().removeIf(value -> value <= id);
        } else {
            pending.remove(id);
        }
        if (pending.isEmpty()) {
            sound.stop();
            nextSound = 0;
        }
        return true;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean buildingBaseline() {
        return buildingBaseline;
    }

    public boolean hasWorld() {
        return dimension != null;
    }

    public double radius() {
        return radius;
    }

    public List<Alert> pending() {
        return new ArrayList<>(pending.values());
    }

    public UUID token() {
        return token;
    }

    private record EntityKey(String dimension, UUID uuid) {}

    public record Alert(long id, String dimension, MobSnapshot mob) {}
}
