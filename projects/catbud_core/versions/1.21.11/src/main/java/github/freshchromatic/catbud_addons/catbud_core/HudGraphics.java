package github.freshchromatic.catbud_addons.catbud_core;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.joml.Matrix3x2fStack;
public final class HudGraphics {
    private final GuiGraphics graphics;
    public HudGraphics(GuiGraphics graphics) { this.graphics = graphics; }
    public Matrix3x2fStack pose() { return graphics.pose(); }
    public void fill(int x1, int y1, int x2, int y2, int color) { graphics.fill(x1,y1,x2,y2,color); }
    public void centeredText(Font font, String text, int x, int y, int color) { graphics.drawCenteredString(font,text,x,y,color); }
    public void blit(RenderPipeline pipeline, Identifier texture, int x,int y,float u,float v,int w,int h,int tw,int th,int color) { graphics.blit(pipeline,texture,x,y,u,v,w,h,tw,th,color); }
}
