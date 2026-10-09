package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.settings.ClientSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.WireMoveDescription;
import org.triplea.ai.sidecar.wire.WireRelationship;
import org.triplea.ai.sidecar.wire.WireState;
import org.triplea.ai.sidecar.wire.WireStateApplier;
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

  /**
   * Americans are at war with Japan. Japanese fleets and planes are gone, and every Japanese land
   * territory with production is handed to America, so the only enemy coasts left are the 0-IPC
   * islands. A cruiser with one move left starts in 22 Sea Zone, beside the Marianas. 34 Sea Zone,
   * beside Paulau, is the best water it can reach. A destroyer with no movement left stays in 22,
   * so the safest-water fallback does not also pick 34 once enemy land stops raising sea zones.
   */
  @Test
  void americanCruiserStagesIn34BesidePaulau() {
    final GameData data = canonical.cloneForSession();
    final List<WireTerritory> territories = pacificBoard();
    final List<WireRelationship> relationships =
        List.of(new WireRelationship("Americans", "Japanese", "war"));
    WireStateApplier.apply(
        data,
        new WireState(
            territories,
            List.of(),
            1,
            "nonCombatMove",
            "Americans",
            relationships,
            "ww2global40_2nd_edition"),
        new ConcurrentHashMap<>());

    final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final Territory paulau = data.getMap().getTerritoryOrThrow("Paulau Island");
    final Territory sea34 = data.getMap().getTerritoryOrThrow("34 Sea Zone");
    final Territory sea22 = data.getMap().getTerritoryOrThrow("22 Sea Zone");
    final Territory marianas = data.getMap().getTerritoryOrThrow("Marianas");
    assertThat(americans.isAtWar(japanese)).isTrue();
    assertThat(paulau.getOwner()).isEqualTo(japanese);
    assertThat(TerritoryAttachment.getProduction(paulau)).isZero();
    assertThat(data.getMap().getNeighbors(sea34)).contains(paulau);
    assertThat(marianas.getOwner()).isEqualTo(japanese);
    assertThat(TerritoryAttachment.getProduction(marianas)).isZero();
    assertThat(data.getMap().getNeighbors(sea22)).contains(marianas);

    final List<WireMoveDescription> moves =
        new NoncombatMoveExecutor()
            .execute(
                canonical,
                new NoncombatMoveRequest(
                    new WireState(
                        territories,
                        List.of(),
                        1,
                        "nonCombatMove",
                        "Americans",
                        relationships,
                        "ww2global40_2nd_edition"),
                    SEED))
            .moves();

    assertThat(moves)
        .as("cruiser stages in 34 Sea Zone, beside Paulau. moves=%s", moves)
        .anyMatch(m -> "22 Sea Zone".equals(m.from()) && "34 Sea Zone".equals(m.to()));
  }

  /**
   * 0-IPC Japanese islands stay Japanese. Produced Japanese land becomes American, and the Japanese
   * navy and air force are removed, so 34 Sea Zone can be held and its water value is the island
   * floor rather than raw production.
   */
  private static List<WireTerritory> pacificBoard() {
    final List<WireTerritory> territories = new ArrayList<>();
    for (final String sea :
        List.of(
            "6 Sea Zone",
            "19 Sea Zone",
            "20 Sea Zone",
            "33 Sea Zone",
            "10 Sea Zone",
            "26 Sea Zone",
            "35 Sea Zone",
            "101 Sea Zone")) {
      territories.add(new WireTerritory(sea, "Neutral", List.of()));
    }
    for (final String owned :
        List.of(
            "Manchuria",
            "Formosa",
            "Okinawa",
            "Iwo Jima",
            "Kiangsu",
            "Korea",
            "Shantung",
            "Kwangsi",
            "Kiangsi",
            "Jehol",
            "Siam",
            "Hainan")) {
      territories.add(
          new WireTerritory(
              owned,
              "Americans",
              List.of(new WireUnit(owned + "-inf", "infantry", 0, 0, 0, "Americans"))));
    }
    territories.add(
        new WireTerritory(
            "Japan",
            "Americans",
            List.of(
                new WireUnit("japan-factory", "factory_major", 0, 0, 0, "Americans"),
                new WireUnit("japan-inf", "infantry", 0, 0, 0, "Americans"))));
    territories.add(
        new WireTerritory(
            "22 Sea Zone",
            "Neutral",
            List.of(
                new WireUnit("cruiser-22", "cruiser", 0, 1, 0, "Americans"),
                new WireUnit("dd-22", "destroyer", 0, 2, 0, "Americans"))));
    return territories;
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
