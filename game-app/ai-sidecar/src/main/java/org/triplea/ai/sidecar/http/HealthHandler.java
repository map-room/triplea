package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;

public final class HealthHandler implements HttpHandler {
  private static final System.Logger LOG = System.getLogger(HealthHandler.class.getName());

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, 0);
      exchange.close();
      return;
    }
    final byte[] body = "{\"status\":\"ok\"}".getBytes();
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    } catch (final IOException e) {
      if (isClientDisconnect(e)) {
        LOG.log(
            System.Logger.Level.DEBUG, "Health probe client hung up before body write completed");
      } else {
        throw e;
      }
    }
  }

  private static boolean isClientDisconnect(final IOException e) {
    if (matchesDisconnectMessage(e.getMessage())) {
      return true;
    }
    for (final Throwable suppressed : e.getSuppressed()) {
      if (suppressed instanceof IOException && matchesDisconnectMessage(suppressed.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesDisconnectMessage(final String message) {
    if (message == null) {
      return false;
    }
    return message.contains("Broken pipe")
        || message.contains("Connection reset by peer")
        || message.contains("insufficient bytes written");
  }
}
