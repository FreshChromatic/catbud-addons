package github.freshchromatic.catbud_addons.monster_wave;

interface RunSink {

    void accept(RunEvent event);

    void finish(RunEvent event);

    String location();

    String error();

    long droppedEvents();
}
