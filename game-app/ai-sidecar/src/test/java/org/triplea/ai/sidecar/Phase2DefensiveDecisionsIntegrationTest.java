package org.triplea.ai.sidecar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.triplea.settings.ClientSetting;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.http.HttpService;

/**
 * End-to-end integration test for Phase 2 defensive decisions.
 *
 * <p>Starts a real {@link HttpService} via {@link SidecarMain#startForTest}, creates a Session
 * through the real {@code POST /session} endpoint, then drives all three defensive decision kinds
 * through {@code POST /session/{id}/decision} against the real ProAi executors (no stubs).
 *
 * <p>WireState reflects the Map Room production path: {@code currentPlayer} is the attacker's
 * nation, and the session is created for the defending nation being queried.
 */
class Phase2DefensiveDecisionsIntegrationTest {

  private static HttpService svc;
  private static HttpClient client;
  private static String base;
  private static String auth;
  private static String sessionId;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Attacker's nation — set as currentPlayer in WireState (defender's turn via activePlayers). */
  private static final String ATTACKER = "Russians";

  /** Defender's nation — the session nation being queried for defensive decisions. */
  private static final String DEFENDER = "Germans";

  @BeforeAll
  static void startServiceAndCreateSession() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());

    svc = SidecarMain.startForTest(Map.of("SIDECAR_BIND_HOST", "127.0.0.1", "SIDECAR_PORT", "0"));
    final int port = svc.boundPort();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    base = "http://127.0.0.1:" + port;
    auth = "Bearer dev-token";

    // Create a session for the DEFENDER — production path: defender is queried while the
    // attacker holds the top-level turn (ctx.currentPlayer = attacker) and the defender is
    // in an activePlayers stage.
    final String gameId = "integration-defensive";
    sessionId = gameId + ":" + DEFENDER;
    final HttpResponse<String> create =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/sessions"))
                .header("Authorization", auth)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"sessionId\":\""
                            + sessionId
                            + "\",\"gameId\":\""
                            + gameId
                            + "\",\"nation\":\""
                            + DEFENDER
                            + "\",\"seed\":42}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, create.statusCode(), "Session create must return 200");
  }

  @AfterAll
  static void stopService() {
    if (svc != null) {
      svc.stop();
    }
  }

  // ---------------------------------------------------------------------------
  // Test 1: selectCasualties
  //   POST /decision with kind=select-casualties for a mixed German stack being
  //   attacked by Russians on Germany. currentPlayer=Russians (attacker's turn).
  //   Expect 200 + SelectCasualtiesPlan with Map Room unit IDs (not UUIDs).
  // ---------------------------------------------------------------------------

  @Test
  void selectCasualties_mixedStack_returns200WithValidKilledIds() throws Exception {
    // WireState: attacker (Russians) holds the turn; Germany territory has German units plus the
    // Russian enemy infantry (all units referenced in the request must appear in WireState).
    // Field names follow WireTerritory (territoryId, owner, units) and WireUnit (unitId, unitType).
    final String wireState =
        "{"
            + "\"territories\":["
            + "{\"territoryId\":\"Germany\",\"owner\":\""
            + DEFENDER
            + "\",\"units\":["
            + "{\"unitId\":\"u-inf-1\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-art-1\",\"unitType\":\"artillery\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-tank-1\",\"unitType\":\"armour\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-rus-inf-1\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "]}"
            + "],"
            + "\"players\":[],"
            + "\"round\":1,"
            + "\"phase\":\"combat\","
            + "\"currentPlayer\":\""
            + ATTACKER
            + "\""
            + "}";

    final String selectFrom =
        "["
            + "{\"unitId\":\"u-inf-1\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-art-1\",\"unitType\":\"artillery\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-tank-1\",\"unitType\":\"armour\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "]";

    final String body =
        "{"
            + "\"kind\":\"select-casualties\","
            + "\"state\":"
            + wireState
            + ","
            + "\"battle\":{"
            + "\"battleId\":\"b-sc-integration\","
            + "\"territory\":\"Germany\","
            + "\"attackerNation\":\""
            + ATTACKER
            + "\","
            + "\"defenderNation\":\""
            + DEFENDER
            + "\","
            + "\"hitCount\":2,"
            + "\"selectFrom\":"
            + selectFrom
            + ","
            + "\"friendlyUnits\":"
            + selectFrom
            + ","
            + "\"enemyUnits\":["
            + "{\"unitId\":\"u-rus-inf-1\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "],"
            + "\"isAmphibious\":false,"
            + "\"amphibiousLandAttackers\":[],"
            + "\"defaultCasualties\":[\"u-inf-1\",\"u-art-1\"],"
            + "\"allowMultipleHitsPerUnit\":false"
            + "}"
            + "}";

    final HttpResponse<String> resp = postDecision(body);
    assertEquals(200, resp.statusCode(), "selectCasualties must return 200; body=" + resp.body());

    final JsonNode envelope = MAPPER.readTree(resp.body());
    assertEquals("ready", envelope.path("status").asText(), "envelope status must be 'ready'");
    final JsonNode plan = envelope.path("plan");
    assertTrue(plan.has("killed"), "Response must have 'killed' field");
    assertTrue(plan.has("damaged"), "Response must have 'damaged' field");
    assertTrue(plan.get("killed").isArray(), "'killed' must be an array");
    assertEquals(2, plan.get("killed").size(), "hitCount=2 so exactly 2 units must be killed");

    // All returned IDs must be Map Room IDs, not Java UUIDs.
    for (final JsonNode idNode : plan.get("killed")) {
      final String id = idNode.asText();
      assertTrue(
          id.equals("u-inf-1") || id.equals("u-art-1") || id.equals("u-tank-1"),
          "Killed id must be a Map Room id from the selectFrom list, got: " + id);
      assertNotUuid(id);
    }
  }

  // ---------------------------------------------------------------------------
  // Test 2: retreatQuery
  //   POST /decision with kind=retreat-or-press for Germans on Poland with legal
  //   retreat to Germany or Slovakia Hungary. currentPlayer=Russians (attacker).
  //   Expect 200 + RetreatPlan with retreatTo null (press) or one of the legal
  //   retreat territories.
  // ---------------------------------------------------------------------------

  @Test
  void retreatQuery_legalRetreats_returns200WithValidRetreatTo() throws Exception {
    final String wireState =
        "{"
            + "\"territories\":["
            + "{\"territoryId\":\"Poland\",\"owner\":\""
            + DEFENDER
            + "\",\"units\":["
            + "{\"unitId\":\"u-ger-inf-1\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-ger-inf-2\",\"unitType\":\"infantry\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-ger-art-1\",\"unitType\":\"artillery\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-ger-tank-1\",\"unitType\":\"armour\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "]}"
            + "],"
            + "\"players\":[],"
            + "\"round\":1,"
            + "\"phase\":\"combat\","
            + "\"currentPlayer\":\""
            + ATTACKER
            + "\""
            + "}";

    final String body =
        "{"
            + "\"kind\":\"retreat-or-press\","
            + "\"state\":"
            + wireState
            + ","
            + "\"battle\":{"
            + "  \"battleId\":\"b-retreat-integration\","
            + "  \"battleTerritory\":\"Poland\","
            + "  \"canSubmerge\":false,"
            + "  \"possibleRetreatTerritories\":[\"Germany\",\"Slovakia Hungary\"]"
            + "}"
            + "}";

    final HttpResponse<String> resp = postDecision(body);
    assertEquals(200, resp.statusCode(), "retreatQuery must return 200; body=" + resp.body());

    final JsonNode envelope = MAPPER.readTree(resp.body());
    assertEquals("ready", envelope.path("status").asText(), "envelope status must be 'ready'");
    final JsonNode plan = envelope.path("plan");
    assertTrue(plan.has("retreatTo"), "Response must have 'retreatTo' field");

    // retreatTo is null (press) or one of the legal retreat territories.
    if (!plan.get("retreatTo").isNull()) {
      final String retreatTo = plan.get("retreatTo").asText();
      assertTrue(
          retreatTo.equals("Germany") || retreatTo.equals("Slovakia Hungary"),
          "retreatTo must be one of the possible territories, got: " + retreatTo);
    }
  }

  // ---------------------------------------------------------------------------
  // Test 3: scramble
  //   POST /decision with kind=scramble for Germans defending 112 Sea Zone from
  //   an airbase at Western Germany. currentPlayer=British (attacker at sea).
  //   Expect 200 + ScramblePlan with scramblers map (may be empty if ProAi
  //   declines odds — must never be null and any IDs must be Map Room IDs).
  // ---------------------------------------------------------------------------

  @Test
  void scramble_airbaseAdjacentToSeaZone_returns200WithValidPlan() throws Exception {
    // Attacker is British attacking 112 Sea Zone; session is Germans (defender).
    final String wireState =
        "{"
            + "\"territories\":["
            + "{\"territoryId\":\"112 Sea Zone\",\"owner\":\""
            + DEFENDER
            + "\",\"units\":["
            + "{\"unitId\":\"u-uk-dd-1\",\"unitType\":\"destroyer\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-uk-dd-2\",\"unitType\":\"destroyer\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "]},"
            + "{\"territoryId\":\"Western Germany\",\"owner\":\""
            + DEFENDER
            + "\",\"units\":["
            + "{\"unitId\":\"u-ger-airfield\",\"unitType\":\"airfield\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-ger-ftr-1\",\"unitType\":\"fighter\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-ger-ftr-2\",\"unitType\":\"fighter\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "]}"
            + "],"
            + "\"players\":[],"
            + "\"round\":1,"
            + "\"phase\":\"combat\","
            + "\"currentPlayer\":\"British\""
            + "}";

    final String body =
        "{"
            + "\"kind\":\"scramble\","
            + "\"state\":"
            + wireState
            + ","
            + "\"battle\":{"
            + "  \"defendingTerritory\":\"112 Sea Zone\","
            + "\"possibleScramblers\":{"
            + "\"Western Germany\":{"
            + "\"maxCount\":1,"
            + "\"units\":["
            + "{\"unitId\":\"u-ger-ftr-1\",\"unitType\":\"fighter\",\"hitsTaken\":0,\"movesUsed\":0},"
            + "{\"unitId\":\"u-ger-ftr-2\",\"unitType\":\"fighter\",\"hitsTaken\":0,\"movesUsed\":0}"
            + "]"
            + "}"
            + "}"
            + "}"
            + "}";

    final HttpResponse<String> resp = postDecision(body);
    assertEquals(200, resp.statusCode(), "scramble must return 200; body=" + resp.body());

    final JsonNode envelope = MAPPER.readTree(resp.body());
    assertEquals("ready", envelope.path("status").asText(), "envelope status must be 'ready'");
    final JsonNode plan = envelope.path("plan");
    assertTrue(plan.has("scramblers"), "Response must have 'scramblers' field");
    assertTrue(plan.get("scramblers").isObject(), "'scramblers' must be an object/map");

    // If ProAi decided to scramble, validate the response structure.
    if (plan.get("scramblers").size() > 0) {
      assertTrue(
          plan.get("scramblers").has("Western Germany"),
          "Scramblers must be keyed by source territory 'Western Germany'");
      final JsonNode fighters = plan.get("scramblers").get("Western Germany");
      assertTrue(fighters.isArray(), "Scramblers for 'Western Germany' must be an array");
      for (final JsonNode idNode : fighters) {
        final String id = idNode.asText();
        assertTrue(
            id.equals("u-ger-ftr-1") || id.equals("u-ger-ftr-2"),
            "Scrambler id must be from the possibleScramblers list, got: " + id);
        assertNotUuid(id);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private HttpResponse<String> postDecision(final String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(base + "/session/" + sessionId + "/decision"))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String extractField(final String json, final String field) throws Exception {
    final JsonNode node = MAPPER.readTree(json);
    final JsonNode value = node.get(field);
    return value != null && !value.isNull() ? value.asText() : null;
  }

  private static void assertNotUuid(final String id) {
    assertTrue(
        !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        "ID must not be a raw Java UUID, got: " + id);
  }
}
