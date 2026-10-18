package github.freshchromatic.catbud_addons.update;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JdkReleaseTransport implements ReleaseTransport {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public HttpResponse<InputStream> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
