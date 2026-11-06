package org.triplea.ai.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.settings.ClientSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

class CanonicalGameDataRegistryTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void loadsEverySetup() {
    final CanonicalGameDataRegistry registry = CanonicalGameDataRegistry.loadAll();
    assertThat(registry.forKey("ww2global40_2nd_edition")).isNotNull();
    assertThat(registry.forKey("ww2global42_2nd_edition")).isNotNull();
    assertThat(registry.forKey("ww2v3_1941")).isNotNull();
    assertThat(registry.forKey("ww2v3_1942")).isNotNull();
  }

  @Test
  void returnsTheSameInstancePerKey() {
    final CanonicalGameDataRegistry registry = CanonicalGameDataRegistry.loadAll();
    assertThat(registry.forKey("ww2v3_1941")).isSameAs(registry.forKey("ww2v3_1941"));
  }

  @Test
  void namesTheOffendingKeyAndTheKnownOnes() {
    final CanonicalGameDataRegistry registry = CanonicalGameDataRegistry.loadAll();
    assertThatThrownBy(() -> registry.forKey("ww2v3_1943"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ww2v3_1943")
        .hasMessageContaining("ww2v3_1941");
  }

  @Test
  void anniversaryGameDataMatchesOurRoster() {
    final var data = CanonicalGameDataRegistry.loadAll().forKey("ww2v3_1941").template();
    assertThat(data.getPlayerList().getPlayers()).hasSize(7);
    assertThat(data.getMap().getTerritories()).hasSize(162);
  }

  @Test
  void anniversary1942HasItsOwnTurnOrderAndTheSameBoard() {
    final var registry = CanonicalGameDataRegistry.loadAll();
    final var d41 = registry.forKey("ww2v3_1941").template();
    final var d42 = registry.forKey("ww2v3_1942").template();
    assertThat(d42.getMap().getTerritories()).hasSameSizeAs(d41.getMap().getTerritories());
    assertThat(purchaseOrder(d41)).startsWith("Germans", "Russians", "Japanese");
    assertThat(purchaseOrder(d42)).startsWith("Japanese", "Russians", "Germans");
    // 1942 declares who an Axis-held territory originally belonged to; 1941 leaves it unset.
    assertThat(originalOwner(d41, "Ukraine")).isEmpty();
    assertThat(originalOwner(d42, "Ukraine")).contains("Russians");
  }

  @Test
  void global1942HasItsOwnTurnOrderAndTheSameBoard() {
    final var registry = CanonicalGameDataRegistry.loadAll();
    final var d40 = registry.forKey("ww2global40_2nd_edition").template();
    final var d42 = registry.forKey("ww2global42_2nd_edition").template();
    assertThat(d42.getMap().getTerritories()).hasSameSizeAs(d40.getMap().getTerritories());
    assertThat(purchaseOrder(d40)).startsWith("Germans", "Russians", "Japanese");
    assertThat(purchaseOrder(d42))
        .startsWith("Russians", "Japanese", "Americans", "Chinese", "Germans");
  }

  private static List<String> purchaseOrder(final GameData data) {
    final List<String> order = new ArrayList<>();
    for (final GameStep step : data.getSequence().getSteps()) {
      if (step.getName().endsWith("Purchase") && step.getPlayerId() != null) {
        order.add(step.getPlayerId().getName());
      }
    }
    return order;
  }

  private static Optional<String> originalOwner(final GameData data, final String territory) {
    return TerritoryAttachment.getOrThrow(data.getMap().getTerritoryOrThrow(territory))
        .getOriginalOwner()
        .map(GamePlayer::getName);
  }
}
