package github.freshchromatic.catbud_addons.public_warps;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;

public record WarpSceneState(WarpSceneMesh mesh, BlockSnapshot snapshot, int x0, int y0, int x1, int y1, double angle,
    double pitch, double zoom, double inside, double offsetX, double offsetY, double offsetZ,
    ScreenRectangle scissorArea) implements PictureInPictureRenderState {

    public float scale() {
        return 1;
    }

    public ScreenRectangle bounds() {
        return PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea);
    }
}
