package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.triplea.ai.sidecar.session.SessionRegistry;
import org.triplea.ai.sidecar.wire.SessionCreateRequest;
import org.triplea.ai.sidecar.wire.SessionCreateResponse;

/**
 * Handles {@code POST /sessions} (v2 contract).
 *
 * <p>The caller supplies a deterministic {@code sessionId = matchID:nation:r{round}}. The handler
 * validates that {@code sessionId == gameId + ":" + nation + ":r" + round} and then delegates to
 * {@link SessionRegistry#createOrGet} which is idempotent — reopening an existing session returns
 * {@code created=false} without reinitialising ProData.
 */
public final class SessionCreateHandler implements HttpHandler {
  private static final System.Logger LOG = System.getLogger(SessionCreateHandler.class.getName());

  private final SessionRegistry registry;

  public SessionCreateHandler(final SessionRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      writeJson(exchange, 405, JsonBodies.errorBody("method-not-allowed", "POST required"));
      return;
    }
    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final SessionCreateRequest req;
    try {
      req = JsonBodies.readValue(body, SessionCreateRequest.class);
    } catch (final IOException e) {
      // Structured WARN per map-room#2305 — see SessionLifecycleHandler.handleUpdate.
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] sessions create validation failed exClass={0} message={1} bodyBytes={2}",
          new Object[] {e.getClass().getSimpleName(), e.getMessage(), body.length()});
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request", e.getMessage()));
      return;
    }
    // Validate required fields
    if (req.sessionId() == null || req.gameId() == null || req.nation() == null) {
      writeJson(
          exchange,
          400,
          JsonBodies.errorBody("bad-request", "sessionId, gameId and nation are required"));
      return;
    }
    // Validate per-round sessionId contract: must be gameId:nation:r{round}
    final String expectedId = req.gameId() + ":" + req.nation() + ":r" + req.round();
    if (!expectedId.equals(req.sessionId())) {
      writeJson(
          exchange,
          400,
          JsonBodies.errorBody(
              "bad-request",
              "sessionId must equal gameId + \":\" + nation + \":r\" + round (expected \""
                  + expectedId
                  + "\")"));
      return;
    }

    final SessionRegistry.CreateResult result =
        registry.createOrGet(
            new org.triplea.ai.sidecar.session.SessionKey(req.gameId(), req.nation(), req.round()),
            req.sessionId(),
            req.seed());

    writeJson(
        exchange,
        200,
        JsonBodies.writeValue(
            new SessionCreateResponse(result.session().sessionId(), result.created())));
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
