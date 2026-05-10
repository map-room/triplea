package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionRequest;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.OtherOffensiveRequest;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.exec.DecisionExecutor;
import org.triplea.ai.sidecar.exec.NoncombatMoveExecutor;
import org.triplea.ai.sidecar.exec.PurchaseExecutor;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionRegistry;

/**
 * Dispatches {@code POST /session/{id}/decision} calls to a kind-specific {@link DecisionExecutor}.
 *
 * <p>The request body is deserialised through the polymorphic {@link DecisionRequest} sealed
 * interface (Jackson discriminator {@code kind}). The concrete request type drives a pattern switch
 * that picks the matching executor; the returned {@link DecisionPlan} is wrapped in a {@code
 * {"status":"ready","plan":{...}}} envelope and sent as the 200 response body.
 *
 * <p>Known decision kinds: {@code purchase} and {@code noncombat-move}. Any other kind returns 400
 * via Jackson deserialisation failure (unknown discriminator value).
 */
public final class DecisionHandler implements HttpHandler {

  private static final System.Logger LOG = System.getLogger(DecisionHandler.class.getName());

  private final SessionRegistry registry;
  private final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor;
  private final DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> noncombatMoveExecutor;

  /** Production constructor — wires all executors. */
  public DecisionHandler(final SessionRegistry registry) {
    this(registry, new PurchaseExecutor(registry.snapshotStore()), new NoncombatMoveExecutor());
  }

  /** Test constructor — accepts executor stubs so handler logic can be exercised in isolation. */
  public DecisionHandler(
      final SessionRegistry registry,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor,
      final DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> noncombatMoveExecutor) {
    this.registry = registry;
    this.purchaseExecutor = purchaseExecutor;
    this.noncombatMoveExecutor = noncombatMoveExecutor;
  }

  @Override
  public void handle(final HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      writeJson(exchange, 405, JsonBodies.errorBody("method-not-allowed"));
      return;
    }
    final Optional<SessionPathRouter.Match> match =
        SessionPathRouter.match(exchange.getRequestURI().getPath());
    if (match.isEmpty() || !"decision".equals(match.get().subpath())) {
      writeJson(exchange, 404, JsonBodies.errorBody("not-found"));
      return;
    }
    final Optional<Session> session = registry.get(match.get().sessionId());
    if (session.isEmpty()) {
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] session not found matchID={0} endpoint=decision",
          match.get().sessionId());
      writeJson(exchange, 404, JsonBodies.errorBody("unknown-session"));
      return;
    }

    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final DecisionRequest request;
    try {
      request = JsonBodies.readValue(body, DecisionRequest.class);
    } catch (final IOException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] decision validation failed matchID={0} exClass={1} message={2} bodyBytes={3}",
          new Object[] {
            match.get().sessionId(), e.getClass().getSimpleName(), e.getMessage(), body.length()
          });
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
      return;
    }
    if (request == null) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
      return;
    }

    AiTraceLogger.setMatchId(session.get().key().gameId());
    try {
      switch (request) {
        case PurchaseRequest pr -> {
          final PurchasePlan plan = purchaseExecutor.execute(session.get(), pr);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case NoncombatMoveRequest nm -> {
          final NoncombatMovePlan plan = noncombatMoveExecutor.execute(session.get(), nm);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case OtherOffensiveRequest oo ->
            writeJson(exchange, 501, JsonBodies.errorBodyWithKind("not-implemented", oo.kind()));
      }
    } catch (final IllegalArgumentException e) {
      LOG.log(System.Logger.Level.ERROR, "Decision bad-request: IllegalArgumentException", e);
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
    } catch (final RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Decision handler internal error", e);
      writeJson(exchange, 500, JsonBodies.errorBody("internal"));
    } finally {
      AiTraceLogger.clearMatchId();
    }
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
