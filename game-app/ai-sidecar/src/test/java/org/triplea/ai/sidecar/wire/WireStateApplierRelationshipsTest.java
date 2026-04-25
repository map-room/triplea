package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.RelationshipType;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class WireStateApplierRelationshipsTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private GameData fresh() {
    return canonical.cloneForSession();
  }

  @Test
  void hydratesWarRelationship() {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");

    final WireState wire =
        new WireState(
            List.of(),
            List.of(),
            3,
            "combat-move",
            "Germans",
            List.of(new WireRelationship("Germans", "Russians", "war")));

    WireStateApplier.apply(data, wire, new ConcurrentHashMap<>());

    final RelationshipType actual =
        data.getRelationshipTracker().getRelationshipType(germans, russians);
    assertThat(actual.getRelationshipTypeAttachment().isWar()).isTrue();
  }

  @Test
  void hydratesAlliedRelationship() {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");

    final WireState wire =
        new WireState(
            List.of(),
            List.of(),
            3,
            "combat-move",
            "Germans",
            List.of(new WireRelationship("Germans", "Russians", "allied")));

    WireStateApplier.apply(data, wire, new ConcurrentHashMap<>());

    final RelationshipType actual =
        data.getRelationshipTracker().getRelationshipType(germans, russians);
    assertThat(actual.getRelationshipTypeAttachment().isAllied()).isTrue();
  }

  @Test
  void hydratesNeutralRelationship() {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");

    // Force war first so apply must actually flip back to neutral.
    final RelationshipType warType = firstByArcheType(data, "war");
    data.getRelationshipTracker().setRelationship(germans, russians, warType);

    final WireState wire =
        new WireState(
            List.of(),
            List.of(),
            3,
            "combat-move",
            "Germans",
            List.of(new WireRelationship("Germans", "Russians", "neutral")));

    WireStateApplier.apply(data, wire, new ConcurrentHashMap<>());

    final RelationshipType actual =
        data.getRelationshipTracker().getRelationshipType(germans, russians);
    assertThat(actual.getRelationshipTypeAttachment().isNeutral()).isTrue();
  }

  @Test
  void emptyRelationshipsListIsNoOp() {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    final RelationshipType before =
        data.getRelationshipTracker().getRelationshipType(germans, russians);

    final WireState wire = new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of());
    WireStateApplier.apply(data, wire, new ConcurrentHashMap<>());

    assertThat(data.getRelationshipTracker().getRelationshipType(germans, russians))
        .isEqualTo(before);
  }

  @Test
  void skipsUnknownPlayerGracefully() {
    // Defensive — TS side should filter, but applier shouldn't crash on stale fixtures.
    final GameData data = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of(new WireRelationship("Germans", "Mongolia", "war")));
    // Should NOT throw.
    WireStateApplier.apply(data, wire, new ConcurrentHashMap<>());
  }

  private static RelationshipType firstByArcheType(final GameData data, final String archeType) {
    return data.getRelationshipTypeList().getAllRelationshipTypes().stream()
        .filter(r -> archeType.equalsIgnoreCase(r.getRelationshipTypeAttachment().getArcheType()))
        .findFirst()
        .orElseThrow();
  }
}
