package net.spidiboost.spidiban.updates;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GitHubDownloadTest {
  @TempDir static Path root;
  static SSLContext tls;
  HttpsServer server;
  ExecutorService worker;
  final AtomicInteger requests = new AtomicInteger();

  @BeforeAll
  static void certificate() throws Exception {
    Path store = root.resolve("localhost.p12");
    String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool" + suffix).toString(),
                "-genkeypair",
                "-alias",
                "localhost",
                "-keyalg",
                "RSA",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "fixture-password",
                "-keypass",
                "fixture-password",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost,ip:127.0.0.1",
                "-validity",
                "2",
                "-noprompt")
            .redirectErrorStream(true)
            .start();
    try {
      assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
      assertEquals(
          0,
          keytool.exitValue(),
          new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    } finally {
      keytool.destroyForcibly();
      keytool.waitFor(5, TimeUnit.SECONDS);
    }
    var keys = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(store)) {
      keys.load(input, "fixture-password".toCharArray());
    }
    var manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    manager.init(keys, "fixture-password".toCharArray());
    var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keys);
    tls = SSLContext.getInstance("TLS");
    tls.init(manager.getKeyManagers(), trust.getTrustManagers(), null);
  }

  @BeforeEach
  void start() throws Exception {
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(tls));
    worker = Executors.newFixedThreadPool(4);
    server.setExecutor(worker);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          String path = exchange.getRequestURI().getPath();
          try (exchange) {
            if (path.equals("/post")) {
              assertEquals("POST", exchange.getRequestMethod());
              assertEquals("close", exchange.getRequestHeaders().getFirst("Connection"));
              byte[] payload=exchange.getRequestBody().readAllBytes();
              exchange.sendResponseHeaders(200,payload.length);
              exchange.getResponseBody().write(payload);
            } else if (path.equals("/redirect")) {
              exchange.getResponseHeaders().set("Location", "/ok");
              exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/loop")) {
              exchange.getResponseHeaders().set("Location", "/loop");
              exchange.sendResponseHeaders(307, -1);
            } else if (path.equals("/foreign")) {
              exchange.getResponseHeaders().set("Location", "https://evil.invalid/payload");
              exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/plain")) {
              exchange.getResponseHeaders().set("Location", "http://github.com/payload");
              exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/error")) {
              exchange.sendResponseHeaders(503, -1);
            } else if (path.equals("/stall")) {
              exchange.sendResponseHeaders(200, 10);
              exchange.getResponseBody().write(1);
              exchange.getResponseBody().flush();
              Thread.sleep(1000);
            } else if (path.equals("/drip")) {
              exchange.sendResponseHeaders(200, 100);
              for (int i = 0; i < 100; i++) {
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                Thread.sleep(30);
              }
            } else if (path.equals("/cut")) {
              exchange.sendResponseHeaders(200, 100);
              exchange.getResponseBody().write(new byte[3]);
            } else {
              byte[] data =
                  path.equals("/large")
                      ? new byte[1024]
                      : "verified".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, path.equals("/chunked") ? 0 : data.length);
              exchange.getResponseBody().write(data);
            }
          } catch (IOException ignored) {
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    server.start();
  }

  GitHubDownload download(int read, int total) {
    return new GitHubDownload(
        uri -> {
          var connection =
              (HttpsURLConnection)
                  URI.create(
                          "https://127.0.0.1:" + server.getAddress().getPort() + uri.getRawPath())
                      .toURL()
                      .openConnection();
          connection.setSSLSocketFactory(tls.getSocketFactory());
          return connection;
        },
        2000,
        read,
        total);
  }

  byte[] get(String path, int limit) throws IOException {
    return download(2000, 5000).get(URI.create("https://github.com" + path), limit);
  }

  @AfterEach
  void stop() throws Exception {
    server.stop(0);
    worker.shutdownNow();
    assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  void realTlsDownloadsAndRelativeRedirects() throws Exception {
    assertArrayEquals("verified".getBytes(StandardCharsets.UTF_8), get("/ok", 8));
    assertArrayEquals("verified".getBytes(StandardCharsets.UTF_8), get("/redirect", 8));
    assertEquals(3, requests.get());
  }

  @Test
  void tlsPostPreservesUtf8AndCloseRejectsFurtherRequests() throws Exception {
    var transfer = new HttpTransfer(uri -> {
      var connection=(HttpsURLConnection)URI.create("https://127.0.0.1:"+server.getAddress().getPort()+uri.getRawPath()).toURL().openConnection();
      connection.setSSLSocketFactory(tls.getSocketFactory());
      return connection;
    },2000,2000,5000);
    String body="{\"user\":\"Игрок\"}";
    assertEquals(body,new String(transfer.request(URI.create("https://github.com/post"),body,1000).body(),StandardCharsets.UTF_8));
    transfer.close();
    assertThrows(InterruptedIOException.class,()->transfer.request(URI.create("https://github.com/post"),body,1000));
    assertEquals(1,requests.get());
    try(var input=HttpTransfer.class.getResourceAsStream("HttpTransfer.class")){
      assertFalse(new String(input.readAllBytes(),StandardCharsets.ISO_8859_1).contains("java/net/http"));
    }
  }

  @Test
  void rejectsSizeBeforeBodyAndDuringChunkedBody() {
    assertThrows(IOException.class, () -> get("/large", 16));
    assertThrows(IOException.class, () -> get("/chunked", 3));
    assertThrows(IOException.class, () -> get("/cut", 1024));
  }

  @Test
  void validatesEveryRedirectAndCapsLoops() {
    assertThrows(IOException.class, () -> get("/foreign", 100));
    assertThrows(IOException.class, () -> get("/plain", 100));
    assertEquals(2, requests.get());
    assertThrows(IOException.class, () -> get("/loop", 100));
    assertEquals(8, requests.get());
    assertThrows(IOException.class, () -> get("/error", 100));
  }

  @Test
  void stalledTlsReadAndSlowDripAreBounded() {
    var download = download(150, 350);
    long start = System.nanoTime();
    assertThrows(
        SocketTimeoutException.class,
        () -> download.get(URI.create("https://github.com/stall"), 1000));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
    start = System.nanoTime();
    assertThrows(
        SocketTimeoutException.class,
        () -> download.get(URI.create("https://github.com/drip"), 1000));
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
  }

  @Test
  void repeatedDisconnectsRecoverWithoutAsyncHttpWorkers() throws Exception {
    Set<Long> before = asyncThreads();
    for (int i = 0; i < 30; i++) {
      assertThrows(IOException.class, () -> get("/cut", 1024));
      assertEquals("verified", new String(get("/ok", 16), StandardCharsets.UTF_8));
    }
    assertEquals(before, asyncThreads());
    assertEquals(60, requests.get());
    try (var input = GitHubDownload.class.getResourceAsStream("GitHubDownload.class")) {
      String binary = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
      assertFalse(binary.contains("java/net/http"));
      assertFalse(binary.contains("SSLEngine"));
    }
  }

  private static Set<Long> asyncThreads() {
    Set<Long> ids = new HashSet<>();
    Thread.getAllStackTraces()
        .forEach(
            (thread, stack) -> {
              if (thread.getName().startsWith("HttpClient-")
                  || Arrays.stream(stack)
                      .anyMatch(frame -> frame.getClassName().contains("SSLFlowDelegate")))
                ids.add(thread.threadId());
            });
    return ids;
  }

  @Test
  void keepsCertificateAndHostnameVerification() {
    var download =
        new GitHubDownload(
            uri ->
                (HttpsURLConnection)
                    URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/ok")
                        .toURL()
                        .openConnection(),
            2000,
            2000,
            5000);
    assertThrows(
        SSLHandshakeException.class, () -> download.get(URI.create("https://github.com/ok"), 16));
  }

  @Test
  void rejectsUntrustedAddressesWithoutOpeningConnection() {
    for (String uri :
        List.of(
            "http://github.com/a",
            "https://github.com:444/a",
            "https://github.com.evil/a",
            "https://name@github.com/a",
            "file:///tmp/file",
            "https://github.com/a#fragment")) {
      assertFalse(GitHubDownload.trusted(URI.create(uri)));
      assertThrows(
          IOException.class,
          () ->
              new GitHubDownload(
                      address -> {
                        fail("Connection opened");
                        return null;
                      },
                      100,
                      100,
                      100)
                  .get(URI.create(uri), 16));
    }
  }
}
