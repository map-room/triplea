package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthFilterTest {
  @Test
  void rejectsMissingHeader() throws Exception {
    final AuthFilter f = new AuthFilter("secret-token");
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/session", "{}");
    assertTrue(!f.authorized(ex));
    f.rejectUnauthorized(ex);
    assertEquals(401, ex.responseCode());
  }

  @Test
  void rejectsWrongToken() throws Exception {
    final AuthFilter f = new AuthFilter("secret-token");
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/session", "{}");
    ex.getRequestHeaders().add("Authorization", "Bearer wrong");
    assertTrue(!f.authorized(ex));
  }

  @Test
  void acceptsCorrectToken() throws Exception {
    final AuthFilter f = new AuthFilter("secret-token");
    final FakeHttpExchange ex = new FakeHttpExchange("POST", "/session", "{}");
    ex.getRequestHeaders().add("Authorization", "Bearer secret-token");
    assertTrue(f.authorized(ex));
  }

  @Test
  void healthSkipsAuth() {
    final AuthFilter f = new AuthFilter("secret-token");
    assertTrue(f.isPublicPath("/health"));
    assertTrue(!f.isPublicPath("/session"));
  }
}
