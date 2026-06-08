package games.strategy.triplea.ai.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.ai.pro.logging.ProLogCapture;
import games.strategy.triplea.attachments.RulesAttachment;
import games.strategy.triplea.delegate.GameDataTestUtil;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Regression test for #2940: AI empty transports congregate at an unload destination (e.g., near
 * Alaska after an amphibious assault) and never return home to reload.
 *
 * <p>Root cause (confirmed by this test): the "Move remaining transports to safest territory"
 * fallback in {@link ProNonCombatMoveAi} picks the territory with minimum strengthDifference. When
 * every reachable territory is equally threatened (all strengthDifference = 99999 because US
 * destroyers can reach them all within movement range), the selection is determined by iteration
 * order of a HashSet — which may return the transport's current position, leaving it stranded.
 *
 * <p>The primary "move to best loading territory" loop fails because US destroyers block both water
 * exits from 1 Sea Zone toward Japan (blocking {@code territoryCanMoveSeaUnitsThrough}).
 *
 * <p>G40 note: Japan's {@code rulesAttachment} initially lists sea zones 1-3, 8-12 as "disallowed,"
 * but a trigger clears this restriction when Japan goes to war. Tests that bypass the trigger
 * system (as this one does) must manually clear the restriction so the transport can move during
 * NCM — mirroring the in-game state after the war declaration trigger fires.
 *
 * <p>Fix: add a factory-proximity tiebreaker to the fallback so that, among equally dangerous
 * territories, the transport prefers the one with the shortest geographic water distance to the
 * nearest owned factory. This ensures the transport steps toward Japan each turn rather than
 * staying at the invasion staging zone indefinitely.
 */
public class ProEmptyTransportRepositionTest {

  private static final String JAPAN = "Japan";
  private static final String SEA_ZONE_1 = "1 Sea Zone";
  private static final String SEA_ZONE_8 = "8 Sea Zone";
  private static final String SEA_ZONE_2 = "2 Sea Zone";

  private GameData data;
  private GamePlayer japanese;
  private GamePlayer americans;

  @BeforeEach
  void setUp() {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    japanese = data.getPlayerList().getPlayerId("Japanese");
    americans = data.getPlayerList().getPlayerId("Americans");
  }

  /**
   * Core regression: an empty Japanese transport stranded at 1 Sea Zone (Alaska-adjacent, ~4 hops
   * from Japan's factory) must reposition toward Japan during NCM even when both direct exit routes
   * are blocked by US destroyers.
   *
   * <p>Setup:
   *
   * <ul>
   *   <li>Empty Japanese transport at 1 Sea Zone (post-invasion position near Alaska)
   *   <li>Japanese factory_major + infantry at Japan (valid loading target)
   *   <li>US destroyers at 8 Sea Zone and 2 Sea Zone — blocking both exits toward Japan via {@code
   *       territoryCanMoveSeaUnitsThrough}, forcing the primary repositioning loop to fail and the
   *       fallback to run
   *   <li>Japan's NCM movement restriction cleared (simulates the in-game trigger that fires when
   *       Japan goes to war, allowing sea units to enter the north Pacific sea zones)
   * </ul>
   *
   * <p>Expected (with fix): ProAI dispatches an NCM move for the transport — the factory-proximity
   * tiebreaker ensures it steps toward Japan rather than staying indefinitely.
   *
   * <p>Without the fix: the fallback's simple min-strengthDifference selection may select 1 Sea
   * Zone (current position) when all candidates are equally threatened, resulting in no dispatch.
   */
  @Test
  void emptyTransportRepositionsTowardJapanWhenDirectRoutesBlocked() {
    declareWar(japanese, americans);

    // Clear Japan's north-Pacific movement restriction.
    // In-game a trigger fires when Japan declares war, setting movementRestrictionTerritories to
    // empty so Japanese naval units can freely enter sea zones 1-3, 8-12.  Tests that bypass the
    // trigger system must replicate that state change manually.
    final RulesAttachment japRa = japanese.getRulesAttachment();
    if (japRa != null) {
      data.performChange(
          ChangeFactory.attachmentPropertyReset(japRa, "movementRestrictionTerritories"));
    }

    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Territory sz1 = data.getMap().getTerritoryOrThrow(SEA_ZONE_1);
    final Territory sz8 = data.getMap().getTerritoryOrThrow(SEA_ZONE_8);
    final Territory sz2 = data.getMap().getTerritoryOrThrow(SEA_ZONE_2);

    // Clear all units from every territory.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Empty Japanese transport at 1 Sea Zone — the stranded unit.
    final Unit japTransport = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz1, List.of(japTransport)));

    // Japanese factory_major + infantry at Japan — gives the NCM planner a valid loading target.
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(japan, List.of(japInfantry)));

    // US destroyers block both exits from 1 SZ toward Japan.
    // Route 1→8→7→6→Japan: blocked by 8 SZ DD.
    // Route 1→2→3→4→5→6→Japan: blocked by 2 SZ DD.
    // Both DDs can reach all sea zones within 2 hops of 1 SZ (their movement=2), so every
    // reachable sea zone has strengthDifference=99999 — the factory-proximity tiebreaker is
    // needed to break the tie in favour of zones closer to Japan.
    final Unit usDestroyer8 = GameDataTestUtil.destroyer(data).create(1, americans).get(0);
    final Unit usDestroyer2 = GameDataTestUtil.destroyer(data).create(1, americans).get(0);
    data.performChange(ChangeFactory.addUnits(sz8, List.of(usDestroyer8)));
    data.performChange(ChangeFactory.addUnits(sz2, List.of(usDestroyer2)));

    final List<MoveDescription> dispatched = runNonCombatMoveFor(japanese);

    // The transport must appear in at least one dispatched NCM move.
    // With the factory-proximity tiebreaker the fallback prefers a zone closer to Japan's factory
    // over the current zone (equal threat, equal or better distance, non-current wins).
    // Without the fix the fallback's hash-iteration order may leave the transport stranded at 1 SZ.
    final boolean transportMoved =
        dispatched.stream().anyMatch(md -> md.getUnits().contains(japTransport));

    assertThat(transportMoved)
        .as(
            "Empty Japanese transport at 1 Sea Zone should reposition during NCM. "
                + "Primary route is blocked (US DDs at 8 SZ and 2 SZ); "
                + "factory-proximity tiebreaker in the fallback must step it toward Japan. "
                + "Dispatched moves: "
                + dispatched)
        .isTrue();
  }

  /**
   * Counter-regression: a transport that is ALREADY adjacent to Japan's factory zone (5 SZ, one hop
   * from 6 SZ which is Japan's coastal sea zone) must still be repositioned toward Japan when 6 SZ
   * is free (primary loop succeeds). This ensures the fix does not regress the normal (unblocked)
   * case.
   */
  @Test
  void emptyTransportAdjacentToJapanMovesToCoastalSeaZone() {
    declareWar(japanese, americans);
    data.getSequence().setRoundAndStep(2, "Non Combat Move", japanese);

    final Territory japan = data.getMap().getTerritoryOrThrow(JAPAN);
    final Territory sz5 = data.getMap().getTerritoryOrThrow("5 Sea Zone");
    final Territory sz6 = data.getMap().getTerritoryOrThrow("6 Sea Zone");

    // Clear all units.
    for (final Territory t : data.getMap().getTerritories()) {
      data.performChange(ChangeFactory.removeUnits(t, t.getUnits()));
    }

    // Transport at 5 SZ (1 hop from 6 SZ = Japan's coastal zone).
    final Unit japTransport = GameDataTestUtil.transport(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(sz5, List.of(japTransport)));

    // Factory + infantry at Japan.
    final UnitType factoryMajor = data.getUnitTypeList().getUnitType("factory_major").orElseThrow();
    data.performChange(ChangeFactory.addUnits(japan, factoryMajor.create(1, japanese)));
    final Unit japInfantry = GameDataTestUtil.infantry(data).create(1, japanese).get(0);
    data.performChange(ChangeFactory.addUnits(japan, List.of(japInfantry)));

    // No enemy units — the primary repositioning loop should succeed.
    final List<MoveDescription> dispatched = runNonCombatMoveFor(japanese);

    // Transport should move to 6 Sea Zone (Japan's adjacent coastal zone).
    final boolean transportAtSz6 =
        dispatched.stream()
            .anyMatch(
                md -> md.getUnits().contains(japTransport) && sz6.equals(md.getRoute().getEnd()));

    assertThat(transportAtSz6)
        .as(
            "Transport at 5 SZ should move to 6 SZ (Japan's coastal zone) with no blocking"
                + " enemies — primary repositioning loop should succeed. Dispatched: "
                + dispatched)
        .isTrue();
  }

  // ---- helpers ----

  private void declareWar(final GamePlayer p1, final GamePlayer p2) {
    data.performChange(
        ChangeFactory.relationshipChange(
            p1,
            p2,
            data.getRelationshipTracker().getRelationshipType(p1, p2),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private List<MoveDescription> runNonCombatMoveFor(final GamePlayer player) {
    final ProAi proAi = new ProAi("Test " + player.getName(), player.getName() + " AI");
    final PlayerBridge playerBridgeMock = Mockito.mock(PlayerBridge.class);
    proAi.initialize(playerBridgeMock, player);
    when(playerBridgeMock.getGameData()).thenReturn(data);

    final List<MoveDescription> dispatched = new ArrayList<>();
    final IMoveDelegate moveDelegate = Mockito.mock(IMoveDelegate.class);
    when(moveDelegate.performMove(any()))
        .then(
            inv -> {
              dispatched.add(inv.getArgument(0));
              return Optional.empty();
            });

    try (ProLogCapture ignored = new ProLogCapture()) {
      proAi.invokeNonCombatMoveForSidecar(moveDelegate, data, player);
    }
    return dispatched;
  }
}
