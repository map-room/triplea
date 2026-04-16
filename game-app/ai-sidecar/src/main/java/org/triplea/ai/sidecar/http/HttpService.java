package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.triplea.ai.sidecar.SidecarConfig;
import org.triplea.ai.sidecar.session.SessionRegistry;

public final class HttpService {
  private final HttpServer server;

  private HttpService(final HttpServer server) {
    this.server = server;
  }

  public static HttpService start(final SidecarConfig cfg, final SessionRegistry registry)
      throws IOException {
    final HttpServer server =
        HttpServer.create(new InetSocketAddress(cfg.bindHost(), cfg.port()), 0);
    final AuthFilter auth = new AuthFilter(cfg.authToken());

    server.createContext("/health", new HealthHandler());

    // v2 contract: POST /sessions (plural, deterministic sessionId)
    registerAuthed(server, "/sessions", new SessionCreateHandler(registry), auth);

    // Per-session sub-paths: /session/{id}/update, /session/{id}/decision, DELETE /session/{id}
    registerAuthed(server, "/session/", new CompositeSessionHandler(registry), auth);

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

  private static final class CompositeSessionHandler implements HttpHandler {
    private final SessionLifecycleHandler lifecycle;
    private final DecisionHandler decision;

    CompositeSessionHandler(final SessionRegistry registry) {
      this.lifecycle = new SessionLifecycleHandler(registry);
      this.decision = new DecisionHandler(registry);
    }

    @Override
    public void handle(final HttpExchange exchange) throws IOException {
      final String path = exchange.getRequestURI().getPath();
      if (path.endsWith("/decision")) {
        decision.handle(exchange);
      } else {
        lifecycle.handle(exchange);
      }
    }
  }
}
