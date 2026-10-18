package github.freshchromatic.catbud_addons.monster_wave;

import java.time.Instant;

interface RunClock {

    Instant instant();

    long nanoTime();

    RunClock SYSTEM = new RunClock() {

        public Instant instant() {
            return Instant.now();
        }

        public long nanoTime() {
            return System.nanoTime();
        }
    };
}
