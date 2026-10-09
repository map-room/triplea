package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.settings.ClientSetting;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireTerritory;
import org.triplea.ai.sidecar.wire.WireUnit;

/**
 * Positive companion to {@link ProNeutralStaging2745ReproTest}.
 *
 * <p>Round-1 British are already at war with Germany. With the setup Luftwaffe left in place,
 * France cannot be held and the units stay home. This board removes only those planes — the German
 * land army and navy stay — and noncombat then moves onto France, which borders Western Germany.
 * The same four moves are produced on triplea main, before the sea-value and tie-break changes.
 */
class ProAtWarStaging2745Test {

  private static final long SEED = 2745L;
  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  @Test
  void britishRound1MovesOntoFranceBesideGermany() {
    final GameData data = canonical.cloneForSession();
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory france = data.getMap().getTerritoryOrThrow("France");
    final Territory westernGermany = data.getMap().getTerritoryOrThrow("Western Germany");
    assertThat(british.isAtWar(germans)).isTrue();
    assertThat(westernGermany.getOwner()).isEqualTo(germans);
    assertThat(data.getMap().getNeighbors(france)).contains(westernGermany);

    final List<WireMoveDescription> moves =
        new NoncombatMoveExecutor()
            .execute(
                canonical,
                new NoncombatMoveRequest(
                    new WireState(
                        List.of(
                            germans("Holland Belgium", "infantry", 4, "artillery", 2, "armour", 3),
                            germans(
                                "Western Germany",
                                "infantry",
                                3,
                                "artillery",
                                1,
                                "mech_infantry",
                                4,
                                "aaGun",
                                3,
                                "factory_major",
                                1,
                                "harbour",
                                1,
                                "airfield",
                                1),
                            germans(
                                "Germany",
                                "infantry",
                                11,
                                "artillery",
                                3,
                                "factory_major",
                                1,
                                "aaGun",
                                3),
                            germans("Norway", "infantry", 3),
                            germans("Slovakia Hungary", "infantry", 2, "armour", 1),
                            germans("Poland", "infantry", 3, "armour", 1)),
                        List.of(),
                        1,
                        "nonCombatMove",
                        "British",
                        List.of(),
                        "ww2global40_2nd_edition"),
                    SEED))
            .moves();

    assertThat(moves)
        .as("units move onto France, beside Western Germany. moves=%s", moves)
        .anyMatch(m -> "United Kingdom".equals(m.from()) && "France".equals(m.to()))
        .anyMatch(m -> "Gibraltar".equals(m.from()) && "France".equals(m.to()))
        .anyMatch(m -> "Malta".equals(m.from()) && "France".equals(m.to()))
        .anyMatch(m -> "98 Sea Zone".equals(m.from()) && "France".equals(m.to()));
  }

  /** German land, factories, and bases. Planes are omitted so France can be held. */
  private static WireTerritory germans(final String territory, final Object... typeCounts) {
    final List<WireUnit> units = new ArrayList<>();
    for (int i = 0; i < typeCounts.length; i += 2) {
      final String type = (String) typeCounts[i];
      final int count = (Integer) typeCounts[i + 1];
      for (int n = 0; n < count; n++) {
        units.add(new WireUnit(territory + "-" + type + "-" + n, type, 0, 0, 0, "Germans"));
      }
    }
    return new WireTerritory(territory, "Germans", units);
  }
}
