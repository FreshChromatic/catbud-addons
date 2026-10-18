package github.freshchromatic.catbud_addons.monster_wave;

enum DisabledRunSink implements RunSink {

    INSTANCE;

    @Override
    public void accept(RunEvent event) {
    }

    @Override
    public void finish(RunEvent event) {
    }

    @Override
    public String location() {
        return null;
    }

    @Override
    public String error() {
        return null;
    }

    @Override
    public long droppedEvents() {
        return 0;
    }
}
