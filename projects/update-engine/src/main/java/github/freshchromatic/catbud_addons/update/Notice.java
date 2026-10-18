package github.freshchromatic.catbud_addons.update;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record Notice(String id, int revision, String title, String body, String severity, List<String> modules,
    List<String> minecraftVersions, List<String> servers, long startsAt, long expiresAt, String link,
    boolean notifyOnRevision) {

    public Notice {
        UUID.fromString(id);
        if (revision < 1 || title == null || title.length() > 80 || body == null || body.length() > 4000 || !List.of(
            "info", "important").contains(severity) || startsAt < 0 || expiresAt <= startsAt) {
            throw new IllegalArgumentException("Invalid announcement");
        }
        modules = List.copyOf(modules);
        minecraftVersions = List.copyOf(minecraftVersions);
        servers = List.copyOf(servers);
    }

    public boolean applies(Set<String> installed, String minecraft, String server, long now) {
        return startsAt <= now && expiresAt > now && (modules.isEmpty() || modules.stream().anyMatch(
            installed::contains)) && (minecraftVersions.isEmpty() || minecraftVersions.contains(minecraft)) && (servers
                .isEmpty() || servers.contains(server));
    }

    public String reminderId() {
        return "notice:" + id + ":" + (notifyOnRevision ? revision : 0);
    }
}
