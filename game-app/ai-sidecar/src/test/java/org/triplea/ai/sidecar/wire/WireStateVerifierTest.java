package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.settings.ClientSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

class WireStateVerifierTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  // ---- log capture ----

  private final List<LogRecord> captured = new ArrayList<>();
  private final Handler captureHandler =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          // Force supplier evaluation so getMessage() returns the formatted string.
          record.getMessage();
          captured.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };
  private Logger verifierLogger;

  @BeforeEach
  void installLogCapture() {
    captured.clear();
    verifierLogger = Logger.getLogger(WireStateVerifier.class.getName());
    verifierLogger.addHandler(captureHandler);
    verifierLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void removeLogCapture() {
    verifierLogger.removeHandler(captureHandler);
  }

  private List<String> warnings() {
    return captured.stream()
        .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
        .map(
            r ->
                r.getMessage() != null
                    ? r.getMessage()
                    : r.getParameters() != null
                        ? String.format(
                            r.getMessage() == null ? "%s" : r.getMessage(), r.getParameters())
                        : "(null)")
        .collect(Collectors.toList());
  }

  private List<String> infos() {
    return captured.stream()
        .filter(r -> r.getLevel().equals(Level.INFO))
        .map(LogRecord::getMessage)
        .collect(Collectors.toList());
  }

  // ---- helpers ----

  private GameData fresh() {
    return canonical.cloneForSession();
  }

  private ConcurrentMap<String, UUID> freshIdMap() {
    return new ConcurrentHashMap<>();
  }

  /** Apply a wire state, then run the verifier independently on the same data. */
  private void applyAndVerify(
      final GameData gd, final WireState wire, final ConcurrentMap<String, UUID> idMap) {
    WireStateApplier.apply(gd, wire, idMap);
    // The verifier is called inside apply(), so logs are already captured.
  }

  // ---- tests ----

  @Test
  void happyPath_noMismatchesAfterCleanApply() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of())),
            List.of(new WirePlayer("Germans", 40, List.of(), false)),
            1,
            "purchase",
            "Germans",
            List.of());
    applyAndVerify(gd, wire, idMap);

    assertThat(warnings()).isEmpty();
    assertThat(infos()).anyMatch(m -> m.contains("apply-verify") && m.contains("mismatches=0"));
  }

  @Test
  void ownerDrift_detectedAfterMutatingTerritoryOwner() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    // Wire says Germany is owned by Russians; after apply it will be Russians.
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Russians", List.of())),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    // Capture state after a CLEAN apply — should be 0 drifts.
    final long cleanWarnings = warnings().stream().filter(w -> w.contains("apply-drift")).count();
    assertThat(cleanWarnings).isZero();

    // Now post-apply mutate the territory owner back to Germans to simulate drift.
    captured.clear();
    gd.performChange(
        ChangeFactory.changeOwner(
            gd.getMap().getTerritoryOrThrow("Germany"), gd.getPlayerList().getPlayerId("Germans")));
    // Run verifier directly (not via apply) to check the mutated state.
    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=owner")
                    && w.contains("territory=Germany")
                    && w.contains("expected=Russians")
                    && w.contains("actual=Germans"));
  }

  @Test
  void unitHitsDrift_detectedAfterMutatingUnitHits() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final String uid = "u-inf-1";
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit(uid, "infantry", 1, 0)))),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Mutate the unit's hits to 0 to simulate drift from wire's expected 1.
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final UUID uuid = idMap.get(uid);
    final Unit unit =
        germany.getUnits().stream().filter(u -> u.getId().equals(uuid)).findFirst().orElseThrow();
    final org.triplea.java.collections.IntegerMap<Unit> hitMap =
        new org.triplea.java.collections.IntegerMap<>();
    hitMap.put(unit, 0);
    gd.performChange(ChangeFactory.unitsHit(hitMap, List.of(germany)));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=unit-hits")
                    && w.contains("unitId=" + uid)
                    && w.contains("expected=1")
                    && w.contains("actual=0"));
  }

  @Test
  void unitAlreadyMovedDrift_detectedAfterMutatingAlreadyMoved() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final String uid = "u-arm-1";
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit(uid, "armour", 0, 2)))),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Reset alreadyMoved to 0 to simulate drift from wire's expected 2.
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final UUID uuid = idMap.get(uid);
    final Unit unit =
        germany.getUnits().stream().filter(u -> u.getId().equals(uuid)).findFirst().orElseThrow();
    gd.performChange(
        ChangeFactory.unitPropertyChange(
            unit, java.math.BigDecimal.ZERO, Unit.PropertyName.ALREADY_MOVED));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=unit-already-moved")
                    && w.contains("unitId=" + uid)
                    && w.contains("expected=2")
                    && w.contains("actual=0"));
  }

  @Test
  void pusDrift_detectedAfterMutatingPlayerPus() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Germans", 42, List.of(), false)),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Adjust Germans' PUs to 30 to simulate drift from wire's expected 42.
    final var pus = gd.getResourceList().getResourceOrThrow("PUs");
    final var player = gd.getPlayerList().getPlayerId("Germans");
    final int delta = 30 - player.getResources().getQuantity(pus);
    gd.performChange(ChangeFactory.changeResourcesChange(player, pus, delta));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=player-pus")
                    && w.contains("player=Germans")
                    && w.contains("expected=42")
                    && w.contains("actual=30"));
  }

  @Test
  void relationshipDrift_detectedAfterMutatingRelationship() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    // Wire declares Germans-Russians as "war".
    final WireState wire =
        new WireState(
            List.of(),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of(new WireRelationship("Germans", "Russians", "war")));
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Mutate the relationship to "allied" to simulate drift.
    final var a = gd.getPlayerList().getPlayerId("Germans");
    final var b = gd.getPlayerList().getPlayerId("Russians");
    final var alliedType =
        gd.getRelationshipTypeList().getAllRelationshipTypes().stream()
            .filter(
                r -> "allied".equalsIgnoreCase(r.getRelationshipTypeAttachment().getArcheType()))
            .findFirst()
            .orElseThrow();
    gd.getRelationshipTracker().setRelationship(a, b, alliedType);

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=relationship")
                    && w.contains("pair=Germans-Russians")
                    && w.contains("expected=war"));
  }

  @Test
  void conqueredThisTurnDrift_detectedAfterRemovingFromBattleTracker() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    // Wire says Poland was conquered this turn.
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Poland", "Germans", List.of(), true)),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Remove Poland from the BattleTracker conquered set to simulate drift.
    final Territory poland = gd.getMap().getTerritoryOrThrow("Poland");
    gd.getBattleDelegate().getBattleTracker().getConquered().remove(poland);

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=conquered-this-turn")
                    && w.contains("territory=Poland")
                    && w.contains("expected=true")
                    && w.contains("actual=false"));
  }

  @Test
  void unitTerritoryDrift_detectedAfterMovingUnitToWrongTerritory() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final String uid = "u-inf-1";
    // Wire places the infantry in Germany.
    final WireState wire =
        new WireState(
            List.of(
                new WireTerritory(
                    "Germany", "Germans", List.of(new WireUnit(uid, "infantry", 0, 0)))),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Move the unit to France to simulate misplacement drift.
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Territory france = gd.getMap().getTerritoryOrThrow("France");
    final Unit unit =
        germany.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get(uid)))
            .findFirst()
            .orElseThrow();
    gd.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.moveUnits(
            germany, france, List.of(unit)));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=unit-territory")
                    && w.contains("unitId=" + uid)
                    && w.contains("expected=Germany")
                    && w.contains("actual=France"));
  }

  @Test
  void transportedByDrift_detectedAfterClearingLink() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireUnit transport =
        WireUnit.of(
            "u-trn-1", "transport", 0, 0, 0, "Germans", null, false, false, false, false, 0);
    final WireUnit infantry =
        WireUnit.of(
            "u-inf-1", "infantry", 0, 0, 0, "Germans", "u-trn-1", false, false, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("112 Sea Zone", "Germans", List.of(transport, infantry))),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Clear the transportedBy link to simulate drift.
    final Territory seaZone = gd.getMap().getTerritoryOrThrow("112 Sea Zone");
    final UUID infantryUuid = idMap.get("u-inf-1");
    final Unit infantryUnit =
        seaZone.getUnits().stream()
            .filter(u -> u.getId().equals(infantryUuid))
            .findFirst()
            .orElseThrow();
    gd.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.unitPropertyChange(
            infantryUnit, null, Unit.PropertyName.TRANSPORTED_BY));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=unit-transport-by")
                    && w.contains("unitId=u-inf-1")
                    && w.contains("expected=u-trn-1")
                    && w.contains("actual=null"));
  }

  @Test
  void submergedFlagDrift_detectedAfterClearingSubmerged() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final String uid = "u-sub-1";
    final WireUnit sub =
        WireUnit.of(uid, "submarine", 0, 0, 0, "Germans", null, true, false, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("112 Sea Zone", "Germans", List.of(sub))),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Clear submerged flag to simulate drift.
    final Territory seaZone = gd.getMap().getTerritoryOrThrow("112 Sea Zone");
    final Unit unit =
        seaZone.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get(uid)))
            .findFirst()
            .orElseThrow();
    gd.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.unitPropertyChange(
            unit, false, Unit.PropertyName.SUBMERGED));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=unit-submerged")
                    && w.contains("unitId=" + uid)
                    && w.contains("expected=true")
                    && w.contains("actual=false"));
  }

  @Test
  void wasInCombatFlagDrift_detectedAfterClearingWasInCombat() {
    final GameData gd = fresh();
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final String uid = "u-inf-1";
    final WireUnit infantry =
        WireUnit.of(uid, "infantry", 0, 0, 0, "Germans", null, false, true, false, false, 0);
    final WireState wire =
        new WireState(
            List.of(new WireTerritory("Germany", "Germans", List.of(infantry))),
            List.of(),
            1,
            "purchase",
            "Germans",
            List.of());
    WireStateApplier.apply(gd, wire, idMap);
    captured.clear();

    // Clear wasInCombat to simulate drift.
    final Territory germany = gd.getMap().getTerritoryOrThrow("Germany");
    final Unit unit =
        germany.getUnits().stream()
            .filter(u -> u.getId().equals(idMap.get(uid)))
            .findFirst()
            .orElseThrow();
    gd.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.unitPropertyChange(
            unit, false, Unit.PropertyName.WAS_IN_COMBAT));

    WireStateVerifier.verifyApply(gd, wire, idMap);

    assertThat(warnings())
        .anyMatch(
            w ->
                w.contains("apply-drift")
                    && w.contains("kind=unit-wasInCombat")
                    && w.contains("unitId=" + uid)
                    && w.contains("expected=true")
                    && w.contains("actual=false"));
  }

  @Test
  void summaryLine_alwaysEmitted() {
    final GameData gd = fresh();
    final WireState wire = new WireState(List.of(), List.of(), 1, "purchase", "Germans", List.of());
    WireStateVerifier.verifyApply(gd, wire, freshIdMap());
    assertThat(infos()).anyMatch(m -> m.contains("apply-verify"));
  }
}
