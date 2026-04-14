package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionPathRouterTest {
  @Test
  void parsesSessionIdAndSubpath() {
    final Optional<SessionPathRouter.Match> m = SessionPathRouter.match("/session/s-abc/update");
    assertTrue(m.isPresent());
    assertEquals("s-abc", m.get().sessionId());
    assertEquals("update", m.get().subpath());
  }

  @Test
  void parsesDecisionSubpath() {
    final Optional<SessionPathRouter.Match> m = SessionPathRouter.match("/session/s-abc/decision");
    assertTrue(m.isPresent());
    assertEquals("decision", m.get().subpath());
  }

  @Test
  void parsesBareSessionDelete() {
    final Optional<SessionPathRouter.Match> m = SessionPathRouter.match("/session/s-abc");
    assertTrue(m.isPresent());
    assertEquals("s-abc", m.get().sessionId());
    assertEquals("", m.get().subpath());
  }

  @Test
  void rejectsNonSessionPath() {
    assertTrue(SessionPathRouter.match("/health").isEmpty());
    assertTrue(SessionPathRouter.match("/session").isEmpty());
    assertTrue(SessionPathRouter.match("/other/foo").isEmpty());
  }
}
