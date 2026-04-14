package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HealthHandlerTest {
  @Test
  void returns200WithOkStatus() throws Exception {
    final HealthHandler h = new HealthHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("GET", "/health", null);
    h.handle(ex);
    assertEquals(200, ex.responseCode());
    assertTrue(ex.responseBodyString().contains("\"status\":\"ok\""));
  }

  @Test
  void rejectsNonGet() throws Exception {
    final HealthHandler h = new HealthHandler();
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/health", null);
    h.handle(ex);
    assertEquals(405, ex.responseCode());
  }
}
