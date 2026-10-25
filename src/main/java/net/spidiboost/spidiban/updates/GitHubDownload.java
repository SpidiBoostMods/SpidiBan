package net.spidiboost.spidiban.updates;

import java.io.*;
import java.net.*;
import java.util.Set;

public final class GitHubDownload {
    private static final Set<String> HOSTS = Set.of("github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com");
    private final HttpTransfer transfer;
    private final int totalTimeout;

    public GitHubDownload() { this(new HttpTransfer(), 45000); }
    GitHubDownload(HttpTransfer transfer, int totalTimeout) {
        this.transfer = transfer;
        this.totalTimeout = totalTimeout;
    }
    GitHubDownload(HttpTransfer.Connections connections, int connect, int read, int total) {
        this(new HttpTransfer(connections, connect, read, total), total);
    }

    public byte[] get(URI address, int limit) throws IOException {
        long deadline = System.nanoTime() + totalTimeout * 1_000_000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!trusted(address)) throw new IOException("Unexpected release download host");
            var response = transfer.request(address, null, limit, deadline);
            if (Set.of(301, 302, 303, 307, 308).contains(response.status())) {
                if (response.location() == null || response.location().isBlank()) throw new IOException("Missing redirect");
                try { address = address.resolve(response.location()); }
                catch (IllegalArgumentException error) { throw new IOException("Invalid release redirect", error); }
                continue;
            }
            if (response.status() != 200) throw new IOException("GitHub release HTTP " + response.status());
            return response.body();
        }
        throw new IOException("Too many release redirects");
    }

    public static boolean trusted(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                && uri.getUserInfo() == null && uri.getFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
}
