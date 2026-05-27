package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Phase 3A defense-floor tests (map-room#2755 PR-C). The SVF biases US offensive force toward the
 * targeted theater; the floor is the safety net that prevents the same gradient from stripping the
 * unfavored theater bare. These tests pin both the lookup table (who has a floor under which flag)
 * and the {@code wouldViolateFloor} accounting (counts player-owned units only, debits
 * already-assigned).
 */
class ProDefenseFloorTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    proData = proDataFor(data, americans);
  }

  @Test
  void compute_returnsEmpty_forNonUsPlayer() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);

    final Map<Territory, Integer> floor = ProDefenseFloor.compute(proData, germans);

    assertThat(floor)
        .as("Spec §2 Phase 1: SVF + floor are US-only; other players must not be affected")
        .isEmpty();
  }

  @Test
  void compute_kgf_populatesPacificHoldings() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);

    final Map<Territory, Integer> floor = ProDefenseFloor.compute(proData, americans);

    assertThat(floor)
        .as("KGF must protect the Pacific holdings the Berlin gradient would otherwise strip")
        .containsKey(data.getMap().getTerritoryOrThrow("Hawaiian Islands"))
        .containsKey(data.getMap().getTerritoryOrThrow("Western United States"))
        .containsKey(data.getMap().getTerritoryOrThrow("Queensland"))
        .containsKey(data.getMap().getTerritoryOrThrow("New South Wales"));
    assertThat(floor.get(data.getMap().getTerritoryOrThrow("Hawaiian Islands")))
        .as("Hawaii is the headline case — Japan invades within 2-3 turns if stripped")
        .isPositive();
  }

  @Test
  void compute_kjf_populatesAtlanticHoldings_andDropsPacificFloors() {
    proData.setAiTheaterPriority(AiTheaterPriority.KJF);

    final Map<Territory, Integer> floor = ProDefenseFloor.compute(proData, americans);

    assertThat(floor)
        .as("KJF mirrors the floor onto the Atlantic side — the unfavored theater")
        .containsKey(data.getMap().getTerritoryOrThrow("Eastern United States"))
        .containsKey(data.getMap().getTerritoryOrThrow("Central United States"));
    assertThat(floor)
        .as("KJF must NOT keep KGF's Pacific floors — the unfavored theater flips")
        .doesNotContainKey(data.getMap().getTerritoryOrThrow("Hawaiian Islands"))
        .doesNotContainKey(data.getMap().getTerritoryOrThrow("Queensland"));
  }

  @Test
  void wouldViolateFloor_returnsFalse_forTerritoryWithoutAFloor() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setDFloor(ProDefenseFloor.compute(proData, americans));
    final Territory eastUs = data.getMap().getTerritoryOrThrow("Eastern United States");
    final Unit anyUnit = eastUs.getUnits().iterator().next();

    final boolean violates = ProDefenseFloor.wouldViolateFloor(proData, eastUs, anyUnit, List.of());

    assertThat(violates)
        .as("Eastern US has no KGF floor — moving units out must be unconstrained")
        .isFalse();
  }

  @Test
  void wouldViolateFloor_returnsTrue_whenPullingBelowFloor() {
    // Hawaii's KGF floor is 2. The starting 1940 Global garrison is small — pull enough units
    // via alreadyAssigned to leave only 1, then the next pull must violate.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setDFloor(ProDefenseFloor.compute(proData, americans));
    final Territory hawaii = data.getMap().getTerritoryOrThrow("Hawaiian Islands");
    final int floor = proData.getDFloor().get(hawaii);
    final List<Unit> usUnitsAtHawaii =
        hawaii.getUnits().stream().filter(u -> u.getOwner().equals(americans)).toList();
    // Need at least floor+1 US units to drive the boundary case; if starting garrison is too
    // small the assertion below is the canary that says "tune the floor or the test data".
    assertThat(usUnitsAtHawaii.size())
        .as("Hawaii must start with >floor US units or the floor is unreachable")
        .isGreaterThan(floor);

    final List<Unit> alreadyAssigned = usUnitsAtHawaii.subList(0, usUnitsAtHawaii.size() - floor);
    final Unit nextPull = usUnitsAtHawaii.get(usUnitsAtHawaii.size() - floor);

    final boolean violates =
        ProDefenseFloor.wouldViolateFloor(
            proData, hawaii, nextPull, new HashSet<>(alreadyAssigned));

    assertThat(violates)
        .as("Pulling one past the floor must violate — NCM should skip this assignment")
        .isTrue();
  }

  @Test
  void wouldViolateFloor_returnsFalse_whenStillAboveFloor() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setDFloor(ProDefenseFloor.compute(proData, americans));
    final Territory hawaii = data.getMap().getTerritoryOrThrow("Hawaiian Islands");
    final List<Unit> usUnitsAtHawaii =
        hawaii.getUnits().stream().filter(u -> u.getOwner().equals(americans)).toList();

    // Pull only one unit — this must leave more than the floor still home.
    final Unit firstPull = usUnitsAtHawaii.get(0);

    final boolean violates =
        ProDefenseFloor.wouldViolateFloor(proData, hawaii, firstPull, List.of());

    assertThat(violates)
        .as("A single pull from a full garrison must not violate the floor")
        .isFalse();
  }

  @Test
  void wouldViolateFloor_returnsFalse_whenSourceNotOwnedByPlayer() {
    // Even when a player has units staging on someone else's territory, the floor is a
    // defense-the-homeland concept — it shouldn't fire on someone else's land. Use a German
    // capital so ownership clearly differs from the unit owner.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setDFloor(
        Map.of(data.getMap().getTerritoryOrThrow("Germany"), 5)); // force a fake floor
    final Territory berlin = data.getMap().getTerritoryOrThrow("Germany");
    final Unit anyGermanUnit = berlin.getUnits().iterator().next();

    final boolean violates =
        ProDefenseFloor.wouldViolateFloor(proData, berlin, anyGermanUnit, List.of());

    assertThat(violates).as("Floor only protects garrisons on the AI player's own land").isFalse();
  }

  @Test
  void wouldViolateFloor_returnsFalse_forNullSource() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setDFloor(ProDefenseFloor.compute(proData, americans));
    final Unit anyUnit =
        data.getMap().getTerritoryOrThrow("Eastern United States").getUnits().iterator().next();

    final boolean violates = ProDefenseFloor.wouldViolateFloor(proData, null, anyUnit, List.of());

    assertThat(violates)
        .as("A unit that isn't tracked back to a source (null) cannot violate any floor")
        .isFalse();
  }

  // --- helpers ---

  private static ProData proDataFor(final GameData gameData, final GamePlayer player)
      throws Exception {
    final ProData p = new ProData();
    final Field dataField = ProData.class.getDeclaredField("data");
    dataField.setAccessible(true);
    dataField.set(p, gameData);
    final Field playerField = ProData.class.getDeclaredField("player");
    playerField.setAccessible(true);
    playerField.set(p, player);
    return p;
  }
}
