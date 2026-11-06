package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.ai.pro.data.ProTerritoryManager;
import games.strategy.triplea.delegate.move.validation.MoveValidator;
import games.strategy.triplea.settings.ClientSetting;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * map-room#4648. The input is a real Global 1940 board serialized by Map Room's own {@code
 * toWireState} (byte-identical fixture, guarded on the TS side by {@code
 * wire-g1940-uk-capitals-4511.test.ts}), applied by the real {@link WireStateApplier}. The sidecar
 * never runs {@code MoveDelegate.giveBonusMovement}, so the wire's {@code bonusMovement} is the
 * unit's naval/air-base +1. Planner and validator both read {@code getMovementLeft()} and must
 * agree on it: the destroyer in 26 Sea Zone (next to the Hawaiian Islands naval base) has 3
 * movement, and the 26 -> 12 -> 9 -> 8 move that the planner offers must pass {@link
 * MoveValidator}.
 */
class BaseBonusMovementWireTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static CanonicalGameData canonical;
  private static WireState wire;

  @BeforeAll
  static void load() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
    try (InputStream in =
        BaseBonusMovementWireTest.class
            .getClassLoader()
            .getResourceAsStream("wire-contract/wire-state-g1940-uk-economies.json")) {
      wire = MAPPER.readValue(in, WireState.class);
    }
  }

  private static GameData applied() {
    final GameData gd = canonical.cloneForSession();
    WireStateApplier.apply(gd, wire, new ConcurrentHashMap<>());
    return gd;
  }

  private static Unit americanDestroyerIn26(final GameData gd) {
    final Territory sz26 = gd.getMap().getTerritoryOrThrow("26 Sea Zone");
    final GamePlayer americans = gd.getPlayerList().getPlayerId("Americans");
    return sz26.getUnits().stream()
        .filter(u -> u.getOwner().equals(americans) && "destroyer".equals(u.getType().getName()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void validatorAcceptsTheThreeZoneMoveTheBaseBonusAllows() {
    final GameData gd = applied();
    final GamePlayer americans = gd.getPlayerList().getPlayerId("Americans");
    final Unit destroyer = americanDestroyerIn26(gd);
    final Route route =
        new Route(
            gd.getMap().getTerritoryOrThrow("26 Sea Zone"),
            gd.getMap().getTerritoryOrThrow("12 Sea Zone"),
            gd.getMap().getTerritoryOrThrow("9 Sea Zone"),
            gd.getMap().getTerritoryOrThrow("8 Sea Zone"));

    assertThat(destroyer.getMovementLeft()).isEqualByComparingTo(new BigDecimal(3));
    assertThat(
            new MoveValidator(gd, true)
                .validateMove(new MoveDescription(List.of(destroyer), route), americans)
                .isMoveValid())
        .isTrue();
  }

  @Test
  void plannerRangeIsExactlyWhatTheValidatorSees() {
    final GameData gd = applied();
    final GamePlayer americans = gd.getPlayerList().getPlayerId("Americans");
    final Unit destroyer = americanDestroyerIn26(gd);

    assertThat(
            ProTerritoryManager.getUnitRange(
                destroyer, gd.getMap().getTerritoryOrThrow("26 Sea Zone"), americans, false))
        .isEqualByComparingTo(destroyer.getMovementLeft());
  }
}
