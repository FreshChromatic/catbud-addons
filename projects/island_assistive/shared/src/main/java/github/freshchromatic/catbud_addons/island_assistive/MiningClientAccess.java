package github.freshchromatic.catbud_addons.island_assistive;

public interface MiningClientAccess {

    void islandAssistive$mining(BlockMiningSession session);

    BlockMiningSession islandAssistive$mining();

    boolean islandAssistive$bodyInput();

    void islandAssistive$resetAttackLock();
}
