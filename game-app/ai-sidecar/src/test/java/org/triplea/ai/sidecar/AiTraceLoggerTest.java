package org.triplea.ai.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.settings.ClientSetting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Unit tests for {@link AiTraceLogger} helpers. The full {@link AiTraceLogger#logCapturedMove} path
 * is exercised by integration tests in the executor suites (which construct real {@code
 * MoveDescription} instances); this file focuses on the pure-helper logic that can be verified
 * without a full TripleA game engine.
 */
class AiTraceLoggerTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  @Test
  void unitWireIds_emptyCollection_returnsEmptyString() {
    assertThat(AiTraceLogger.unitWireIds(List.of(), new HashMap<>())).isEmpty();
  }

  @Test
  void unitWireIds_mappedUnits_emitsCommaSeparatedWireIds() {
    final var gd = canonical.cloneForSession();
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final List<Unit> units = new ArrayList<>();
    final Map<UUID, String> uuidToWireId = new HashMap<>();
    for (int i = 0; i < 3; i++) {
      final Unit u = new Unit(UUID.randomUUID(), infantry, germans, gd);
      units.add(u);
      uuidToWireId.put(u.getId(), "u" + (100 + i));
    }

    assertThat(AiTraceLogger.unitWireIds(units, uuidToWireId)).isEqualTo("u100,u101,u102");
  }

  @Test
  void unitWireIds_unmappedUnit_fallsBackToUuidPrefix() {
    final var gd = canonical.cloneForSession();
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final UUID orphanId = UUID.randomUUID();
    final Unit orphan = new Unit(orphanId, infantry, germans, gd);

    final String result = AiTraceLogger.unitWireIds(List.of(orphan), new HashMap<>());

    assertThat(result).isEqualTo("uuid:" + orphanId);
  }

  @Test
  void unitTypeCounts_groupsByType() {
    final var gd = canonical.cloneForSession();
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final UnitType armour = gd.getUnitTypeList().getUnitType("armour").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final List<Unit> units =
        List.of(
            new Unit(UUID.randomUUID(), infantry, germans, gd),
            new Unit(UUID.randomUUID(), infantry, germans, gd),
            new Unit(UUID.randomUUID(), armour, germans, gd));

    assertThat(AiTraceLogger.unitTypeCounts(units)).isEqualTo("infantry×2,armour×1");
  }

  // ---------------------------------------------------------------------------
  // matchID context (#2004)
  //
  // The ThreadLocal-based matchID context backs every per-order [AI-TRACE] line
  // emitted by sidecar executors. The integration assertion (matchID actually
  // appears in the log line) is exercised by the executor suites; here we cover
  // the per-thread isolation, fallback, and clear semantics that DecisionHandler
  // relies on for safe thread-pool reuse.
  // ---------------------------------------------------------------------------

  @AfterEach
  void clearMatchIdContext() {
    AiTraceLogger.clearMatchId();
  }

  @Test
  void currentMatchId_unset_returnsUnknown() {
    AiTraceLogger.clearMatchId();
    assertThat(AiTraceLogger.currentMatchId()).isEqualTo("unknown");
  }

  @Test
  void setAndClearMatchId_roundTrip() {
    AiTraceLogger.setMatchId("match-abc-123");
    assertThat(AiTraceLogger.currentMatchId()).isEqualTo("match-abc-123");
    AiTraceLogger.clearMatchId();
    assertThat(AiTraceLogger.currentMatchId()).isEqualTo("unknown");
  }

  @Test
  void matchId_isolatedAcrossThreads() throws Exception {
    AiTraceLogger.setMatchId("main-thread-match");

    final java.util.concurrent.atomic.AtomicReference<String> seenInWorker =
        new java.util.concurrent.atomic.AtomicReference<>();
    final Thread worker =
        new Thread(
            () -> {
              // Worker has no inherited matchID — must see "unknown".
              seenInWorker.set(AiTraceLogger.currentMatchId());
              AiTraceLogger.setMatchId("worker-thread-match");
            });
    worker.start();
    worker.join();

    assertThat(seenInWorker.get()).isEqualTo("unknown");
    // Main thread context was never touched by worker's setMatchId — still its own value.
    assertThat(AiTraceLogger.currentMatchId()).isEqualTo("main-thread-match");
  }

  // ---------------------------------------------------------------------------
  // casualty-decision rationale (#2101)
  //
  // logCasualtyDecision is the v1 lever for AI-stuck triage. Tests cover:
  //   - the coarse reason-tag computation (default-applied vs overridden)
  //   - the full emitted line shape, captured via a JUL handler attached to
  //     the underlying AiTraceLogger logger (System.Logger delegates to JUL).
  // ---------------------------------------------------------------------------

  @Test
  void casualtyReason_pickedEqualsDefault_returnsDefaultApplied() {
    final var gd = canonical.cloneForSession();
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final Unit u1 = new Unit(UUID.randomUUID(), infantry, germans, gd);
    final Unit u2 = new Unit(UUID.randomUUID(), infantry, germans, gd);

    // Same set, same order.
    assertThat(AiTraceLogger.casualtyReason(List.of(u1, u2), List.of(u1, u2)))
        .isEqualTo("default-applied");
    // Same set, reordered (ProAi may reorder; we compare by UUID set).
    assertThat(AiTraceLogger.casualtyReason(List.of(u2, u1), List.of(u1, u2)))
        .isEqualTo("default-applied");
  }

  @Test
  void casualtyReason_pickedDiffersFromDefault_returnsOverridden() {
    final var gd = canonical.cloneForSession();
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final UnitType armour = gd.getUnitTypeList().getUnitType("armour").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final Unit inf = new Unit(UUID.randomUUID(), infantry, germans, gd);
    final Unit tank = new Unit(UUID.randomUUID(), armour, germans, gd);

    // Different unit picked.
    assertThat(AiTraceLogger.casualtyReason(List.of(tank), List.of(inf)))
        .isEqualTo("overridden-from-default");
    // Different size.
    assertThat(AiTraceLogger.casualtyReason(List.of(inf, tank), List.of(inf)))
        .isEqualTo("overridden-from-default");
  }

  @Test
  void logCasualtyDecision_emitsLineWithExpectedKeysAndMatchId() {
    final var gd = canonical.cloneForSession();
    final UnitType infantry = gd.getUnitTypeList().getUnitType("infantry").orElseThrow();
    final UnitType armour = gd.getUnitTypeList().getUnitType("armour").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final Unit inf1 = new Unit(UUID.randomUUID(), infantry, germans, gd);
    final Unit inf2 = new Unit(UUID.randomUUID(), infantry, germans, gd);
    final Unit tank = new Unit(UUID.randomUUID(), armour, germans, gd);
    final Map<UUID, String> uuidToWireId = new HashMap<>();
    uuidToWireId.put(inf1.getId(), "u-inf-1");
    uuidToWireId.put(inf2.getId(), "u-inf-2");
    uuidToWireId.put(tank.getId(), "u-tank-1");

    AiTraceLogger.setMatchId("match-cas-001");
    final AiTraceCapture cap = AiTraceCapture.attach();
    try {
      AiTraceLogger.logCasualtyDecision(
          /* nation */ "Germans",
          /* battleId */ "b-france-1",
          /* territory */ "Western Europe",
          /* hitCount */ 2,
          /* considered */ List.of(inf1, inf2, tank),
          /* defaultCasualties */ List.of(inf1, inf2),
          /* picked */ List.of(inf1, tank),
          uuidToWireId);
    } finally {
      cap.detach();
    }

    assertThat(cap.formatted()).hasSize(1);
    assertThat(cap.formatted().get(0))
        .startsWith("[AI-TRACE] matchID=match-cas-001 side=sidecar nation=Germans")
        .contains("phase=battle kind=select-casualties")
        .contains("battleId=b-france-1")
        .contains("territory=\"Western Europe\"")
        .contains("hitCount=2")
        .contains("consideredIds=[u-inf-1,u-inf-2,u-tank-1]")
        .contains("consideredTypes=[infantry×2,armour×1]")
        .contains("pickedIds=[u-inf-1,u-tank-1]")
        .contains("pickedTypes=[infantry×1,armour×1]")
        .contains("defaultIds=[u-inf-1,u-inf-2]")
        .contains("reason=overridden-from-default");
  }

  // ---------------------------------------------------------------------------
  // retreat-decision rationale (#2103)
  // ---------------------------------------------------------------------------

  @Test
  void retreatReason_emptyCandidates_returnsNoOptions() {
    assertThat(AiTraceLogger.retreatReason(List.of(), null)).isEqualTo("no-options");
    assertThat(AiTraceLogger.retreatReason(List.of(), "Italy")).isEqualTo("no-options");
  }

  @Test
  void retreatReason_nullRetreatTo_returnsPress() {
    assertThat(AiTraceLogger.retreatReason(List.of("Italy", "Germany"), null)).isEqualTo("press");
  }

  @Test
  void retreatReason_chosenTerritory_returnsRetreat() {
    assertThat(AiTraceLogger.retreatReason(List.of("Italy", "Germany"), "Italy"))
        .isEqualTo("retreat");
  }

  @Test
  void logRetreatDecision_emitsLineWithExpectedKeysAndMatchId() {
    AiTraceLogger.setMatchId("match-ret-001");
    final AiTraceCapture cap = AiTraceCapture.attach();
    try {
      AiTraceLogger.logRetreatDecision(
          /* nation */ "Germans",
          /* battleId */ "b-france-1",
          /* territory */ "Western Europe",
          /* candidateTerritories */ List.of("Germany", "Italy"),
          /* retreatTo */ "Italy");
    } finally {
      cap.detach();
    }

    assertThat(cap.formatted()).hasSize(1);
    assertThat(cap.formatted().get(0))
        .startsWith("[AI-TRACE] matchID=match-ret-001 side=sidecar nation=Germans")
        .contains("phase=battle kind=retreat-decision")
        .contains("battleId=b-france-1")
        .contains("territory=\"Western Europe\"")
        .contains("candidates=[Germany,Italy]")
        .contains("retreatTo=Italy")
        .contains("reason=retreat");
  }

  @Test
  void logRetreatDecision_pressDecision_emitsRetreatToNullAndReasonPress() {
    AiTraceLogger.setMatchId("match-ret-002");
    final AiTraceCapture cap = AiTraceCapture.attach();
    try {
      AiTraceLogger.logRetreatDecision(
          "Germans", "b-france-2", "Western Europe", List.of("Germany"), null);
    } finally {
      cap.detach();
    }

    assertThat(cap.formatted()).hasSize(1);
    assertThat(cap.formatted().get(0)).contains("retreatTo=null").contains("reason=press");
  }

  @Test
  void logRetreatDecision_quotesTerritoryNamesContainingSpaces() {
    AiTraceLogger.setMatchId("match-ret-003");
    final AiTraceCapture cap = AiTraceCapture.attach();
    try {
      AiTraceLogger.logRetreatDecision(
          "Germans",
          "b-fr-3",
          "Western Europe",
          List.of("Eastern Europe", "Germany"),
          "Eastern Europe");
    } finally {
      cap.detach();
    }

    assertThat(cap.formatted()).hasSize(1);
    assertThat(cap.formatted().get(0))
        .contains("candidates=[\"Eastern Europe\",Germany]")
        .contains("retreatTo=\"Eastern Europe\"");
  }

  // ---------------------------------------------------------------------------
  // scramble-selection rationale (#2104)
  // ---------------------------------------------------------------------------

  @Test
  void scrambleReason_emptyCandidates_returnsNoCandidates() {
    final var gd = canonical.cloneForSession();
    assertThat(AiTraceLogger.scrambleReason(List.of(), List.of())).isEqualTo("no-candidates");
    // No-candidates wins even if picked is somehow non-empty (defensive guard).
    final UnitType fighter = gd.getUnitTypeList().getUnitType("fighter").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final Unit f = new Unit(UUID.randomUUID(), fighter, germans, gd);
    assertThat(AiTraceLogger.scrambleReason(List.of(), List.of(f))).isEqualTo("no-candidates");
  }

  @Test
  void scrambleReason_pickedSubsetOfCandidates_returnsPartialAllOrNone() {
    final var gd = canonical.cloneForSession();
    final UnitType fighter = gd.getUnitTypeList().getUnitType("fighter").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final Unit f1 = new Unit(UUID.randomUUID(), fighter, germans, gd);
    final Unit f2 = new Unit(UUID.randomUUID(), fighter, germans, gd);

    assertThat(AiTraceLogger.scrambleReason(List.of(f1, f2), List.of())).isEqualTo("none");
    assertThat(AiTraceLogger.scrambleReason(List.of(f1, f2), List.of(f1))).isEqualTo("partial");
    assertThat(AiTraceLogger.scrambleReason(List.of(f1, f2), List.of(f1, f2))).isEqualTo("all");
  }

  @Test
  void logScrambleDecision_emitsLineWithExpectedKeysAndMatchId() {
    final var gd = canonical.cloneForSession();
    final UnitType fighter = gd.getUnitTypeList().getUnitType("fighter").orElseThrow();
    final var germans = gd.getPlayerList().getPlayerId("Germans");
    final Unit f1 = new Unit(UUID.randomUUID(), fighter, germans, gd);
    final Unit f2 = new Unit(UUID.randomUUID(), fighter, germans, gd);
    final Map<UUID, String> uuidToWireId = new HashMap<>();
    uuidToWireId.put(f1.getId(), "u-fighter-1");
    uuidToWireId.put(f2.getId(), "u-fighter-2");

    AiTraceLogger.setMatchId("match-scr-001");
    final AiTraceCapture cap = AiTraceCapture.attach();
    try {
      AiTraceLogger.logScrambleDecision(
          /* nation */ "Germans",
          /* territory */ "112 Sea Zone",
          /* candidates */ List.of(f1, f2),
          /* picked */ List.of(f1),
          uuidToWireId);
    } finally {
      cap.detach();
    }

    assertThat(cap.formatted()).hasSize(1);
    assertThat(cap.formatted().get(0))
        .startsWith("[AI-TRACE] matchID=match-scr-001 side=sidecar nation=Germans")
        .contains("phase=battle kind=scramble-decision")
        .contains("territory=\"112 Sea Zone\"")
        .contains("candidatesIds=[u-fighter-1,u-fighter-2]")
        .contains("candidatesTypes=[fighter×2]")
        .contains("pickedIds=[u-fighter-1]")
        .contains("pickedTypes=[fighter×1]")
        .contains("reason=partial");
  }
}
