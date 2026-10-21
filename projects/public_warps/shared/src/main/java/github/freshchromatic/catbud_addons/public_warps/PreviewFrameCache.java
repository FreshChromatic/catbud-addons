package github.freshchromatic.catbud_addons.public_warps;

public final class PreviewFrameCache {

    private static final long THUMBNAIL_INTERVAL_NANOS = 33_333_333L;

    private PreviewView renderedView;
    private long        renderedAt;

    public boolean reusable(PreviewView view, boolean interactive, long now) {
        if (renderedView == null) {
            return false;
        }
        return renderedView.equals(view) || (!interactive && now - renderedAt < THUMBNAIL_INTERVAL_NANOS);
    }

    public void rendered(PreviewView view, long now) {
        renderedView = view;
        renderedAt = now;
    }
}
