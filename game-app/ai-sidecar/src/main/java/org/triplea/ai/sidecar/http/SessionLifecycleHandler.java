package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionRegistry;
import org.triplea.ai.sidecar.wire.SessionUpdateRequest;

public final class SessionLifecycleHandler implements HttpHandler {

  private static final System.Logger LOG =
      System.getLogger(SessionLifecycleHandler.class.getName());

  private final SessionRegistry registry;

  public SessionLifecycleHandler(final SessionRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    final Optional<SessionPathRouter.Match> match =
        SessionPathRouter.match(exchange.getRequestURI().getPath());
    if (match.isEmpty()) {
      writeJson(exchange, 404, JsonBodies.errorBody("not-found", "unknown path"));
      return;
    }
    final String sessionId = match.get().sessionId();
    final String sub = match.get().subpath();
    final String method = exchange.getRequestMethod();

    if ("DELETE".equals(method) && sub.isEmpty()) {
      handleDelete(exchange, sessionId);
      return;
    }
    if ("POST".equals(method) && "update".equals(sub)) {
      handleUpdate(exchange, sessionId);
      return;
    }
    writeJson(exchange, 405, JsonBodies.errorBody("method-not-allowed", method + " " + sub));
  }

  private void handleDelete(final HttpExchange exchange, final String sessionId)
      throws IOException {
    if (!registry.delete(sessionId)) {
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] session not found matchID={0} endpoint=delete",
          sessionId);
      writeJson(exchange, 404, JsonBodies.errorBody("not-found", "unknown session"));
      return;
    }
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private void handleUpdate(final HttpExchange exchange, final String sessionId)
      throws IOException {
    final Optional<Session> session = registry.get(sessionId);
    if (session.isEmpty()) {
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] session not found matchID={0} endpoint=update",
          sessionId);
      writeJson(exchange, 404, JsonBodies.errorBody("not-found", "unknown session"));
      return;
    }
    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    try {
      JsonBodies.readValue(body, SessionUpdateRequest.class);
    } catch (final IOException e) {
      // Structured WARN per map-room#2305: matchID + exception class + message + body
      // length so production drift surfaces in Loki without a follow-up logging fix.
      // Body itself is not logged (game state, potentially large; full slice goes
      // into the response body for the bot's own logs via map-room/map-room#2306).
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] update validation failed matchID={0} exClass={1} message={2} bodyBytes={3}",
          new Object[] {sessionId, e.getClass().getSimpleName(), e.getMessage(), body.length()});
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request", e.getMessage()));
      return;
    }
    // Phase 1: accept and record the delta request; actual GameData mutation is Phase 2.
    // Touch updatedAt so the reaper doesn't mark this session as stale.
    registry.touchUpdatedAt(session.get().key());
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
  }

  private static void writeJson(final HttpExchange ex, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }
}
