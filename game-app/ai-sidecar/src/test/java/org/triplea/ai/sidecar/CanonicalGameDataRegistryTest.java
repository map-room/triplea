package org.triplea.ai.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import games.strategy.triplea.settings.ClientSetting;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

class CanonicalGameDataRegistryTest {

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
  }

  @Test
  void loadsBothEditions() {
    final CanonicalGameDataRegistry registry = CanonicalGameDataRegistry.loadAll();
    assertThat(registry.forKey("ww2global40_2nd_edition")).isNotNull();
    assertThat(registry.forKey("ww2v3_1941")).isNotNull();
  }

  @Test
  void returnsTheSameInstancePerKey() {
    final CanonicalGameDataRegistry registry = CanonicalGameDataRegistry.loadAll();
    assertThat(registry.forKey("ww2v3_1941")).isSameAs(registry.forKey("ww2v3_1941"));
  }

  @Test
  void namesTheOffendingKeyAndTheKnownOnes() {
    final CanonicalGameDataRegistry registry = CanonicalGameDataRegistry.loadAll();
    assertThatThrownBy(() -> registry.forKey("ww2v3_1942"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ww2v3_1942")
        .hasMessageContaining("ww2v3_1941");
  }

  @Test
  void anniversaryGameDataMatchesOurRoster() {
    final var data = CanonicalGameDataRegistry.loadAll().forKey("ww2v3_1941").template();
    assertThat(data.getPlayerList().getPlayers()).hasSize(7);
    assertThat(data.getMap().getTerritories()).hasSize(162);
  }
}
