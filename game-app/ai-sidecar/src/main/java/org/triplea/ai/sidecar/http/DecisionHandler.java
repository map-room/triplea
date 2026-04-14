package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionRegistry;
import org.triplea.ai.sidecar.wire.DecisionRequest;
import org.triplea.ai.sidecar.wire.DecisionResponse;

public final class DecisionHandler implements HttpHandler {
  private static final Set<String> KNOWN_KINDS =
      Set.of(
          "purchase",
          "combat-move",
          "noncombat-move",
          "place",
          "select-casualties",
          "retreat-or-press",
          "scramble",
          "kamikaze");

  private final SessionRegistry registry;

  public DecisionHandler(final SessionRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      writeJson(exchange, 405, JsonBodies.errorBody("method-not-allowed", "POST required"));
      return;
    }
    final Optional<SessionPathRouter.Match> match =
        SessionPathRouter.match(exchange.getRequestURI().getPath());
    if (match.isEmpty() || !"decision".equals(match.get().subpath())) {
      writeJson(exchange, 404, JsonBodies.errorBody("not-found", "unknown path"));
      return;
    }
    final Optional<Session> session = registry.get(match.get().sessionId());
    if (session.isEmpty()) {
      writeJson(exchange, 404, JsonBodies.errorBody("not-found", "unknown session"));
      return;
    }
    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final DecisionRequest req;
    try {
      req = JsonBodies.readValue(body, DecisionRequest.class);
    } catch (final IOException e) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request", "invalid JSON body"));
      return;
    }
    if (req.kind() == null || !KNOWN_KINDS.contains(req.kind())) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request", "unknown kind: " + req.kind()));
      return;
    }
    writeJson(
        exchange,
        501,
        JsonBodies.writeValue(
            DecisionResponse.error("not-implemented: " + req.kind())));
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
