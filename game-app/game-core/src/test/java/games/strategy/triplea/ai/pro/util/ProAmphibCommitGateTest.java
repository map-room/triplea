package games.strategy.triplea.ai.pro.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import games.strategy.triplea.ai.pro.data.AiTheaterPriority;
import games.strategy.triplea.ai.pro.data.ProBattleResult;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.xml.TestMapGameData;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonatype.goodies.prefs.memory.MemoryPreferences;

/**
 * Phase 3B amphib commit gate tests (map-room#2755 PR-D). Pins the four decisions the gate makes:
 * (1) non-US bypass per spec §2; (2) value gate fires only when tCommit > 0; (3) value gate
 * actually defers when score is below threshold; (4) theater classifier picks the right tCommit.
 */
class ProAmphibCommitGateTest {

  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;
  private ProData proData;

  @BeforeEach
  void setUp() throws Exception {
    ClientSetting.setPreferences(new MemoryPreferences());
    data = TestMapGameData.GLOBAL1940.getGameData();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    proData = proDataFor(data, americans);
  }

  @Test
  void shouldCommit_returnsTrue_forNonUsPlayer_regardlessOfThresholds() throws Exception {
    proData = proDataFor(data, germans);
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setTCommitTargeted(1_000_000.0); // wildly high — would block any US commit
    proData.setTCommitOffTheater(1_000_000.0);
    final Territory target = data.getMap().getTerritoryOrThrow("Western Germany");
    final ProBattleResult result = mock(ProBattleResult.class);
    when(result.getWinPercentage()).thenReturn(90.0);

    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, target, 5.0, result);

    assertThat(commit)
        .as("Spec §2: SVF gate is US-only; Germans amphib commits must bypass")
        .isTrue();
  }

  @Test
  void shouldCommit_returnsTrue_atDefaultThresholds() {
    // Defaults are tCommitTargeted=tCommitOffTheater=0.0 → gate off → always commit.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    final Territory target = data.getMap().getTerritoryOrThrow("Western Germany");
    final ProBattleResult result = mock(ProBattleResult.class);
    when(result.getWinPercentage()).thenReturn(75.0);

    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, target, 5.0, result);

    assertThat(commit)
        .as("Default tCommit=0 must mean a zero-impact gate so PR-D is backward-compatible")
        .isTrue();
  }

  @Test
  void shouldCommit_returnsFalse_whenValueTimesPWinBelowTargetedThreshold() {
    // Under KGF, Western Germany is an in-theater (non-Japanese) target — uses tCommitTargeted.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setTCommitTargeted(10.0); // value*pWin must be >= 10
    final Territory target = data.getMap().getTerritoryOrThrow("Western Germany");
    final ProBattleResult result = mock(ProBattleResult.class);
    when(result.getWinPercentage()).thenReturn(50.0); // 0.5

    // value=10, pWin=0.5 → score=5 → below threshold of 10 → defer
    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, target, 10.0, result);

    assertThat(commit)
        .as("commit score below targeted-theater threshold must defer the commit")
        .isFalse();
  }

  @Test
  void shouldCommit_returnsTrue_whenValueTimesPWinAboveTargetedThreshold() {
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setTCommitTargeted(10.0);
    final Territory target = data.getMap().getTerritoryOrThrow("Western Germany");
    final ProBattleResult result = mock(ProBattleResult.class);
    when(result.getWinPercentage()).thenReturn(80.0); // 0.8

    // value=15, pWin=0.8 → score=12 → above threshold of 10 → commit
    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, target, 15.0, result);

    assertThat(commit)
        .as("commit score above targeted-theater threshold must let the commit proceed")
        .isTrue();
  }

  @Test
  void shouldCommit_offTheaterTarget_picksOffTheaterThreshold_underKGF() {
    // Under KGF, a Japanese-owned target is OFF-theater — gate must use tCommitOffTheater.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setTCommitTargeted(1.0); // small targeted bar
    proData.setTCommitOffTheater(100.0); // high off-theater bar
    final Territory japaneseTarget = data.getMap().getTerritoryOrThrow("Japan");
    final ProBattleResult result = mock(ProBattleResult.class);
    when(result.getWinPercentage()).thenReturn(80.0); // 0.8

    // value=20, pWin=0.8 → score=16. Below off-theater bar of 100 → defer.
    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, japaneseTarget, 20.0, result);

    assertThat(commit)
        .as("KGF off-theater targets (Japanese-owned) must be judged against tCommitOffTheater")
        .isFalse();
  }

  @Test
  void shouldCommit_offTheaterTarget_picksOffTheaterThreshold_underKJF() {
    // Under KJF, a German-owned target is OFF-theater. Symmetric flip of the previous test.
    proData.setAiTheaterPriority(AiTheaterPriority.KJF);
    proData.setTCommitTargeted(1.0);
    proData.setTCommitOffTheater(100.0);
    final Territory germanTarget = data.getMap().getTerritoryOrThrow("Western Germany");
    final ProBattleResult result = mock(ProBattleResult.class);
    when(result.getWinPercentage()).thenReturn(80.0);

    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, germanTarget, 20.0, result);

    assertThat(commit)
        .as("KJF off-theater targets (German-owned) must be judged against tCommitOffTheater")
        .isFalse();
  }

  @Test
  void shouldCommit_returnsTrue_whenResultIsNull_fallsBackToLegacyBehavior() {
    // A missing battle result means the caller couldn't estimate; the legacy commit path is
    // permissive in that case — don't introduce a new gate failure mode.
    proData.setAiTheaterPriority(AiTheaterPriority.KGF);
    proData.setTCommitTargeted(50.0);
    final Territory target = data.getMap().getTerritoryOrThrow("Western Germany");

    final boolean commit = ProAmphibCommitGate.shouldCommit(proData, target, 100.0, null);

    assertThat(commit)
        .as("Null battle result must not synthesize a defer — fall through to legacy commit")
        .isTrue();
  }

  // --- helpers ---

  private static ProData proDataFor(final GameData gameData, final GamePlayer player)
      throws Exception {
    final ProData p = new ProData();
    final Field dataField = ProData.class.getDeclaredField("data");
    dataField.setAccessible(true);
    dataField.set(p, gameData);
    final Field playerField = ProData.class.getDeclaredField("player");
    playerField.setAccessible(true);
    playerField.set(p, player);
    return p;
  }
}
