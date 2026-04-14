package org.triplea.ai.sidecar.http;

import java.util.Optional;

public final class SessionPathRouter {
  public record Match(String sessionId, String subpath) {}

  private SessionPathRouter() {}

  public static Optional<Match> match(final String path) {
    if (path == null || !path.startsWith("/session/")) {
      return Optional.empty();
    }
    final String tail = path.substring("/session/".length());
    if (tail.isEmpty()) {
      return Optional.empty();
    }
    final int slash = tail.indexOf('/');
    if (slash < 0) {
      return Optional.of(new Match(tail, ""));
    }
    final String id = tail.substring(0, slash);
    final String sub = tail.substring(slash + 1);
    if (id.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Match(id, sub));
  }
}
