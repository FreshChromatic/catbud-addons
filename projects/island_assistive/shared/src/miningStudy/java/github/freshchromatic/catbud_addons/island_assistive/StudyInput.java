package github.freshchromatic.catbud_addons.island_assistive;

import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.Options;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;

public sealed interface StudyInput {

    record Key(int action, KeyEvent key) implements StudyInput {}

    record Button(MouseButtonInfo button, int action) implements StudyInput {}

    record Move(double x, double y, double dx, double dy) implements StudyInput {}

    record Scroll(double x, double y) implements StudyInput {}

    default boolean takesControl(Options options) {
        if (this instanceof Button button) {
            return button.action() == InputConstants.PRESS && button.button()
                .button() == InputConstants.MOUSE_BUTTON_LEFT;
        }
        if (!(this instanceof Key key) || key.action() != InputConstants.PRESS) {
            return false;
        }
        for (var binding : new KeyMapping[] { options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
            options.keyJump, options.keyShift }) {
            if (binding.matches(key.key())) {
                return true;
            }
        }
        for (var binding : options.keyHotbarSlots) {
            if (binding.matches(key.key())) {
                return true;
            }
        }
        return false;
    }

    default String eventType() {
        return switch (this) {
            case Key key -> "native_key";
            case Button button -> "native_button";
            case Move move -> "native_move";
            case Scroll scroll -> "native_scroll";
        };
    }
}
