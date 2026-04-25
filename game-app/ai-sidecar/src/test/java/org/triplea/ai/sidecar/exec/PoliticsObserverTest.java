package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MutableProperty;
import games.strategy.engine.data.RelationshipType;
import games.strategy.triplea.attachments.PoliticalActionAttachment;
import games.strategy.triplea.settings.ClientSetting;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.WarDeclaration;

class PoliticsObserverTest {

  private static CanonicalGameData canonical;

  @BeforeAll
  static void initPrefs() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load();
  }

  private GameData fresh() {
    return canonical.cloneForSession();
  }

  @Test
  void capturesAttemptAndExtractsWarTarget() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");

    final PoliticsObserver observer = PoliticsObserver.attach(data);

    final PoliticalActionAttachment action = buildWarAction(data, "Germans", "Russians");
    observer.recordAttempt(action);

    final List<WarDeclaration> targets = observer.toWarDeclarations(germans);
    assertThat(targets).containsExactly(new WarDeclaration("Russians"));

    observer.detach();
  }

  @Test
  void filtersNonWarRelationshipChanges() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    // Germans and Italians start allied in G40 — build an action that keeps them allied.
    final PoliticalActionAttachment action = buildAllianceAction(data, "Germans", "Italians");
    observer.recordAttempt(action);

    assertThat(observer.toWarDeclarations(germans)).isEmpty();
    observer.detach();
  }

  @Test
  void filtersActionsNotInvolvingActingPlayer() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    // Italians-vs-Russians war: Germans are the acting player but are not in this change.
    final PoliticalActionAttachment action = buildWarAction(data, "Italians", "Russians");
    observer.recordAttempt(action);

    assertThat(observer.toWarDeclarations(germans)).isEmpty();
    observer.detach();
  }

  @Test
  void attachInstallsDelegateUnderPoliticsKey() {
    final GameData data = fresh();
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    assertThat(data.getDelegateOptional("politics")).isPresent();

    observer.detach();
  }

  @Test
  void filtersNonPrimaryNations_UKPacific() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    // Japanese is the acting player declaring war on UK_Pacific (a TripleA split-UK faction
    // not recognised by Map Room's engine). The observer must filter it out.
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    // Build an action: Japanese vs UK_Pacific → war.
    final PoliticalActionAttachment action = buildWarAction(data, "Japanese", "UK_Pacific");
    observer.recordAttempt(action);

    assertThat(observer.toWarDeclarations(japanese)).isEmpty();
    observer.detach();
  }

  @Test
  void filtersNonPrimaryNations_Dutch() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    final PoliticalActionAttachment action = buildWarAction(data, "Japanese", "Dutch");
    observer.recordAttempt(action);

    assertThat(observer.toWarDeclarations(japanese)).isEmpty();
    observer.detach();
  }

  @Test
  void keepsPrimaryNationsInWarDeclarations() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    // British is a primary — must be kept.
    final PoliticalActionAttachment action = buildWarAction(data, "Japanese", "British");
    observer.recordAttempt(action);

    assertThat(observer.toWarDeclarations(japanese)).containsExactly(new WarDeclaration("British"));
    observer.detach();
  }

  @Test
  void capturedListIsAccessibleAfterDetach() throws MutableProperty.InvalidValueException {
    final GameData data = fresh();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final PoliticsObserver observer = PoliticsObserver.attach(data);

    observer.recordAttempt(buildWarAction(data, "Germans", "Russians"));
    observer.detach();

    // toWarDeclarations still works after detach — captured list is not cleared.
    assertThat(observer.toWarDeclarations(germans)).containsExactly(new WarDeclaration("Russians"));
  }

  // === Helpers ===

  /**
   * Build a {@link PoliticalActionAttachment} whose only relationship change is {@code a} vs {@code
   * b} moving to war.
   *
   * <p>Uses the {@link MutableProperty} route to set the relationship change because {@code
   * PoliticalActionAttachment.setRelationshipChange(String)} is package-private.
   */
  private static PoliticalActionAttachment buildWarAction(
      final GameData data, final String a, final String b)
      throws MutableProperty.InvalidValueException {
    return buildAction(data, a, b, firstArchetypeName(data, "war"));
  }

  private static PoliticalActionAttachment buildAllianceAction(
      final GameData data, final String a, final String b)
      throws MutableProperty.InvalidValueException {
    return buildAction(data, a, b, firstArchetypeName(data, "allied"));
  }

  private static PoliticalActionAttachment buildAction(
      final GameData data, final String a, final String b, final String relTypeName)
      throws MutableProperty.InvalidValueException {
    final GamePlayer playerA = data.getPlayerList().getPlayerId(a);
    final PoliticalActionAttachment action =
        new PoliticalActionAttachment("testAction_" + a + "_" + b, playerA, data);
    // setRelationshipChange(String) is package-private; reach it via the MutableProperty API.
    action
        .getPropertyOrEmpty("relationshipChange")
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "PoliticalActionAttachment has no 'relationshipChange' property"))
        .setValue(a + ":" + b + ":" + relTypeName);
    return action;
  }

  private static String firstArchetypeName(final GameData data, final String archeType) {
    return data.getRelationshipTypeList().getAllRelationshipTypes().stream()
        .filter(r -> archeType.equalsIgnoreCase(r.getRelationshipTypeAttachment().getArcheType()))
        .map(RelationshipType::getName)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("No relationship type with archeType: " + archeType));
  }
}
