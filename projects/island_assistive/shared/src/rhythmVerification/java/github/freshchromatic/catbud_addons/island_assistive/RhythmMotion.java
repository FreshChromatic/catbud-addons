package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;

final class RhythmMotion {

    record Vector(double x, double y, double z) {

        Vector add(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        Vector subtract(Vector other) {
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        Vector scale(double value) {
            return new Vector(x * value, y * value, z * value);
        }

        double dot(Vector other) {
            return x * other.x + y * other.y + z * other.z;
        }

        Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }

        double length() {
            return Math.sqrt(dot(this));
        }

        Vector unit() {
            return scale(1 / length());
        }

        double angle(Vector other) {
            return Math.toDegrees(Math.atan2(cross(other).length(), dot(other)));
        }
    }

    record Rotation(double yaw, double pitch) {

        Vector vector() {
            double y = Math.toRadians(yaw);
            double p = Math.toRadians(pitch);
            return new Vector(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
        }

        static Rotation from(Vector vector, double previousYaw) {
            var unit = vector.unit();
            double yaw = Math.hypot(unit.x, unit.z) < 1e-10 ? previousYaw : Math.toDegrees(Math.atan2(-unit.x, unit.z));
            return new Rotation(previousYaw + wrap(yaw - previousYaw), Math.toDegrees(Math.asin(Math.clamp(-unit.y, -1,
                1))));
        }
    }

    record Knot(double fraction, double forward, double lateral) {}

    record Profile(String id, String session, String phase, String family, double initialPitch, double referenceAngle,
        double referenceDistance, double delaySeconds, double movementSeconds, List<Knot> knots) {

        Vector sample(double fraction) {
            if (fraction <= 0) {
                return new Vector(0, 0, 0);
            }
            if (fraction >= 1) {
                return new Vector(1, 0, 0);
            }
            for (int index = 1; index < knots.size(); index++) {
                var a = knots.get(index - 1);
                var b = knots.get(index);
                if (fraction <= b.fraction) {
                    double amount = (fraction - a.fraction) / (b.fraction - a.fraction);
                    return new Vector(a.forward + amount * (b.forward - a.forward), a.lateral + amount * (b.lateral
                        - a.lateral), 0);
                }
            }
            throw new IllegalStateException("Uncovered rhythm time");
        }
    }

    record Context(String session, String family, Vector eye, Rotation start, Vector reference) {}

    interface Guide {

        Rotation direction(double seconds, double previousYaw);
    }

    static Profile select(Context context, List<Profile> profiles) {
        double angle = context.start.vector().angle(context.reference.subtract(context.eye));
        double distance = context.reference.subtract(context.eye).length();
        Profile selected = null;
        double best = Double.POSITIVE_INFINITY;
        for (var profile : profiles) {
            if (profile.session.equals(context.session) || !profile.phase.equals("mining_active") || !profile.family
                .equals(context.family) || Math.min(angle, profile.referenceAngle) <= 1e-6) {
                continue;
            }
            double ratio = Math.max(angle / profile.referenceAngle, profile.referenceAngle / angle);
            double pitch = Math.abs(context.start.pitch - profile.initialPitch);
            double difference = Math.abs(distance - profile.referenceDistance);
            if (ratio > 2 || pitch > 15 || difference > 1) {
                continue;
            }
            double score = Math.abs(Math.log(angle / profile.referenceAngle)) + pitch / 30 + difference / 3;
            if (score < best || score == best && profile.id.compareTo(selected.id) < 0) {
                best = score;
                selected = profile;
            }
        }
        return selected;
    }

    static final class Controller {

        private final Context context;
        private final Profile profile;
        private final boolean full;
        private final Vector  forward;
        private final Vector  tangent;
        private final Vector  normal;
        private final double  angle;
        private final double  duration;
        private final Guide   guide;

        private Rotation current;
        private Rotation previousTarget;
        private double   elapsed;
        private double   yawVelocity;
        private double   pitchVelocity;
        private double   peakSpeed;
        private double   peakAcceleration;

        Controller(Context context, Profile profile, boolean full) {
            this(context, profile, full, null);
        }

        Controller(Context context, Profile profile, boolean full, Guide guide) {
            this.context = context;
            this.profile = profile;
            this.full = full;
            this.guide = guide;
            current = context.start;
            previousTarget = current;
            forward = current.vector();
            var goal = context.reference.subtract(context.eye).unit();
            tangent = goal.subtract(forward.scale(goal.dot(forward))).unit();
            normal = forward.cross(tangent).unit();
            angle = Math.toRadians(forward.angle(goal));
            var end = Rotation.from(goal, current.yaw);
            double distance = Math.hypot(wrap(end.yaw - current.yaw), end.pitch - current.pitch);
            double minimum = distance <= 22.5 ? 2 * Math.sqrt(distance / 1440) : distance / 180 + .125;
            duration = Math.max(profile.movementSeconds, minimum);
        }

        Rotation advance(double seconds) {
            int steps = Math.max(1, (int) Math.ceil(seconds * 240));
            double interval = seconds / steps;
            if (interval <= 0) {
                return current;
            }
            for (int index = 0; index < steps; index++) {
                elapsed += interval;
                var target = target(elapsed, previousTarget.yaw);
                var feed = limited(wrap(target.yaw - previousTarget.yaw) / interval, (target.pitch
                    - previousTarget.pitch) / interval, 180);
                var desired = limited(feed.x + wrap(target.yaw - current.yaw) * 12, feed.y + (target.pitch
                    - current.pitch) * 10, 180);
                var acceleration = limited((desired.x - yawVelocity) * 24, (desired.y - pitchVelocity) * 20, 1440);
                double upper = pitchLimit(90 - current.pitch, interval);
                double lower = -pitchLimit(90 + current.pitch, interval);
                double nextPitch = Math.clamp(pitchVelocity + acceleration.y * interval, lower, upper);
                double pitchAcceleration = (nextPitch - pitchVelocity) / interval;
                if (Math.abs(pitchAcceleration) > 1440.000001) {
                    throw new IllegalStateException("No feasible pitch braking step");
                }
                double yawLimit = Math.sqrt(Math.max(0, 1440 * 1440 - pitchAcceleration * pitchAcceleration));
                double yawAcceleration = Math.clamp(acceleration.x, -yawLimit, yawLimit);
                var velocity = limited(yawVelocity + yawAcceleration * interval, nextPitch, 180);
                peakAcceleration = Math.max(peakAcceleration, Math.hypot(velocity.x - yawVelocity, velocity.y
                    - pitchVelocity) / interval);
                yawVelocity = velocity.x;
                pitchVelocity = velocity.y;
                peakSpeed = Math.max(peakSpeed, Math.hypot(yawVelocity, pitchVelocity));
                current = new Rotation(current.yaw + yawVelocity * interval, current.pitch + pitchVelocity * interval);
                if (!Double.isFinite(current.yaw) || current.pitch < -90 || current.pitch > 90) {
                    throw new IllegalStateException("Invalid generated view");
                }
                previousTarget = target;
            }
            return current;
        }

        Rotation target(double seconds, double previousYaw) {
            if (guide != null) {
                return guide.direction(seconds, previousYaw);
            }
            double fraction = Math.clamp((seconds - profile.delaySeconds) / duration, 0, 1);
            var progress = full ? profile.sample(fraction) : new Vector(fraction, 0, 0);
            var offset = tangent.scale(progress.x).add(normal.scale(progress.y)).scale(angle);
            double length = offset.length();
            if (length < 1e-12) {
                return context.start;
            }
            return Rotation.from(forward.scale(Math.cos(length)).add(offset.unit().scale(Math.sin(length))),
                previousYaw);
        }

        double duration() {
            return duration;
        }

        double peakSpeed() {
            return peakSpeed;
        }

        double peakAcceleration() {
            return peakAcceleration;
        }
    }

    private static Vector limited(double x, double y, double bound) {
        double length = Math.hypot(x, y);
        double scale = length > bound ? bound / length : 1;
        return new Vector(x * scale, y * scale, 0);
    }

    private static double pitchLimit(double margin, double seconds) {
        return 2 * margin / (Math.sqrt(seconds * seconds + 2 * margin / 1440) + seconds);
    }

    static double wrap(double value) {
        double result = value % 360;
        if (result >= 180) {
            return result - 360;
        }
        return result < -180 ? result + 360 : result;
    }
}
