package org.triplea.ai.sidecar.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.CanonicalGameDataRegistry;
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

/**
 * Dispatches {@code POST /decision} calls to a kind-specific {@link DecisionExecutor}.
 *
 * <p>The request body is deserialised through the polymorphic {@link DecisionRequest} sealed
 * interface (Jackson discriminator {@code kind}). The concrete request type drives a pattern switch
 * that picks the matching executor; the returned {@link DecisionPlan} is wrapped in a {@code
 * {"status":"ready","plan":{...}}} envelope and sent as the 200 response body.
 *
 * <p>Known decision kinds: {@code purchase} and {@code noncombat-move}. Any other kind returns 400
 * via Jackson deserialisation failure (unknown discriminator value).
 *
 * <p>The handler holds no per-request state. Each call resolves the {@link CanonicalGameData} for
 * the request's {@code gameDataKey} out of the injected {@link CanonicalGameDataRegistry}, then
 * constructs its own {@link games.strategy.engine.data.GameData} clone and {@link
 * games.strategy.triplea.ai.pro.ProAi} — see executor implementations.
 */
public final class DecisionHandler implements HttpHandler {

  private static final System.Logger LOG = System.getLogger(DecisionHandler.class.getName());

  private final CanonicalGameDataRegistry registry;
  private final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor;
  private final DecisionExecutor<NoncombatMoveRequest, NoncombatMovePlan> noncombatMoveExecutor;

  /** Production constructor — wires all executors. */
  public DecisionHandler(final CanonicalGameDataRegistry registry) {
    this(registry, new PurchaseExecutor(), new NoncombatMoveExecutor());
  }

  /** Test constructor — accepts executor stubs so handler logic can be exercised in isolation. */
  public DecisionHandler(
      final CanonicalGameDataRegistry registry,
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
    if (!"/decision".equals(exchange.getRequestURI().getPath())) {
      writeJson(exchange, 404, JsonBodies.errorBody("not-found"));
      return;
    }

    final long startNanos = System.nanoTime();
    final String requestId = firstHeader(exchange, "X-Request-Id");

    final String body =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    final DecisionRequest request;
    try {
      request = JsonBodies.readValue(body, DecisionRequest.class);
    } catch (final IOException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "[sidecar] decision validation failed exClass={0} message={1} bodyBytes={2}",
          new Object[] {e.getClass().getSimpleName(), e.getMessage(), body.length()});
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
      return;
    }
    if (request == null) {
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
      return;
    }

    AiTraceLogger.setMatchId(matchIdFor(request));
    AiTraceLogger.setRequestId(requestId != null ? requestId : AiTraceLogger.SENTINEL);
    setRoundAndPlayer(request);
    try {
      switch (request) {
        case PurchaseRequest pr -> {
          final CanonicalGameData canonical = registry.forKey(pr.state().gameDataKey());
          final PurchasePlan plan = purchaseExecutor.execute(canonical, pr);
          writeJsonTimed(exchange, 200, JsonBodies.readyBody(plan), startNanos);
        }
        case NoncombatMoveRequest nm -> {
          final CanonicalGameData canonical = registry.forKey(nm.state().gameDataKey());
          final NoncombatMovePlan plan = noncombatMoveExecutor.execute(canonical, nm);
          writeJsonTimed(exchange, 200, JsonBodies.readyBody(plan), startNanos);
        }
        case OtherOffensiveRequest oo ->
            writeJsonTimed(
                exchange,
                501,
                JsonBodies.errorBodyWithKind("not-implemented", oo.kind()),
                startNanos);
      }
    } catch (final IllegalArgumentException e) {
      LOG.log(System.Logger.Level.ERROR, "Decision bad-request: IllegalArgumentException", e);
      writeJson(exchange, 400, JsonBodies.errorBody("bad-request"));
    } catch (final RuntimeException e) {
      LOG.log(System.Logger.Level.ERROR, "Decision handler internal error", e);
      writeJson(exchange, 500, JsonBodies.errorBody("internal"));
    } finally {
      AiTraceLogger.clearAll();
    }
  }

  /** Returns the first value of the named request header, or {@code null} if absent. */
  private static String firstHeader(final HttpExchange exchange, final String name) {
    final var values = exchange.getRequestHeaders().get(name);
    return (values != null && !values.isEmpty()) ? values.get(0) : null;
  }

  /**
   * Extract the bgio match ID for log correlation. Returns the real match ID when the wire envelope
   * includes one; falls back to {@link AiTraceLogger#SENTINEL} when absent. Never synthesises a
   * composite like {@code player:r{round}} — that would break {@code grep 'match=<bgioId>'} across
   * server, bot-worker, and sidecar logs.
   */
  private static String matchIdFor(final DecisionRequest request) {
    final String id =
        switch (request) {
          case PurchaseRequest pr -> pr.matchId();
          case NoncombatMoveRequest nm -> nm.matchId();
          case OtherOffensiveRequest ignored -> null;
        };
    return (id != null && !id.isBlank()) ? id : AiTraceLogger.SENTINEL;
  }

  private static void setRoundAndPlayer(final DecisionRequest request) {
    switch (request) {
      case PurchaseRequest pr -> {
        AiTraceLogger.setRound(pr.state().round());
        AiTraceLogger.setPlayer(pr.state().currentPlayer());
      }
      case NoncombatMoveRequest nm -> {
        AiTraceLogger.setRound(nm.state().round());
        AiTraceLogger.setPlayer(nm.state().currentPlayer());
      }
      case OtherOffensiveRequest ignored -> {
        // OtherOffensiveRequest has no state — leave round/player as sentinel.
      }
    }
  }

  /**
   * Write a JSON response and include {@code X-Decision-Time-Ms} so the 0.6 orchestrator can
   * calibrate the shared-pool P bound (p99 latency < 90s purchase budget per decision D1).
   */
  private static void writeJsonTimed(
      final HttpExchange ex, final int status, final String body, final long startNanos)
      throws IOException {
    final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    ex.getResponseHeaders().add("X-Decision-Time-Ms", Long.toString(elapsedMs));
    writeJson(ex, status, body);
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
