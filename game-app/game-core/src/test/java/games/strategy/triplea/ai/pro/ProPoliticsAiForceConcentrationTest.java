package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression/calibration test for map-room/map-room#2749: {@code ProPoliticsAi}'s war-declaration
 * formula had no force-concentration signal, so a blue-water power (no 1-turn-reachable enemy
 * territory, {@code attackPercentage=0}) stayed idle regardless of how much amphib capacity it had
 * massed — {@code warChance} was driven purely by the round ramp.
 *
 * <p>Covers both halves of the fix: {@link ProPoliticsAi#countIdleSeaTransportCapacity} (the signal
 * — real {@code GameData}, the issue's own repro scenario) and {@link
 * ProPoliticsAi#computeWarChance} (the formula — pure arithmetic, no {@code GameData} needed).
 */
class ProPoliticsAiForceConcentrationTest {

  private static final String SEA_ZONE_101 = "101 Sea Zone";
  private static final String EASTERN_US = "Eastern United States";

  private GameData data;
  private GamePlayer americans;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
  }

  // --- countIdleSeaTransportCapacity: the signal ---

  @Test
  void baselineTurnOneCapacityIsTheStartingBareTransportFleet() {
    // Americans start with 3 bare sea-transports (this ruleset's transport capacity is 11, not
    // vanilla's 2 — see ww2_g40_balanced.xml). Pinned exact value from the real starting setup,
    // not re-derived math, so a future map-data change surfaces here.
    assertThat(ProPoliticsAi.countIdleSeaTransportCapacity(data, americans)).isEqualTo(33.0);
  }

  @Test
  void atlanticBuildupRaisesIdleCapacityByExactlyTheAddedTransports() {
    final double baseline = ProPoliticsAi.countIdleSeaTransportCapacity(data, americans);

    // The issue's own scenario: +20 transports in 101 Sea Zone, +80 infantry in Eastern US.
    // 20 transports * 11 capacity = 220; infantry doesn't carry, so it shouldn't move the number.
    final Territory sz101 = data.getMap().getTerritoryOrThrow(SEA_ZONE_101);
    final Territory easternUs = data.getMap().getTerritoryOrThrow(EASTERN_US);
    data.performChange(
        ChangeFactory.addUnits(sz101, GameDataTestUtil.transport(data).create(20, americans)));
    data.performChange(
        ChangeFactory.addUnits(easternUs, GameDataTestUtil.infantry(data).create(80, americans)));

    assertThat(ProPoliticsAi.countIdleSeaTransportCapacity(data, americans))
        .as("baseline (%s) + 20 transports * 11 capacity, infantry excluded", baseline)
        .isEqualTo(baseline + 220.0)
        .isEqualTo(253.0);
  }

  @Test
  void combatTransportsAreExcludedDespiteNonTrivialTransportCapacity() {
    // Battleships/cruisers on this map are isCombatTransport=true with transportCapacity=4 —
    // real warships, not amphib carriers. A naive "can transport" predicate would count them.
    final double before = ProPoliticsAi.countIdleSeaTransportCapacity(data, americans);
    final Territory sz101 = data.getMap().getTerritoryOrThrow(SEA_ZONE_101);
    data.performChange(
        ChangeFactory.addUnits(sz101, GameDataTestUtil.battleship(data).create(1, americans)));

    assertThat(ProPoliticsAi.countIdleSeaTransportCapacity(data, americans))
        .as(
            "battleship's capacity=4 must not be added — it's a combat transport, not an amphib one")
        .isEqualTo(before);
  }

  @Test
  void transportAlreadyCarryingCargoIsExcludedFromIdleCapacity() {
    final Territory sz101 = data.getMap().getTerritoryOrThrow(SEA_ZONE_101);
    final Territory easternUs = data.getMap().getTerritoryOrThrow(EASTERN_US);
    final UnitType transportType = GameDataTestUtil.transport(data);
    final Unit transport = transportType.create(1, americans).get(0);
    final Unit infantry = GameDataTestUtil.infantry(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz101, List.of(transport)));
    data.performChange(ChangeFactory.addUnits(easternUs, List.of(infantry)));

    final double beforeLoading = ProPoliticsAi.countIdleSeaTransportCapacity(data, americans);

    // Load the infantry onto the transport: the cargo unit moves into the transport's own
    // territory and its transportedBy property is set — mirrors what the move delegate does.
    data.performChange(ChangeFactory.removeUnits(easternUs, List.of(infantry)));
    data.performChange(ChangeFactory.addUnits(sz101, List.of(infantry)));
    data.performChange(
        ChangeFactory.unitPropertyChange(infantry, transport, Unit.PropertyName.TRANSPORTED_BY));

    assertThat(ProPoliticsAi.countIdleSeaTransportCapacity(data, americans))
        .as("loaded transport's capacity (11) must drop out — it's committed to a move, not idle")
        .isEqualTo(beforeLoading - transportType.getUnitAttachment().getTransportCapacity());
  }

  // --- computeWarChance: the formula, pure arithmetic ---

  @Test
  void zeroForceBonusReproducesThePreFixFormulaExactly() {
    // forceBonus=0 must be a no-op: the fix must not change behavior for a player with no idle
    // amphib capacity. Reproduces the issue's own logged values for Americans vs Italians.
    assertThat(ProPoliticsAi.computeWarChance(1, 0.0, 0.0)).isEqualTo(0.0);
    assertThat(ProPoliticsAi.computeWarChance(2, 0.0, 0.0)).isCloseTo(0.05, within(1e-9));
    assertThat(ProPoliticsAi.computeWarChance(3, 0.0, 0.0)).isCloseTo(0.10, within(1e-9));
    assertThat(ProPoliticsAi.computeWarChance(8, 0.0, 0.0)).isCloseTo(0.35, within(1e-9));
  }

  @Test
  void divisor300DoesNotSaturateTheStartingFleetAtTurnOne() {
    // The issue's originally-suggested /30 divisor saturates warChance past 1.0 using just the
    // US's starting 3 transports (33 capacity / 30 = 1.1) — an immediate, undiscriminating war
    // declaration on turn 1, a worse failure mode than the bug it fixes. /300 must not do that.
    final double forceBonus = 33.0 / 300.0;
    assertThat(forceBonus).isCloseTo(0.11, within(0.005));
    assertThat(ProPoliticsAi.computeWarChance(1, 0.0, forceBonus))
        .as("turn 1, starting fleet only: small, non-saturating bonus")
        .isLessThan(0.2);
  }

  @Test
  void divisor300MakesTheMassedAtlanticFleetDominateByRoundFive() {
    // After the issue's described buildup (253 idle capacity), the force-concentration term
    // alone should push warChance past 1.0 well before the pre-fix round-ramp-only formula
    // would cross a meaningful probability (round ~7-8 per the issue).
    final double forceBonus = 253.0 / 300.0;
    assertThat(forceBonus).isCloseTo(0.8433, within(0.001));

    assertThat(ProPoliticsAi.computeWarChance(1, 0.0, forceBonus))
        .as("round 1, massed fleet, still no reachable target: already a strong signal")
        .isGreaterThan(0.8);
    assertThat(ProPoliticsAi.computeWarChance(5, 0.0, forceBonus))
        .as("round 5: force bonus alone crosses certainty, instead of waiting until round ~7-8")
        .isGreaterThanOrEqualTo(1.0);
  }
}
