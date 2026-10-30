package github.freshchromatic.catbud_addons.island_assistive;

import java.util.List;
import java.util.UUID;

public interface MobAlertOutput {

    void initial(List<MobSnapshot> mobs);

    void discovered(UUID session, MobAlertSession.Alert alert);
}
