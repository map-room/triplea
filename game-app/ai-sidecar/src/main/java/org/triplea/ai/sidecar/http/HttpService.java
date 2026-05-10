package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.SidecarConfig;

public final class HttpService {
  private final HttpServer server;

  private HttpService(final HttpServer server) {
    this.server = server;
  }

  public static HttpService start(final SidecarConfig cfg, final CanonicalGameData canonical)
      throws IOException {
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(cfg.bindHost(), cfg.port()), 0);
    final AuthFilter auth = new AuthFilter(cfg.authToken());

    server.createContext("/health", new HealthHandler());
    registerAuthed(server, "/decision", new DecisionHandler(canonical), auth);

    server.setExecutor(null);
    server.start();
    return new HttpService(server);
  }

  public int boundPort() {
    return server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
  }

  private static void registerAuthed(
      final HttpServer server,
      final String path,
      final HttpHandler handler,
      final AuthFilter auth) {
    server.createContext(
        path,
        exchange -> {
          if (!auth.authorized(exchange)) {
            auth.rejectUnauthorized(exchange);
            return;
          }
          handler.handle(exchange);
        });
  }
}
