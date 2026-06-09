package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionsHandlerTest {

  private final SessionsHandler handler = new SessionsHandler();

  @Test
  void getReturns404WithStatelessBody() throws Exception {
    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/sessions", null);
    handler.handle(ex);
    assertEquals(404, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"stateless\""), "body=" + body);
    assertTrue(body.contains("\"message\":"), "body=" + body);
  }

  @Test
  void postReturns404WithStatelessBody() throws Exception {
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/sessions", "{}");
    handler.handle(ex);
    assertEquals(404, ex.responseCode());
    final String body = ex.responseBodyString();
    assertTrue(body.contains("\"status\":\"stateless\""), "body=" + body);
  }

  @Test
  void responseContentTypeIsJson() throws Exception {
    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/sessions", null);
    handler.handle(ex);
    final var ct = ex.getResponseHeaders().get("Content-Type");
    assertTrue(
        ct != null && ct.stream().anyMatch(v -> v.contains("application/json")),
        "expected application/json Content-Type, got: " + ct);
  }
}
