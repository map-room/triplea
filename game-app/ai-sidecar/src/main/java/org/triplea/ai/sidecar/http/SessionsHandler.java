package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Explicitly rejects all {@code /sessions/*} routes with 404 and a statelessness explanation.
 *
 * <p>The sidecar is fully stateless: {@code POST /decision} carries the entire game state in the
 * request body; a fresh {@link games.strategy.engine.data.GameData} clone and a fresh {@link
 * games.strategy.triplea.ai.pro.ProAi} are constructed per call. There are no sessions to create,
 * query, or reset.
 *
 * <p>Returning 404 rather than 501 or 405 matches the contract (the resource does not exist) and
 * gives the ai-bot a clear signal that it is talking to a stateless sidecar.
 */
public final class SessionsHandler implements HttpHandler {

  private static final byte[] BODY =
      ("{\"status\":\"stateless\",\"message\":"
              + "\"This sidecar is fully stateless. POST /decision carries the entire game state;"
              + " no session exists to reset or query.\"}")
          .getBytes(StandardCharsets.UTF_8);

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(404, BODY.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(BODY);
    }
  }
}
