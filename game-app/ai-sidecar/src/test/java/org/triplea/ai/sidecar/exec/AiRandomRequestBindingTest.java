package org.triplea.ai.sidecar.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.withSettings;

import games.strategy.triplea.ai.AiRandom;
import games.strategy.triplea.settings.ClientSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;
import org.triplea.ai.sidecar.CanonicalGameData;
import org.triplea.ai.sidecar.dto.NoncombatMoveRequest;
import org.triplea.ai.sidecar.dto.PurchaseRequest;
import org.triplea.ai.sidecar.wire.WireState;

/**
 * Guards the sidecar wiring of {@link AiRandom#bindRequest}, not the primitive itself.
 *
 * <p>{@code AbstractAiTest} binds the thread itself, so it stays green if an executor stops calling
 * {@code bindRequest}. This test drives {@link PurchaseExecutor#execute} and {@link
 * NoncombatMoveExecutor#execute}. The first {@link AiRandom#get()} after a real {@code bindRequest}
 * throws. That draw is {@code ProDummyDelegateBridge}'s field initializer, which both executors
 * reach inside the {@code try}. A removed {@code bindRequest} never arms the throw, so the call
 * returns normally and the assertion fails.
 */
class AiRandomRequestBindingTest {

  private static final long SEED = 186L;
  private static final String BOOM = "boom-after-bind";

  private static CanonicalGameData canonical;

  @BeforeAll
  static void init() {
    ClientSetting.setPreferences(new MemoryPreferences());
    canonical = CanonicalGameData.load("ww2global40_2nd_edition.xml");
  }

  @AfterEach
  void clearBinding() {
    AiRandom.clearRequest();
  }

  @Test
  void purchaseExecutorBindsTheRequestSeedAndClearsItWhenTheDrawThrows() {
    final PurchaseRequest request = new PurchaseRequest(germans("purchase"), SEED);
    assertBindsAndClearsOnDraw(SEED, () -> new PurchaseExecutor().execute(canonical, request));
  }

  @Test
  void noncombatMoveExecutorBindsTheRequestSeedAndClearsItWhenTheDrawThrows() {
    final NoncombatMoveRequest request = new NoncombatMoveRequest(germans("nonCombatMove"), SEED);
    assertBindsAndClearsOnDraw(SEED, () -> new NoncombatMoveExecutor().execute(canonical, request));
  }

  private static WireState germans(final String phase) {
    return new WireState(
        List.of(), List.of(), 1, phase, "Germans", List.of(), "ww2global40_2nd_edition");
  }

  private static void assertBindsAndClearsOnDraw(final long seed, final Runnable execute) {
    final List<String> trace = new ArrayList<>();
    final AtomicBoolean bound = new AtomicBoolean();
    // The sidecar test classpath selects the subclass mock maker, which cannot mock statics.
    // Ask for the inline maker on this mock only.
    try (MockedStatic<AiRandom> mocked =
        mockStatic(
            AiRandom.class,
            withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(CALLS_REAL_METHODS))) {
      mocked.when(() -> AiRandom.bindRequest(anyLong())).thenAnswer(recordBind(trace, bound));
      mocked.when(AiRandom::clearRequest).thenAnswer(recordClear(trace));
      mocked.when(AiRandom::get).thenAnswer(throwAfterBind(trace, bound));

      assertThatThrownBy(execute::run)
          .as("missing bindRequest lets the executor finish on ThreadLocalRandom")
          .isInstanceOf(IllegalStateException.class)
          .hasMessage(BOOM);
    }

    final String bind = "bind:" + seed;
    assertThat(trace).contains(bind);
    final int bindAt = trace.indexOf(bind);
    final int clearAt = trace.lastIndexOf("clear");
    assertThat(clearAt).isEqualTo(trace.size() - 1);
    assertThat(trace.subList(bindAt + 1, clearAt)).contains("get");
    assertThat(AiRandom.get()).isInstanceOf(ThreadLocalRandom.class);
  }

  private static Answer<Object> recordBind(final List<String> trace, final AtomicBoolean bound) {
    return invocation -> {
      trace.add("bind:" + invocation.getArgument(0));
      invocation.callRealMethod();
      bound.set(true);
      return null;
    };
  }

  private static Answer<Object> recordClear(final List<String> trace) {
    return invocation -> {
      trace.add("clear");
      invocation.callRealMethod();
      return null;
    };
  }

  private static Answer<Object> throwAfterBind(
      final List<String> trace, final AtomicBoolean bound) {
    return invocation -> {
      trace.add("get");
      if (bound.get()) {
        throw new IllegalStateException(BOOM);
      }
      return invocation.callRealMethod();
    };
  }
}
