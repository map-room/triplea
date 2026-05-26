package games.strategy.triplea.ai.pro.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameSequence;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ai.pro.ProData;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProValueHeatmapDumperTest {

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    System.setProperty("ai.dump.path", tempDir.toString());
    ProValueHeatmapDumper.resetSeqForTests();
  }

  @AfterEach
  void tearDown() {
    System.clearProperty("ai.dump.values");
    System.clearProperty("ai.dump.strategic");
    System.clearProperty("ai.dump.path");
  }

  @Test
  void disabledByDefault_writesNothing() {
    final GameData data = stubGameData(1);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

    assertThat(listDumpedFiles()).isEmpty();
  }

  @Test
  void enabledViaSystemProperty_writesJsonFile() throws IOException {
    System.setProperty("ai.dump.values", "1");
    final GameData data = stubGameData(7);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values =
        orderedMap(
            stubTerritory("Eastern United States", false), 12.5,
            stubTerritory("Sea Zone 10", true), 0.0);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

    final List<Path> files = listDumpedFiles();
    assertThat(files).hasSize(1);
    final String content = Files.readString(files.get(0), StandardCharsets.UTF_8);
    assertThat(content)
        .contains("\"round\":7")
        .contains("\"player\":\"Americans\"")
        .contains("\"entrypoint\":\"combined\"")
        .contains("\"name\":\"Eastern United States\"")
        .contains("\"isWater\":false")
        .contains("\"value\":12.5")
        .contains("\"name\":\"Sea Zone 10\"")
        .contains("\"isWater\":true");
  }

  @Test
  void territoriesAreSortedByName() throws IOException {
    System.setProperty("ai.dump.values", "1");
    final GameData data = stubGameData(1);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values =
        orderedMap(
            stubTerritory("Zulu", false), 1.0,
            stubTerritory("Alpha", false), 2.0,
            stubTerritory("Mike", false), 3.0);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

    final String content = Files.readString(listDumpedFiles().get(0), StandardCharsets.UTF_8);
    final int alphaIdx = content.indexOf("\"name\":\"Alpha\"");
    final int mikeIdx = content.indexOf("\"name\":\"Mike\"");
    final int zuluIdx = content.indexOf("\"name\":\"Zulu\"");
    assertThat(alphaIdx).isPositive().isLessThan(mikeIdx);
    assertThat(mikeIdx).isLessThan(zuluIdx);
  }

  @Test
  void multipleDumpsAreUniquelyNumbered() {
    System.setProperty("ai.dump.values", "1");
    final GameData data = stubGameData(1);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);
    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "sea-only", values);

    final List<Path> files = listDumpedFiles();
    assertThat(files).hasSize(2);
    assertThat(files.stream().map(Path::getFileName).map(Path::toString))
        .anyMatch(n -> n.contains("combined"))
        .anyMatch(n -> n.contains("sea-only"));
  }

  @Test
  void playerNameWithUnsafeCharactersIsSanitizedInFilename() {
    System.setProperty("ai.dump.values", "1");
    final GameData data = stubGameData(1);
    final GamePlayer player = stubPlayer("British/Pacific");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

    final List<Path> files = listDumpedFiles();
    assertThat(files).hasSize(1);
    final String name = files.get(0).getFileName().toString();
    assertThat(name).contains("British_Pacific").doesNotContain("/");
  }

  @Test
  void systemPropertyPathIsHonored() {
    // Default precedence: ai.dump.path sysprop > $HOME/.maproom/ai-debug.
    // setUp() already set ai.dump.path to tempDir; verify the dumper resolved it.
    System.setProperty("ai.dump.values", "1");
    final GameData data = stubGameData(1);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

    assertThat(listDumpedFiles()).hasSize(1);
  }

  @Test
  void fallsBackToHomeDirWhenNoOverrideSet(@TempDir final Path fakeHome) {
    // Clear the sysprop set by setUp() to exercise the user.home fallback path.
    System.clearProperty("ai.dump.path");
    final String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", fakeHome.toString());
    System.setProperty("ai.dump.values", "1");
    try {
      final GameData data = stubGameData(1);
      final GamePlayer player = stubPlayer("Americans");
      final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

      ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

      final Path expectedDir = fakeHome.resolve(ProValueHeatmapDumper.OUTPUT_SUBDIR);
      assertThat(expectedDir).exists();
      try (Stream<Path> s = Files.list(expectedDir)) {
        assertThat(s.toList()).hasSize(1);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } finally {
      // Restore the ORIGINAL user.home — clearing it (the previous impl) leaks a null
      // into the JVM that breaks subsequent ClientSetting static init in OTHER test
      // classes that run in the same JVM (caught when ProStrategicValueFieldTest started
      // failing intermittently with NoClassDefFoundError on ClientSetting).
      if (originalHome != null) {
        System.setProperty("user.home", originalHome);
      } else {
        System.clearProperty("user.home");
      }
    }
  }

  @Test
  void nanAndInfinityValuesAreSerializedAsZero() throws IOException {
    System.setProperty("ai.dump.values", "1");
    final GameData data = stubGameData(1);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values =
        orderedMap(
            stubTerritory("Nan", false), Double.NaN,
            stubTerritory("PosInf", false), Double.POSITIVE_INFINITY,
            stubTerritory("NegInf", false), Double.NEGATIVE_INFINITY);

    ProValueHeatmapDumper.dumpIfEnabledFromGameData(data, player, "combined", values);

    final String content = Files.readString(listDumpedFiles().get(0), StandardCharsets.UTF_8);
    assertThat(content).doesNotContain("NaN").doesNotContain("Infinity");
    // Three territories, each with "value":0 — assert all three are present.
    final long zeroCount =
        Stream.of(content.split("\"value\":0"))
            .skip(1) // first split is everything before the first match
            .count();
    assertThat(zeroCount).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------
  // Strategic-field sibling dump (PR-A — map-room#2755)
  // ---------------------------------------------------------------------------

  @Test
  void strategicGate_disabledByDefault_noSiblingFileWritten() {
    System.setProperty("ai.dump.values", "1"); // value gate on
    // ai.dump.strategic intentionally NOT set
    final ProData proData =
        stubProDataWithStrategicField(1, Map.of(stubTerritory("Foo", false), 5.0));
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabled(proData, player, "combined", values);

    // Only the value-map dump is written, no -strategic sibling.
    final List<Path> files = listDumpedFiles();
    assertThat(files).hasSize(1);
    assertThat(files.get(0).getFileName().toString()).doesNotContain("strategic");
  }

  @Test
  void strategicGate_enabled_andFieldPopulated_writesSiblingFile() throws IOException {
    System.setProperty("ai.dump.values", "1");
    System.setProperty("ai.dump.strategic", "1");
    final Map<Territory, Double> strategicField =
        orderedMap(stubTerritory("Berlin", false), 75.0, stubTerritory("East US", false), 10.5);
    final ProData proData = stubProDataWithStrategicField(3, strategicField);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabled(proData, player, "combined", values);

    final List<Path> files = listDumpedFiles();
    assertThat(files).hasSize(2);
    final Path siblingFile =
        files.stream()
            .filter(p -> p.getFileName().toString().contains("strategic"))
            .findFirst()
            .orElseThrow();
    final String content = Files.readString(siblingFile, StandardCharsets.UTF_8);
    assertThat(content)
        .contains("\"entrypoint\":\"combined-strategic\"")
        .contains("\"round\":3")
        .contains("\"name\":\"Berlin\"")
        .contains("\"value\":75.0")
        .contains("\"name\":\"East US\"")
        .contains("\"value\":10.5");
  }

  @Test
  void strategicGate_enabled_butFieldNull_writesNoSibling() {
    System.setProperty("ai.dump.values", "1");
    System.setProperty("ai.dump.strategic", "1");
    final ProData proData = stubProDataWithStrategicField(1, null);
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabled(proData, player, "combined", values);

    assertThat(listDumpedFiles())
        .as("strategic field null (wStrat=0 path) → no sibling")
        .hasSize(1)
        .allMatch(p -> !p.getFileName().toString().contains("strategic"));
  }

  @Test
  void strategicGate_independent_fromValueGate_canEmitWithoutValueDump() {
    // AI_DUMP_VALUES off, AI_DUMP_STRATEGIC on — only the strategic sibling should write.
    System.setProperty("ai.dump.strategic", "1");
    final ProData proData =
        stubProDataWithStrategicField(2, Map.of(stubTerritory("Foo", false), 5.0));
    final GamePlayer player = stubPlayer("Americans");
    final Map<Territory, Double> values = Map.of(stubTerritory("Foo", false), 1.0);

    ProValueHeatmapDumper.dumpIfEnabled(proData, player, "combined", values);

    final List<Path> files = listDumpedFiles();
    assertThat(files).hasSize(1);
    assertThat(files.get(0).getFileName().toString()).contains("strategic");
  }

  // --- helpers ---

  /**
   * Builds a real {@link ProData} with reflection-injected mocked GameData. ProData is final so
   * Mockito can't mock it directly; this mirrors the helper pattern in {@code
   * ProFriendlyIslandFloorTest} and {@code ProStrategicValueFieldTest}.
   */
  private static ProData stubProDataWithStrategicField(
      final int round, final Map<Territory, Double> strategicField) {
    try {
      final ProData proData = new ProData();
      final Field dataField = ProData.class.getDeclaredField("data");
      dataField.setAccessible(true);
      dataField.set(proData, stubGameData(round));
      proData.setStrategicValueField(strategicField);
      return proData;
    } catch (final ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private List<Path> listDumpedFiles() {
    try (Stream<Path> s = Files.list(tempDir)) {
      return s.toList();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static GameData stubGameData(final int round) {
    final GameData data = mock(GameData.class);
    final GameSequence sequence = mock(GameSequence.class);
    when(data.getSequence()).thenReturn(sequence);
    when(sequence.getRound()).thenReturn(round);
    return data;
  }

  private static GamePlayer stubPlayer(final String name) {
    final GamePlayer player = mock(GamePlayer.class);
    when(player.getName()).thenReturn(name);
    return player;
  }

  private static Territory stubTerritory(final String name, final boolean isWater) {
    final Territory t = mock(Territory.class);
    when(t.getName()).thenReturn(name);
    when(t.isWater()).thenReturn(isWater);
    return t;
  }

  private static Map<Territory, Double> orderedMap(final Object... kvPairs) {
    final Map<Territory, Double> m = new LinkedHashMap<>();
    for (int i = 0; i < kvPairs.length; i += 2) {
      m.put((Territory) kvPairs[i], (Double) kvPairs[i + 1]);
    }
    return m;
  }
}
