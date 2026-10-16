package github.freshchromatic.catbud_addons.magic_tower;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.util.List;

class RouteSweepTest {

    private static final List<Vec3> ROUTE = List.of(Vec3.ZERO, new Vec3(2, 0, 0), new Vec3(2, 0, 8));

    @Test
    void whiteBandCrossesCornersAtConstantDistanceAndPreservesTheWholeRoute() {
        var spans = RouteSweep.spans(ROUTE, 0.35);
        assertEquals(10, spans.stream().mapToDouble(s -> s.from().distanceTo(s.to())).sum(), 1e-9);
        var white = spans.stream().filter(RouteSweep.Span::highlighted).toList();
        assertEquals(3, white.stream().mapToDouble(s -> s.from().distanceTo(s.to())).sum(), 1e-9);
        assertEquals(2, white.size());
        assertEquals(0, new Vec3(1.25, 0, 0).distanceTo(white.getFirst().from()), 1e-9);
        assertEquals(0, new Vec3(2, 0, 2.25).distanceTo(white.getLast().to()), 1e-9);
        for (int i = 1; i < spans.size(); i++) {
            assertEquals(spans.get(i - 1).to(), spans.get(i).from());
        }
    }

    @Test
    void bandEntersAndLeavesWithoutWrappingAndHasAnAllBasePause() {
        assertTrue(RouteSweep.spans(ROUTE, 0).stream().noneMatch(RouteSweep.Span::highlighted));
        assertTrue(RouteSweep.spans(ROUTE, 0.99).stream().noneMatch(RouteSweep.Span::highlighted));
        double entering = RouteSweep.spans(ROUTE, 0.1).stream().filter(RouteSweep.Span::highlighted).mapToDouble(s -> s
            .from().distanceTo(s.to())).sum();
        assertEquals(0.5, entering, 1e-9);
        assertTrue(RouteSweep.spans(List.of(Vec3.ZERO, Vec3.ZERO), 0.5).isEmpty());
    }
}
