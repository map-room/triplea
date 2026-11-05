package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.ai.pro.util.ProUtils;
import games.strategy.triplea.ai.pro.util.UkEconomySplit;
import games.strategy.triplea.settings.ClientSetting;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * map-room#4511. The input is a real Global 1940 board serialized by Map Room's own {@code
 * toWireState} (the byte-identical fixture is guarded on the TS side by {@code
 * wire-g1940-uk-capitals-4511.test.ts}), applied by the real {@link WireStateApplier}. What ProAI's
 * capital helpers say about Calcutta and London on that board has to match Map Room's rule: each UK
 * economy has its own capital, income and treasury (§15).
 *
 * <p>Fixture numbers: Europe economy 28 PU, Pacific economy 17 PU (the wire's merged {@code pus} is
 * 45).
 */
class UkPacificCapitalsWireTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static CanonicalGameData canonical;
  private static WireState wire;
  private static JsonNode wireTree;

  @BeforeAll
  static void load() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
    try (InputStream in =
        UkPacificCapitalsWireTest.class
            .getClassLoader()
            .getResourceAsStream("wire-contract/wire-state-g1940-uk-economies.json")) {
      wireTree = MAPPER.readTree(in);
    }
    wire = MAPPER.treeToValue(wireTree, WireState.class);
  }

  private static GameData applied() {
    final GameData gd = canonical.cloneForSession();
    WireStateApplier.apply(gd, wire, new ConcurrentHashMap<>());
    return gd;
  }

  @Test
  void calcuttaStopsOnlyThePacificEconomy() {
    final GameData gd = applied();
    final Territory india = gd.getMap().getTerritoryOrThrow("India");
    assertThat(ProUtils.captureStopsIncome(india, gd.getMap())).isTrue();
    assertThat(ProUtils.capitalValue(india, gd)).isEqualTo(17.0);
    assertThat(ProUtils.capturedTreasury(india, gd)).isEqualTo(17);
  }

  @Test
  void londonStopsOnlyTheEuropeEconomy() {
    final GameData gd = applied();
    final Territory london = gd.getMap().getTerritoryOrThrow("United Kingdom");
    assertThat(ProUtils.captureStopsIncome(london, gd.getMap())).isTrue();
    assertThat(ProUtils.capitalValue(london, gd)).isEqualTo(28.0);
    assertThat(ProUtils.capturedTreasury(london, gd)).isEqualTo(28);
  }

  /** The fixture's British player with its pools (and merged total) rewritten. */
  private static WireState withPools(final int europe, final int pacific) throws Exception {
    final JsonNode copy = wireTree.deepCopy();
    for (final JsonNode p : copy.get("players")) {
      if ("British".equals(p.get("playerId").asText())) {
        ((ObjectNode) p).put("europePus", europe).put("pacificPus", pacific);
        ((ObjectNode) p).put("pus", europe + pacific);
      }
    }
    return MAPPER.treeToValue(copy, WireState.class);
  }

  /** Income and treasury differ here, so a value read from the wrong one cannot pass. */
  @Test
  void treasuryIsTheEconomysPoolNotItsIncomeOrTheMergedTotal() throws Exception {
    final GameData gd = canonical.cloneForSession();
    WireStateApplier.apply(gd, withPools(50, 9), new ConcurrentHashMap<>());
    final Territory india = gd.getMap().getTerritoryOrThrow("India");
    final Territory london = gd.getMap().getTerritoryOrThrow("United Kingdom");
    assertThat(ProUtils.capturedTreasury(india, gd)).isEqualTo(9);
    assertThat(ProUtils.capturedTreasury(london, gd)).isEqualTo(50);
    assertThat(ProUtils.capitalValue(india, gd)).isEqualTo(17.0);
    assertThat(ProUtils.capitalValue(london, gd)).isEqualTo(28.0);
  }

  @Test
  void liberatingCalcuttaIsWorthThePacificEconomyNotZeroNorBoth() {
    final GameData gd = applied();
    final Territory india = gd.getMap().getTerritoryOrThrow("India");
    final var british = gd.getPlayerList().getPlayerId("British");
    // Japan holds Calcutta; the Pacific economy now collects from what is left of it.
    gd.performChange(ChangeFactory.changeOwner(india, gd.getPlayerList().getPlayerId("Japanese")));
    final double pacificWithoutIndia = ProUtils.liberationValue(india, british, gd);
    assertThat(pacificWithoutIndia).isPositive().isLessThan(17.0);
    assertThat(ProUtils.liberatedCapitalOwner(india, british, gd)).isPresent();
  }

  @Test
  void aCapitalHeldByAnEnemyStopsNothing() {
    final GameData gd = applied();
    final Territory india = gd.getMap().getTerritoryOrThrow("India");
    gd.performChange(ChangeFactory.changeOwner(india, gd.getPlayerList().getPlayerId("Japanese")));
    assertThat(ProUtils.captureStopsIncome(india, gd.getMap())).isFalse();
    assertThat(ProUtils.capturedTreasury(india, gd)).isZero();
  }

  /** Control: a board with no pools on the wire has no split and behaves as upstream did. */
  @Test
  void noPoolsOnTheWireMeansNoSplit() throws Exception {
    final JsonNode copy = wireTree.deepCopy();
    for (final JsonNode p : copy.get("players")) {
      ((ObjectNode) p).remove(List.of("europePus", "pacificPus"));
    }
    final GameData gd = canonical.cloneForSession();
    WireStateApplier.apply(
        gd, MAPPER.treeToValue(copy, WireState.class), new ConcurrentHashMap<>());
    assertThat(UkEconomySplit.of(gd)).isEmpty();
    final Territory london = gd.getMap().getTerritoryOrThrow("United Kingdom");
    assertThat(ProUtils.capitalValue(london, gd)).isEqualTo(45.0);
  }
}
