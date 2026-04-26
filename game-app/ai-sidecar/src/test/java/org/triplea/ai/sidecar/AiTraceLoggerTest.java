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
}
