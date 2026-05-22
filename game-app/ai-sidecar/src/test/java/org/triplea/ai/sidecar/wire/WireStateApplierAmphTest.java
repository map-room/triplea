package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.triplea.ai.pro.data.AmphContext;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;

/**
 * Phase 2.1 / 2.2 wire-protocol tests for {@link WireStateApplier#apply} returning {@link
 * AmphContext}. Covers: toggle on/off reflected in the returned context; per-unit embarked and
 * embarkedThisTurn read back correctly; toggle-off leaves existing GameData unchanged.
 */
class WireStateApplierAmphTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private GameData fresh() {
    return canonical.cloneForSession();
  }

  private ConcurrentMap<String, UUID> freshIdMap() {
    return new ConcurrentHashMap<>();
  }

  // ---- toggle reflection ----

  @Test
  void toggleOffReturnsDisabledContext() {
    final WireState wire =
        new WireState(List.of(), List.of(), 1, "combat-move", "Germans", List.of(), false);
    final AmphContext ctx = WireStateApplier.apply(fresh(), wire, freshIdMap());
    assertThat(ctx.isEnabled()).isFalse();
    assertThat(ctx.embarkedUnitIds()).isEmpty();
    assertThat(ctx.embarkedThisTurnUnitIds()).isEmpty();
  }

  @Test
  void toggleOnNoUnitsReturnsEnabledEmptyContext() {
    final WireState wire =
        new WireState(List.of(), List.of(), 1, "noncombat-move", "Americans", List.of(), true);
    final AmphContext ctx = WireStateApplier.apply(fresh(), wire, freshIdMap());
    assertThat(ctx.isEnabled()).isTrue();
    assertThat(ctx.embarkedUnitIds()).isEmpty();
    assertThat(ctx.embarkedThisTurnUnitIds()).isEmpty();
  }

  // ---- per-unit embarked read-back ----

  @Test
  void embarkedUnitAppearsInContext() {
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireUnit stagedInf =
        WireUnit.of(
            "inf-staged",
            "infantry",
            null,
            null,
            null,
            "Americans",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            Boolean.FALSE);
    final WireTerritory sz = new WireTerritory("111 Sea Zone", "Neutral", List.of(stagedInf));
    final WireState wire =
        new WireState(List.of(sz), List.of(), 2, "noncombat-move", "Americans", List.of(), true);

    final AmphContext ctx = WireStateApplier.apply(fresh(), wire, idMap);

    assertThat(ctx.isEnabled()).isTrue();
    final UUID unitUuid = idMap.get("inf-staged");
    assertThat(unitUuid).isNotNull();
    assertThat(ctx.embarkedUnitIds()).contains(unitUuid);
    assertThat(ctx.embarkedThisTurnUnitIds()).doesNotContain(unitUuid);
  }

  @Test
  void embarkedThisTurnUnitAppearsInBothSets() {
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireUnit freshInf =
        WireUnit.of(
            "inf-fresh",
            "infantry",
            null,
            null,
            null,
            "Americans",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            Boolean.TRUE);
    final WireTerritory sz = new WireTerritory("111 Sea Zone", "Neutral", List.of(freshInf));
    final WireState wire =
        new WireState(List.of(sz), List.of(), 2, "noncombat-move", "Americans", List.of(), true);

    final AmphContext ctx = WireStateApplier.apply(fresh(), wire, idMap);

    final UUID unitUuid = idMap.get("inf-fresh");
    assertThat(unitUuid).isNotNull();
    assertThat(ctx.embarkedUnitIds()).contains(unitUuid);
    assertThat(ctx.embarkedThisTurnUnitIds()).contains(unitUuid);
  }

  @Test
  void nonEmbarkedUnitAbsentFromBothSets() {
    final ConcurrentMap<String, UUID> idMap = freshIdMap();
    final WireUnit landInf =
        WireUnit.of(
            "inf-land",
            "infantry",
            null,
            null,
            null,
            "Germans",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Boolean.FALSE,
            Boolean.FALSE);
    final WireTerritory germany = new WireTerritory("Germany", "Germans", List.of(landInf));
    final WireState wire =
        new WireState(List.of(germany), List.of(), 1, "noncombat-move", "Germans", List.of(), true);

    final AmphContext ctx = WireStateApplier.apply(fresh(), wire, idMap);

    final UUID unitUuid = idMap.get("inf-land");
    assertThat(unitUuid).isNotNull();
    assertThat(ctx.embarkedUnitIds()).doesNotContain(unitUuid);
    assertThat(ctx.embarkedThisTurnUnitIds()).doesNotContain(unitUuid);
  }

  // ---- toggle-off: GameData unchanged ----

  @Test
  void toggleOffDoesNotAlterTerritoryOwnership() {
    final GameData gd = fresh();
    final String owner = gd.getMap().getTerritoryOrThrow("Germany").getOwner().getName();
    final WireState wire =
        new WireState(List.of(), List.of(), 1, "combat-move", "Germans", List.of(), false);
    WireStateApplier.apply(gd, wire, freshIdMap());
    assertThat(gd.getMap().getTerritoryOrThrow("Germany").getOwner().getName()).isEqualTo(owner);
  }
}
