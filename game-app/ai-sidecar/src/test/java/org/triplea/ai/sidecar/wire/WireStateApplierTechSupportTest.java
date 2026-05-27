package org.triplea.ai.sidecar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.attachments.UnitSupportAttachment;
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
 * Verifies that WireStateApplier activates the {@code supportAttachmentMechanizedTechnology}
 * UnitSupportAttachment for any player whose wire tech list includes {@code "mechanizedInfantry"}.
 *
 * <p>The sidecar never fires TriggerAttachments (stateless per HTTP request), so the support
 * attachment's {@code players} list — initially empty in the XML — is never populated by the
 * trigger. WireStateApplier must apply the effect directly. Without this fix, ProAi battle
 * simulation ignores the armor-vs-mech-infantry +1 offence bonus even when the tech is researched.
 *
 * <p>See map-room#2768.
 */
class WireStateApplierTechSupportTest {

  private static final String SUPPORT_ATTACHMENT = "supportAttachmentMechanizedTechnology";

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

  private UnitSupportAttachment findMechTechSupport(final GameData gd) {
    final UnitType armour = gd.getUnitTypeList().getUnitType("armour").orElse(null);
    assertThat(armour).as("armour unit type must exist in game data").isNotNull();
    return UnitSupportAttachment.get(armour).stream()
        .filter(usa -> SUPPORT_ATTACHMENT.equals(usa.getName()))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(SUPPORT_ATTACHMENT + " not found on armour unit type"));
  }

  @Test
  void mechanizedInfantryTech_addsPlayerToSupportAttachment() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Germans", 40, List.of("mechanizedInfantry"), false)),
            1,
            "combat",
            "Germans",
            List.of());

    WireStateApplier.apply(gd, wire, freshIdMap());

    final GamePlayer germans = gd.getPlayerList().getPlayerId("Germans");
    final UnitSupportAttachment usa = findMechTechSupport(gd);

    assertThat(usa.getPlayers())
        .as("Germans must be in supportAttachmentMechanizedTechnology.players after tech applied")
        .contains(germans);
  }

  @Test
  void mechanizedInfantryTech_alsoSetsTechAttachmentFlag() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Americans", 40, List.of("mechanizedInfantry"), false)),
            1,
            "combat",
            "Americans",
            List.of());

    WireStateApplier.apply(gd, wire, freshIdMap());

    final TechAttachment ta = gd.getPlayerList().getPlayerId("Americans").getTechAttachment();
    assertThat(ta.getMechanizedInfantry())
        .as("TechAttachment.mechanizedInfantry must be true after wire apply")
        .isTrue();
  }

  @Test
  void withoutTech_playerNotInSupportAttachment() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(new WirePlayer("Russians", 40, List.of(), false)),
            1,
            "combat",
            "Russians",
            List.of());

    WireStateApplier.apply(gd, wire, freshIdMap());

    final GamePlayer russians = gd.getPlayerList().getPlayerId("Russians");
    final UnitSupportAttachment usa = findMechTechSupport(gd);

    assertThat(usa.getPlayers())
        .as("Russians must NOT be in support attachment when tech is absent")
        .doesNotContain(russians);
  }

  @Test
  void multiplePlayersWithTech_allAddedToSupportAttachment() {
    final GameData gd = fresh();
    final WireState wire =
        new WireState(
            List.of(),
            List.of(
                new WirePlayer("Germans", 40, List.of("mechanizedInfantry"), false),
                new WirePlayer("Japanese", 40, List.of("mechanizedInfantry"), false),
                new WirePlayer("Italians", 40, List.of(), false)),
            1,
            "combat",
            "Germans",
            List.of());

    WireStateApplier.apply(gd, wire, freshIdMap());

    final UnitSupportAttachment usa = findMechTechSupport(gd);
    final GamePlayer german = gd.getPlayerList().getPlayerId("Germans");
    final GamePlayer japanese = gd.getPlayerList().getPlayerId("Japanese");
    final GamePlayer italian = gd.getPlayerList().getPlayerId("Italians");

    assertThat(usa.getPlayers())
        .as("Both tech-holders must be in support attachment")
        .contains(german, japanese);
    assertThat(usa.getPlayers())
        .as("Non-tech-holder must not be in support attachment")
        .doesNotContain(italian);
  }
}
