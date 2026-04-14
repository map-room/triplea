package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;

public final class AuthFilter {
  private final String expectedBearer;

  public AuthFilter(final String authToken) {
    this.expectedBearer = "Bearer " + authToken;
  }

  public boolean isPublicPath(final String path) {
    return "/health".equals(path);
  }

  public boolean authorized(final HttpExchange exchange) {
    final String header = exchange.getRequestHeaders().getFirst("Authorization");
    return expectedBearer.equals(header);
  }

  public void rejectUnauthorized(final HttpExchange exchange) throws IOException {
    final byte[] body = "{\"error\":\"unauthorized\"}".getBytes();
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(401, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
