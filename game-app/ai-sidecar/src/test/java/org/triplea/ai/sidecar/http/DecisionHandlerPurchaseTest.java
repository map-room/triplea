package org.triplea.ai.sidecar.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchaseOrder;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.exec.DecisionExecutor;
import org.triplea.ai.sidecar.session.Session;
import org.triplea.ai.sidecar.session.SessionKey;
import org.triplea.ai.sidecar.session.SessionRegistry;

/**
 * Tests that {@link DecisionHandler} correctly dispatches purchase decisions to {@link
 * org.triplea.ai.sidecar.exec.PurchaseExecutor} and wraps the result in the ready envelope.
 *
 * <p>Uses a stub purchase executor to keep this a wire-format / dispatch test (not a ProAi
 * integration test). The stub returns a fixed {@link PurchasePlan} with one buy and no repairs.
 */
class DecisionHandlerPurchaseTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  private static final String EMPTY_STATE =
      "\"state\":{\"territories\":[],\"players\":[],\"round\":1,"
          + "\"phase\":\"purchase\",\"currentPlayer\":\"Germans\"}";

  private static final String PURCHASE_BODY = "{\"kind\":\"purchase\"," + EMPTY_STATE + "}";

  private SessionRegistry newRegistry() {
    return new SessionRegistry(CanonicalGameData.load());
  }

  private Session newSession(final SessionRegistry registry) {
    return registry
        .createOrGet(
            new SessionKey("g-purchase-test", "Germans", 1), "g-purchase-test:Germans:r1", 42L)
        .session();
  }

  /**
   * Creates a {@link DecisionHandler} with stub defensive executors that throw on invocation, and
   * the supplied purchase executor stub.
   */
  private DecisionHandler handlerWithPurchaseStub(
      final SessionRegistry registry,
      final DecisionExecutor<PurchaseRequest, PurchasePlan> purchaseExecutor) {
    return new DecisionHandler(
        registry,
        (session, req) -> {
          throw new AssertionError("retreat must not be called");
        },
        (session, req) -> {
          throw new AssertionError("scramble must not be called");
        },
        purchaseExecutor);
  }

  // -------------------------------------------------------------------------
  // Happy path: purchase stub returns a plan → 200 + status=ready + plan.kind=purchase
  // -------------------------------------------------------------------------

  @Test
  void purchase_success_returns200WithReadyEnvelope() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);

    final PurchasePlan fixedPlan =
        new PurchasePlan(List.of(new PurchaseOrder("infantry", 3, null)), List.of(), List.of());

    final DecisionHandler h = handlerWithPurchaseStub(registry, (session, req) -> fixedPlan);

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", PURCHASE_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode(), "purchase decision must return 200");

    final JsonNode root = MAPPER.readTree(ex.responseBodyString());
    assertEquals("ready", root.path("status").asText(), "envelope status must be 'ready'");
    assertTrue(root.has("plan"), "envelope must have 'plan'");

    final JsonNode plan = root.path("plan");
    assertEquals("purchase", plan.path("kind").asText(), "plan.kind must be 'purchase'");
    assertTrue(plan.path("buys").isArray(), "plan.buys must be an array");
    assertTrue(plan.path("repairs").isArray(), "plan.repairs must be an array");
  }

  @Test
  void purchase_success_buysAndRepairsPresent() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);

    final PurchasePlan fixedPlan =
        new PurchasePlan(
            List.of(
                new PurchaseOrder("infantry", 2, null), new PurchaseOrder("artillery", 1, null)),
            List.of(),
            List.of());

    final DecisionHandler h = handlerWithPurchaseStub(registry, (session, req) -> fixedPlan);

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", PURCHASE_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final JsonNode plan = MAPPER.readTree(ex.responseBodyString()).path("plan");

    assertTrue(plan.path("buys").isArray(), "plan.buys must be array");
    assertTrue(plan.path("repairs").isArray(), "plan.repairs must be array");
    assertEquals(2, plan.path("buys").size(), "buys must contain 2 entries");
    assertEquals(0, plan.path("repairs").size(), "repairs must be empty");
  }

  @Test
  void purchase_emptyPlan_returns200WithEmptyArrays() throws Exception {
    final SessionRegistry registry = newRegistry();
    final Session s = newSession(registry);

    final PurchasePlan emptyPlan = new PurchasePlan(List.of(), List.of(), List.of());
    final DecisionHandler h = handlerWithPurchaseStub(registry, (session, req) -> emptyPlan);

    final FakeHttpExchange ex =
        new FakeHttpExchange("POST", "/session/" + s.sessionId() + "/decision", PURCHASE_BODY);
    h.handle(ex);

    assertEquals(200, ex.responseCode());
    final JsonNode plan = MAPPER.readTree(ex.responseBodyString()).path("plan");
    assertEquals("purchase", plan.path("kind").asText());
    assertEquals(0, plan.path("buys").size(), "empty buys must still be an array");
    assertEquals(0, plan.path("repairs").size(), "empty repairs must still be an array");
  }
}
