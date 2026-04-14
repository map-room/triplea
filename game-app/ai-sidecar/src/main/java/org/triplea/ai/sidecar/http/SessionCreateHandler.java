package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.session.SessionRegistry;
import org.triplea.ai.sidecar.wire.SessionCreateRequest;
import org.triplea.ai.sidecar.wire.SessionCreateResponse;

public final class SessionCreateHandler implements HttpHandler {
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
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request", "invalid JSON body"));
      return;
    }
    if (req.gameId() == null || req.nation() == null) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request", "gameId and nation required"));
      return;
    }
    final Session session = registry.createOrGet(new SessionKey(req.gameId(), req.nation()), req.seed());
    writeJson(exchange, 200, JsonBodies.writeValue(new SessionCreateResponse(session.sessionId())));
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
