package net.spidiboost.spidiban.updates;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded HTTPS downloads with a fixed GitHub trust boundary and a whole-body deadline. */
public final class GitHubDownload {
    private static final Set<String> HOSTS = Set.of("github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public byte[] get(URI address, int limit) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trusted(address)) throw new IOException("Unexpected release download host");
            var request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(40))
                    .header("User-Agent", "SpidiBoost-SpidiCard-Updater").GET().build();
            var future = http.sendAsync(request, ignored -> new LimitedBody(limit));
            HttpResponse<byte[]> response;
            try { response = future.get(45, TimeUnit.SECONDS); }
            catch (Exception e) { future.cancel(true); throw e; }
            if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                address = address.resolve(response.headers().firstValue("location").orElseThrow()); continue;
            }
            if (response.statusCode() != 200) throw new IOException("GitHub release HTTP " + response.statusCode());
            return response.body();
        }
        throw new IOException("Too many release redirects");
    }
    public static boolean trusted(URI uri) {
        return "https".equals(uri.getScheme()) && HOSTS.contains(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Release download exceeds size limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
