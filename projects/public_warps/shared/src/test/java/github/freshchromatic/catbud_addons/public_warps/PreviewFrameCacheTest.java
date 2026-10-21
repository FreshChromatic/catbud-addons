package github.freshchromatic.catbud_addons.public_warps;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PreviewFrameCacheTest {

    @Test
    void interactiveMovementInvalidatesTextureOnEveryAxisWithoutWaitingForThumbnailInterval() {
        var cache = new PreviewFrameCache();
        var origin = new PreviewView(0, 0, 1, 1, 0, 0, 0);
        assertFalse(cache.reusable(origin, true, 0));
        cache.rendered(origin, 0);
        assertTrue(cache.reusable(origin, true, 1));
        assertFalse(cache.reusable(new PreviewView(0, 0, 1, 1, 1, 0, 0), true, 1));
        assertFalse(cache.reusable(new PreviewView(0, 0, 1, 1, 0, 1, 0), true, 1));
        assertFalse(cache.reusable(new PreviewView(0, 0, 1, 1, 0, 0, 1), true, 1));
        assertFalse(cache.reusable(new PreviewView(.1, 0, 1, 1, 0, 0, 0), true, 1));
        assertFalse(cache.reusable(new PreviewView(0, .1, 1, 1, 0, 0, 0), true, 1));
        assertFalse(cache.reusable(new PreviewView(0, 0, 2, 1, 0, 0, 0), true, 1));
        assertFalse(cache.reusable(new PreviewView(0, 0, 1, 0, 0, 0, 0), true, 1));
    }

    @Test
    void animatedThumbnailsRemainThrottledAndStationaryViewsKeepTheirTexture() {
        var cache = new PreviewFrameCache();
        var origin = new PreviewView(0, 0, 1, 0, 0, 0, 0);
        var rotated = new PreviewView(.1, 0, 1, 0, 0, 0, 0);
        cache.rendered(origin, 0);
        assertTrue(cache.reusable(rotated, false, 16_666_667));
        assertFalse(cache.reusable(rotated, false, 33_333_333));
        assertFalse(cache.reusable(rotated, true, 16_666_667));
        assertTrue(cache.reusable(origin, false, 1_000_000_000));
        cache.rendered(rotated, 40_000_000);
        assertTrue(cache.reusable(rotated, true, 40_000_001));
        assertFalse(cache.reusable(origin, true, 40_000_001));
    }
}
