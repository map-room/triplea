package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.Constants;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.PurchasePlan;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Live ProAI purchase against WW2v3-1941 — the map-room#3535 regression surface.
 *
 * <p>Before the fix: step-name mismatch left the sequence on {@code gameInitDelegate}, and Japanese
 * purchase crashed with {@code No rule attachment for:Japanese with name: rulesAttachment}. After:
 * both nations produce a plan without throwing.
 */
class PurchaseExecutorWw2v3Test {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("WW2v3-1941.xml");
  }

  private static PurchaseRequest requestFor(final String nation) {
    return new PurchaseRequest(
        new WireState(List.of(), List.of(), 1, "purchase", nation, List.of(), "ww2v3_1941"), 0L);
  }

  @Test
  void germansPurchaseProducesRealBuys() {
    final GameData data = canonical.cloneForSession();
    final PurchasePlan plan = new PurchaseExecutor().executeOn(data, requestFor("Germans"));
    // Germans turn-1 WW2v3 have PUs and factories — a non-empty plan proves ProAI ran past
    // gameInitDelegate and produced decisions (the #3535 before-state was 500 on every call).
    assertThat(plan.buys()).isNotEmpty();
    // PurchaseExecutor repositions the sequence onto combatMove for the combat-move projection
    // that ships with the purchase plan; that is expected, not a regression.
    assertThat(data.getSequence().getStep().getName())
        .isNotEqualTo("gameInitDelegate")
        .isEqualTo("germanCombatMove");
  }

  @Test
  void japanesePurchaseToleratesMissingRulesAttachment() {
    final GameData data = canonical.cloneForSession();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    // Precondition: WW2v3-1941 has no rulesAttachment on Japanese (only Chinese has one).
    assertThat(japanese.getRulesAttachment())
        .as("WW2v3-1941 Japanese must lack rulesAttachment — that is the #3535 root cause 2")
        .isNull();

    final PurchasePlan plan = new PurchaseExecutor().executeOn(data, requestFor("Japanese"));
    assertThat(plan).isNotNull();
    assertThat(plan.buys()).isNotEmpty();
    assertThat(data.getSequence().getStep().getName()).isNotEqualTo("gameInitDelegate");
  }

  @Test
  void allSevenNationsPurchaseWithoutThrowing() {
    for (final String nation :
        new String[] {
          "Germans", "Russians", "Japanese", "British", "Italians", "Americans", "Chinese"
        }) {
      final GameData data = canonical.cloneForSession();
      final PurchasePlan[] held = new PurchasePlan[1];
      assertThatCode(() -> held[0] = new PurchaseExecutor().executeOn(data, requestFor(nation)))
          .as("purchase for %s", nation)
          .doesNotThrowAnyException();
      assertThat(data.getSequence().getStep().getName())
          .as("step for %s must not be gameInitDelegate", nation)
          .isNotEqualTo("gameInitDelegate");
      assertThat(held[0]).as("plan for %s", nation).isNotNull();
    }
  }

  @Test
  void chineseHasRulesAttachmentButJapaneseDoesNot() {
    final GameData data = canonical.cloneForSession();
    assertThat(data.getPlayerList().getPlayerId("Chinese").getRulesAttachment()).isNotNull();
    assertThat(data.getPlayerList().getPlayerId("Japanese").getRulesAttachment()).isNull();
    // National objectives still exist as objectiveAttachment* (separate from rulesAttachment).
    assertThat(
            data.getPlayerList().getPlayerId("Japanese").getAttachments().keySet().stream()
                .anyMatch(k -> k.startsWith(Constants.RULES_OBJECTIVE_PREFIX)))
        .as("Japanese should still have objectiveAttachment* national objectives")
        .isTrue();
  }
}
