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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
    final CapturingHandler capture = attachHandler();
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
      detachHandler(capture);
    }

    assertThat(capture.formatted())
        .anySatisfy(
            line ->
                assertThat(line)
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
                    .contains("reason=overridden-from-default"));
  }

  private static CapturingHandler attachHandler() {
    final Logger jul = Logger.getLogger(AiTraceLogger.class.getName());
    final CapturingHandler h = new CapturingHandler();
    h.setLevel(Level.ALL);
    jul.setLevel(Level.ALL);
    jul.addHandler(h);
    jul.setUseParentHandlers(false);
    return h;
  }

  private static void detachHandler(final CapturingHandler h) {
    Logger.getLogger(AiTraceLogger.class.getName()).removeHandler(h);
  }

  /**
   * JUL handler that captures every {@link LogRecord} so tests can assert on the formatted message.
   * System.Logger delegates to JUL by default in JDK, so attaching here intercepts {@code LOG.log}
   * calls in {@link AiTraceLogger}.
   */
  private static final class CapturingHandler extends Handler {
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(final LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {}

    List<String> formatted() {
      return records.stream()
          .map(
              r ->
                  r.getParameters() == null
                      ? r.getMessage()
                      : java.text.MessageFormat.format(r.getMessage(), r.getParameters()))
          .toList();
    }
  }
}
