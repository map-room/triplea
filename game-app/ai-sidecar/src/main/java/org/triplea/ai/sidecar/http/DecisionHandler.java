package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.dto.CombatMovePlan;
import org.triplea.ai.sidecar.dto.CombatMoveRequest;
import org.triplea.ai.sidecar.dto.DecisionPlan;
import org.triplea.ai.sidecar.dto.DecisionRequest;
import org.triplea.ai.sidecar.dto.InterceptPlan;
import org.triplea.ai.sidecar.dto.InterceptRequest;
import org.triplea.ai.sidecar.dto.NoncombatMovePlan;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.OtherOffensiveRequest;
import org.triplea.ai.sidecar.dto.PlacePlan;
import org.triplea.ai.sidecar.dto.PlaceRequest;
import org.triplea.ai.sidecar.dto.PoliticsPlan;
import org.triplea.ai.sidecar.dto.PoliticsRequest;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.dto.RetreatPlan;
import org.triplea.ai.sidecar.dto.RetreatQueryRequest;
import org.triplea.ai.sidecar.dto.ScramblePlan;
import org.triplea.ai.sidecar.dto.ScrambleRequest;
import org.triplea.ai.sidecar.exec.CombatMoveExecutor;
import org.triplea.ai.sidecar.exec.DecisionExecutor;
import org.triplea.ai.sidecar.exec.InterceptExecutor;
import org.triplea.ai.sidecar.exec.NoncombatMoveExecutor;
import org.triplea.ai.sidecar.exec.PlaceExecutor;
import org.triplea.ai.sidecar.exec.PoliticsExecutor;
import org.triplea.ai.sidecar.exec.PurchaseExecutor;
import org.triplea.ai.sidecar.exec.RetreatQueryExecutor;
import org.triplea.ai.sidecar.exec.ScrambleExecutor;
import org.triplea.ai.sidecar.session.ProSessionSnapshotStore;
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
 * <p>All known decision kinds are wired to real executors; unknown kinds (unrecognised {@code kind}
 * discriminator values) return 400 via Jackson deserialization failure.
 */
public final class DecisionHandler implements HttpHandler {

  private static final System.Logger LOG = System.getLogger(DecisionHandler.class.getName());

  private final SessionRegistry registry;
  private final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor;
  private final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor;
  private final DecisionExecutor<InterceptRequest, InterceptPlan> interceptExecutor;
  private final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor;
  private final DecisionExecutor<PoliticsRequest, PoliticsPlan> politicsExecutor;
  private final DecisionExecutor<CombatMoveRequest, CombatMovePlan> combatMoveExecutor;
  private final DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> noncombatMoveExecutor;
  private final DecisionExecutor<PlaceRequest, PlacePlan> placeExecutor;

  /** Production constructor — wires all executors. */
  public DecisionHandler(final SessionRegistry registry) {
    this(
        registry,
        new RetreatQueryExecutor(),
        new ScrambleExecutor(),
        new InterceptExecutor(),
        new PurchaseExecutor(registry.snapshotStore()),
        new PoliticsExecutor(registry.snapshotStore()),
        new CombatMoveExecutor(registry.snapshotStore()),
        new NoncombatMoveExecutor(registry.snapshotStore()),
        new PlaceExecutor(registry.snapshotStore()));
  }

  /**
   * Test constructor — accepts a snapshot store and executor stubs so handler logic can be
   * exercised in isolation.
   */
  public DecisionHandler(
      final SessionRegistry registry,
      final ProSessionSnapshotStore snapshotStore,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor) {
    this(
        registry,
        retreatQueryExecutor,
        scrambleExecutor,
        new InterceptExecutor(),
        new PurchaseExecutor(snapshotStore),
        new PoliticsExecutor(snapshotStore),
        new CombatMoveExecutor(snapshotStore),
        new NoncombatMoveExecutor(snapshotStore),
        new PlaceExecutor(snapshotStore));
  }

  /**
   * Test constructor — accepts executor stubs so handler logic can be exercised in isolation.
   * Politics, combat-move, noncombat-move, and place default to stubs that throw {@link
   * AssertionError}.
   */
  public DecisionHandler(
      final SessionRegistry registry,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor) {
    this(
        registry,
        retreatQueryExecutor,
        scrambleExecutor,
        new InterceptExecutor(),
        purchaseExecutor,
        (session, req) -> {
          throw new AssertionError("PoliticsExecutor was called unexpectedly");
        },
        (session, req) -> {
          throw new AssertionError("CombatMoveExecutor was called unexpectedly");
        },
        (session, req) -> {
          throw new AssertionError("NoncombatMoveExecutor was called unexpectedly");
        },
        (session, req) -> {
          throw new AssertionError("PlaceExecutor was called unexpectedly");
        });
  }

  /**
   * Backward-compat test constructor used by tests written before intercept was upgraded to a real
   * ProAi TUV-swing implementation. InterceptExecutor defaults to its production implementation;
   * callers that need to stub it should use the full constructor instead.
   */
  public DecisionHandler(
      final SessionRegistry registry,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor,
      final DecisionExecutor<PoliticsRequest, PoliticsPlan> politicsExecutor,
      final DecisionExecutor<CombatMoveRequest, CombatMovePlan> combatMoveExecutor,
      final DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> noncombatMoveExecutor,
      final DecisionExecutor<PlaceRequest, PlacePlan> placeExecutor) {
    this(
        registry,
        retreatQueryExecutor,
        scrambleExecutor,
        new InterceptExecutor(),
        purchaseExecutor,
        politicsExecutor,
        combatMoveExecutor,
        noncombatMoveExecutor,
        placeExecutor);
  }

  /** Test constructor — full control over all executors. */
  public DecisionHandler(
      final SessionRegistry registry,
      final DecisionExecutor<RetreatQueryRequest, RetreatPlan> retreatQueryExecutor,
      final DecisionExecutor<ScrambleRequest, ScramblePlan> scrambleExecutor,
      final DecisionExecutor<InterceptRequest, InterceptPlan> interceptExecutor,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor,
      final DecisionExecutor<PoliticsRequest, PoliticsPlan> politicsExecutor,
      final DecisionExecutor<CombatMoveRequest, CombatMovePlan> combatMoveExecutor,
      final DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> noncombatMoveExecutor,
      final DecisionExecutor<PlaceRequest, PlacePlan> placeExecutor) {
    this.registry = registry;
    this.retreatQueryExecutor = retreatQueryExecutor;
    this.scrambleExecutor = scrambleExecutor;
    this.interceptExecutor = interceptExecutor;
    this.purchaseExecutor = purchaseExecutor;
    this.politicsExecutor = politicsExecutor;
    this.combatMoveExecutor = combatMoveExecutor;
    this.noncombatMoveExecutor = noncombatMoveExecutor;
    this.placeExecutor = placeExecutor;
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
      // Structured WARN per map-room#2305 (matches SessionLifecycleHandler.handleUpdate
      // pattern). The decision endpoint's error body envelope (DecisionError) is a
      // wire-contract type — it currently has no detail field, so we keep returning
      // the opaque 400 here; the structured log gives the server-side diagnostic and
      // the bot's response-body capture (#2306) remains useful for the response shape
      // it does carry. Extending DecisionError with a `message` field is a separate
      // coordinated TS+Java change and is out of scope for #2305.
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

    // Bind the matchID for this request thread so AiTraceLogger emits include
    // matchID=<id> on every per-order line. Required for the Loki/Promtail design
    // (#1968): {matchID="X"} queries must surface every per-order trace for the
    // match. Clear in finally so a thread-pool worker doesn't leak a stale matchID
    // into the next request.
    AiTraceLogger.setMatchId(session.get().key().gameId());
    try {
      switch (request) {
        case RetreatQueryRequest rq -> {
          final RetreatPlan plan = retreatQueryExecutor.execute(session.get(), rq);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case ScrambleRequest sr -> {
          final ScramblePlan plan = scrambleExecutor.execute(session.get(), sr);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case InterceptRequest ir -> {
          final InterceptPlan plan = interceptExecutor.execute(session.get(), ir);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case PurchaseRequest pr -> {
          final PurchasePlan plan = purchaseExecutor.execute(session.get(), pr);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case PoliticsRequest pol -> {
          final PoliticsPlan plan = politicsExecutor.execute(session.get(), pol);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case CombatMoveRequest cm -> {
          final CombatMovePlan plan = combatMoveExecutor.execute(session.get(), cm);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case NoncombatMoveRequest nm -> {
          final NoncombatMovePlan plan = noncombatMoveExecutor.execute(session.get(), nm);
          writeJson(exchange, 200, JsonBodies.readyBody(plan));
        }
        case PlaceRequest pr -> {
          final PlacePlan plan = placeExecutor.execute(session.get(), pr);
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
