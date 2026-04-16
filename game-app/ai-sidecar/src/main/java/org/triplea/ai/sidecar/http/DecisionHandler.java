package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.triplea.ai.sidecar.dto.CombatMovePlan;
import org.triplea.ai.sidecar.dto.CombatMoveRequest;
import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionRequest;
import org.triplea.ai.sidecar.dto.OtherOffensiveRequest;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.RetreatPlan;
import org.triplea.ai.sidecar.dto.RetreatQueryRequest;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.dto.SelectCasualtiesPlan;
import org.triplea.ai.sidecar.dto.SelectCasualtiesRequest;
import org.triplea.ai.sidecar.exec.CombatMoveExecutor;
import org.triplea.ai.sidecar.exec.DecisionExecutor;
import org.triplea.ai.sidecar.exec.PurchaseExecutor;
import org.triplea.ai.sidecar.exec.RetreatQueryExecutor;
import org.triplea.ai.sidecar.exec.ScrambleExecutor;
import org.triplea.ai.sidecar.exec.SelectCasualtiesExecutor;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionRegistry;

/**
 * Dispatches {@code POST /session/{id}/decision} calls to a kind-specific {@link
 * DecisionExecutor}.
 *
 * <p>The request body is deserialised through the polymorphic {@link DecisionRequest} sealed
 * interface (Jackson discriminator {@code kind}). The concrete request type drives a pattern
 * switch that picks the matching executor; the returned {@link DecisionPlan} is wrapped in a
 * {@code {"status":"ready","plan":{...}}} envelope and sent as the 200 response body.
 *
 * <p>Offensive kinds not yet implemented (noncombat-move/place) return 501 with a
 * {@code {"status":"error","error":"not-implemented","kind":"<kind>"}} body.
 */
public final class DecisionHandler implements HttpHandler {

  private static final System.Logger LOG = System.getLogger(DecisionHandler.class.getName());

  private final SessionRegistry registry;
  private final DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan>
      selectCasualtiesExecutor;
  private final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor;
  private final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor;
  private final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor;
  private final DecisionExecutor<CombatMoveRequest, CombatMovePlan> combatMoveExecutor;

  /** Production constructor — wires the real Phase-2 and Phase-3 executors. */
  public DecisionHandler(final SessionRegistry registry) {
    this(
        registry,
        new SelectCasualtiesExecutor(),
        new RetreatQueryExecutor(),
        new ScrambleExecutor(),
        new PurchaseExecutor(registry.snapshotStore()),
        new CombatMoveExecutor(registry.snapshotStore()));
  }

  /**
   * Test constructor — accepts a snapshot store and executor stubs so handler logic can be
   * exercised in isolation.
   */
  public DecisionHandler(
      final SessionRegistry registry,
      final ProSessionSnapshotStore snapshotStore,
      final DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan> selectCasualtiesExecutor,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor) {
    this(
        registry,
        selectCasualtiesExecutor,
        retreatQueryExecutor,
        scrambleExecutor,
        new PurchaseExecutor(snapshotStore),
        new CombatMoveExecutor(snapshotStore));
  }

  /**
   * Test constructor — accepts executor stubs so handler logic can be exercised in isolation.
   * Combat-move defaults to a stub that throws {@link AssertionError}; use the 6-arg overload to
   * inject a real or custom combat-move executor.
   */
  public DecisionHandler(
      final SessionRegistry registry,
      final DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan> selectCasualtiesExecutor,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor) {
    this(
        registry,
        selectCasualtiesExecutor,
        retreatQueryExecutor,
        scrambleExecutor,
        purchaseExecutor,
        (session, req) -> {
          throw new AssertionError("CombatMoveExecutor was called unexpectedly");
        });
  }

  /** Test constructor — full control over all executors. */
  public DecisionHandler(
      final SessionRegistry registry,
      final DecisionExecutor<SelectCasualtiesRequest, SelectCasualtiesPlan> selectCasualtiesExecutor,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor,
      final DecisionExecutor<CombatMoveRequest, CombatMovePlan> combatMoveExecutor) {
    this.registry = registry;
    this.selectCasualtiesExecutor = selectCasualtiesExecutor;
    this.retreatQueryExecutor = retreatQueryExecutor;
    this.scrambleExecutor = scrambleExecutor;
    this.purchaseExecutor = purchaseExecutor;
    this.combatMoveExecutor = combatMoveExecutor;
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
      writeJson(exchange, 404, JsonBodies.errorBody("unknown-session"));
      return;
    }

    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final DecisionRequest request;
    try {
      request = JsonBodies.readValue(body, DecisionRequest.class);
    } catch (final IOException e) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
      return;
    }
    if (request == null) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
      return;
    }

    try {
      switch (request) {
        case SelectCasualtiesRequest sc -> {
          final SelectCasualtiesPlan plan = selectCasualtiesExecutor.execute(session.get(), sc);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case RetreatQueryRequest rq -> {
          final RetreatPlan plan = retreatQueryExecutor.execute(session.get(), rq);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case ScrambleRequest sr -> {
          final ScramblePlan plan = scrambleExecutor.execute(session.get(), sr);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case PurchaseRequest pr -> {
          final PurchasePlan plan = purchaseExecutor.execute(session.get(), pr);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case CombatMoveRequest cm -> {
          final CombatMovePlan plan = combatMoveExecutor.execute(session.get(), cm);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case OtherOffensiveRequest oo -> writeJson(
            exchange,
            501,
            JsonBodies.errorBodyWithKind("not-implemented", oo.kind()));
      }
    } catch (final IllegalArgumentException e) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
    } catch (final RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Decision handler internal error", e);
      writeJson(exchange, 500, JsonBodies.errorBody("internal"));
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
