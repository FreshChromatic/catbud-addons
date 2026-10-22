package github.freshchromatic.catbud_addons.update;

import java.nio.file.Path;
import java.util.Set;

public record UpdateEnvironment(String minecraft, String loaderVersion, Path directory, Set<String> installedModules) {

    public UpdateEnvironment {
        directory = directory.toAbsolutePath().normalize();
        installedModules = Set.copyOf(installedModules);
    }
}
