package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.ai.pro.simulate.ProDummyDelegateBridge;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.settings.ClientSetting;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.AiTraceLogger;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.wire.WireRelationship;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireStateApplier;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

/**
 * map-room#4709: Global 1940 restricts where the neutral Americans and Japanese may move, and lifts
 * the restriction by a trigger once they are at war. The stateless sidecar never fired that
 * trigger, so a US move through 88 Sea Zone was refused all game, silently.
 *
 * <p>Each case runs a one-step sea move through a real {@link RecordingMoveDelegate}, the same path
 * {@link NoncombatMoveExecutor} uses. A captured move is a move that reaches the wire.
 */
class MovementRestrictionLift4709Test {

  private static final String G40 = "ww2global40_2nd_edition";
  private static CanonicalGameData global40;
  private static CanonicalGameData global42;
  private static CanonicalGameData anniversary41;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    global40 = CanonicalGameData.load(G40 + ".xml");
    global42 = CanonicalGameData.load("ww2global42_2nd_edition.xml");
    anniversary41 = CanonicalGameData.load("WW2v3-1941.xml");
  }

  @Test
  void usAtWarSails89To88() {
    final Attempt attempt =
        sail(global40, G40, "Americans", "89 Sea Zone", "88 Sea Zone", war("Americans", "Germans"));

    assertThat(attempt.error()).isEmpty();
    assertThat(attempt.captured()).hasSize(1);
  }

  @Test
  void usAtPeaceIsStillRefused88AndTheDropIsLogged() {
    final List<LogRecord> warnings = new ArrayList<>();
    final Handler capture =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
              warnings.add(record);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    final Logger traceLogger = Logger.getLogger(AiTraceLogger.class.getName());
    traceLogger.addHandler(capture);
    try {
      final Attempt attempt =
          sail(global40, G40, "Americans", "89 Sea Zone", "88 Sea Zone", List.of());

      assertThat(attempt.error()).contains("Cannot move to restricted territories");
      assertThat(attempt.captured()).isEmpty();
    } finally {
      traceLogger.removeHandler(capture);
    }

    assertThat(warnings)
        .singleElement()
        .satisfies(
            r ->
                assertThat(MessageFormat.format(r.getMessage(), r.getParameters()))
                    .contains("kind=sidecar-move-dropped")
                    .contains("nation=Americans")
                    .contains("from=\"89 Sea Zone\" to=\"88 Sea Zone\"")
                    .contains("types=[destroyer×1]")
                    .contains("reason=\"Cannot move to restricted territories\""));
  }

  @Test
  void japanAtWarWithUsSailsIntoRestrictedZone() {
    final String[] leg = japaneseLegInto("8 Sea Zone");

    final Attempt attempt =
        sail(global40, G40, "Japanese", leg[0], leg[1], war("Japanese", "Americans"));

    assertThat(attempt.error()).isEmpty();
    assertThat(attempt.captured()).hasSize(1);
  }

  @Test
  void japanAtWarOnlyWithBritainIsStillRefused() {
    // The Japanese lift is war with the Americans specifically, not with any Allied power.
    final String[] leg = japaneseLegInto("8 Sea Zone");

    final Attempt attempt =
        sail(global40, G40, "Japanese", leg[0], leg[1], war("Japanese", "British"));

    assertThat(attempt.error()).contains("Cannot move to restricted territories");
    assertThat(attempt.captured()).isEmpty();
  }

  @Test
  void global1942HasNoRestrictionBeforeOrAfterWar() {
    final String g42 = "ww2global42_2nd_edition";
    assertThat(sail(global42, g42, "Americans", "89 Sea Zone", "88 Sea Zone", List.of()).error())
        .isEmpty();
    assertThat(
            sail(
                    global42,
                    g42,
                    "Americans",
                    "89 Sea Zone",
                    "88 Sea Zone",
                    war("Americans", "Germans"))
                .error())
        .isEmpty();
  }

  @Test
  void anniversaryChinaRestrictionStillBinds() {
    final GameData data = anniversary41.cloneForSession();
    final GamePlayer chinese = data.getPlayerList().getPlayerId("Chinese");
    final String[] before =
        RulesAttachment.get(chinese, "rulesAttachment").getMovementRestrictionTerritories();

    WireStateApplier.apply(
        data,
        new WireState(
            List.of(),
            List.of(),
            3,
            "nonCombatMove",
            "Americans",
            war("Americans", "Germans", "Americans", "Japanese"),
            "WW2v3-1941"),
        new ConcurrentHashMap<>());

    assertThat(RulesAttachment.get(chinese, "rulesAttachment").getMovementRestrictionTerritories())
        .isNotEmpty()
        .containsExactly(before);
  }

  // --- helpers -------------------------------------------------------------------------------

  private record Attempt(
      Optional<String> error, List<RecordingMoveDelegate.CapturedMove> captured) {}

  /** Applies a one-destroyer board, then sails it {@code from → to} in noncombat move. */
  private static Attempt sail(
      final CanonicalGameData canonical,
      final String gameDataKey,
      final String nation,
      final String from,
      final String to,
      final List<WireRelationship> relationships) {
    final GameData data = canonical.cloneForSession();
    final ConcurrentMap<String, UUID> unitIdMap = new ConcurrentHashMap<>();
    WireStateApplier.apply(
        data,
        new WireState(
            List.of(
                new WireTerritory(
                    from, nation, List.of(new WireUnit("dd-1", "destroyer", 0, 0, 0, nation)))),
            List.of(),
            3,
            "nonCombatMove",
            nation,
            relationships,
            gameDataKey),
        unitIdMap);

    final GamePlayer player = data.getPlayerList().getPlayerId(nation);
    final ProAi proAi = ExecutorSupport.newProAi("restriction-4709", nation, null);
    ExecutorSupport.initializeProAi(proAi, data, player);
    final RecordingMoveDelegate recorder = new RecordingMoveDelegate(proAi);
    recorder.initialize("move", "Move");
    recorder.setDelegateBridgeAndPlayer(new ProDummyDelegateBridge(proAi, player, data));

    final Territory start = data.getMap().getTerritoryOrThrow(from);
    final Territory end = data.getMap().getTerritoryOrThrow(to);
    final Unit destroyer =
        start.getUnits().stream()
            .filter(u -> u.getId().equals(unitIdMap.get("dd-1")))
            .findFirst()
            .orElseThrow();
    final Optional<String> error =
        recorder.performMove(new MoveDescription(List.of(destroyer), new Route(start, end)));
    return new Attempt(error, recorder.captured());
  }

  /** A sea zone next to {@code restricted} that is itself outside Japan's restricted list. */
  private static String[] japaneseLegInto(final String restricted) {
    final GameData data = global40.cloneForSession();
    final List<String> japanRestricted =
        List.of(
            RulesAttachment.get(data.getPlayerList().getPlayerId("Japanese"), "rulesAttachment")
                .getMovementRestrictionTerritories());
    assertThat(japanRestricted).contains(restricted);
    final Territory into = data.getMap().getTerritoryOrThrow(restricted);
    final String from =
        data.getMap().getNeighbors(into).stream()
            .filter(Territory::isWater)
            .map(Territory::getName)
            .filter(name -> !japanRestricted.contains(name))
            .sorted()
            .findFirst()
            .orElseThrow();
    return new String[] {from, restricted};
  }

  private static List<WireRelationship> war(final String... pairs) {
    return Stream.iterate(0, i -> i < pairs.length, i -> i + 2)
        .map(i -> new WireRelationship(pairs[i], pairs[i + 1], "war"))
        .toList();
  }
}
