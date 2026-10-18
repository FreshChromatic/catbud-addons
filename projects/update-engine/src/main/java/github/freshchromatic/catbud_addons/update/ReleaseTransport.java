package github.freshchromatic.catbud_addons.update;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
public interface ReleaseTransport extends AutoCloseable {

    HttpResponse<InputStream> send(HttpRequest request) throws Exception;

    @Override
    default void close() {
    }
}
