package org.triplea.tools.lspjava;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end CLI tests — forks a real JVM running {@link Main} against the real triplea source
 * tree, mirroring {@code scripts/__tests__/lsp-ts.test.ts}'s own {@code runCli} pattern (a real
 * subprocess, not an in-process call, since {@code System.exit} can't be unit-tested directly).
 * Reuses this test JVM's own classpath rather than depending on {@code installDist} having run.
 */
class MainCliTest {

  private record CliResult(int exitCode, String stdout, String stderr) {}

  /**
   * Gradle's test task CWD is the subproject dir (tools/lsp-java); walk up to find the repo root.
   */
  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (!dir.resolve("settings.gradle.kts").toFile().exists()) {
      Path parent = dir.getParent();
      if (parent == null)
        throw new IllegalStateException(
            "Could not find triplea repo root (no settings.gradle.kts) above "
                + Path.of("").toAbsolutePath());
      dir = parent;
    }
    return dir;
  }

  private static CliResult runCli(String... args) throws IOException, InterruptedException {
    String classpath = System.getProperty("java.class.path");
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                System.getProperty("java.home") + "/bin/java",
                "-cp",
                classpath,
                "org.triplea.tools.lspjava.Main"));
    command.addAll(List.of(args));

    Process process =
        new ProcessBuilder(command)
            .directory(repoRoot().toFile())
            .redirectErrorStream(false)
            .start();
    String stdout = new String(process.getInputStream().readAllBytes());
    String stderr = new String(process.getErrorStream().readAllBytes());
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      throw new AssertionError("lsp-java CLI did not exit within 60s: " + List.of(args));
    }
    return new CliResult(process.exitValue(), stdout, stderr);
  }

  @Test
  void findCallers_sessionProAi_exitsUnresolved_doesNotPrintEmptyResult() throws Exception {
    // Session was deleted from the sidecar (triplea#91) — exit 3 ("nothing to search"), not a
    // printed "(no results)" (which would read as "searched, found none").
    CliResult r =
        runCli("find-callers", "org.triplea.ai.sidecar.session.Session.proAi", "--scope=sidecar");

    assertThat(r.exitCode()).isEqualTo(ResolutionReport.EXIT_UNRESOLVED);
    assertThat(r.stdout().trim()).isEmpty();
    assertThat(r.stderr()).contains("could not resolve");
  }

  @Test
  void findCallers_purchaseExecutorExecute_returnsTheDispatchHandler() throws Exception {
    CliResult r =
        runCli(
            "find-callers",
            "org.triplea.ai.sidecar.exec.PurchaseExecutor.execute",
            "--scope=sidecar");

    assertThat(r.exitCode()).isZero();
    assertThat(r.stdout()).contains("DecisionHandler.java");
    assertThat(r.stderr()).contains("WIDENED to an ancestor interface");
  }

  @Test
  void findImpls_abstractProAi_returnsProAi() throws Exception {
    // Needs game-core in scope (the default) — AbstractProAi/ProAi both live there, not in the
    // sidecar.
    CliResult r = runCli("find-impls", "AbstractProAi", "--scope=all");

    assertThat(r.exitCode()).isZero();
    assertThat(r.stdout()).contains("ProAi.java");
  }

  @Test
  void jsonFlag_producesParseableJsonArray() throws Exception {
    CliResult r = runCli("find-impls", "AbstractProAi", "--scope=all", "--json", "--quiet");

    assertThat(r.exitCode()).isZero();
    assertThat(r.stderr().trim()).isEmpty(); // --quiet
    assertThat(r.stdout().trim()).startsWith("[").endsWith("]");
    assertThat(r.stdout()).contains("\"kind\":\"extends\"");
  }

  @Test
  void findRefs_decisionHandler_includesTheClassLiteral_exactlyNineteenResults() throws Exception {
    // alpha's #174 review: find-refs DecisionHandler returned 18 results, silently missing the
    // DecisionHandler.class literal (System.getLogger(DecisionHandler.class.getName())) at
    // DecisionHandler.java:40. Pinned at exactly 19, not merely "more than 18" — the fix must add
    // the one missing reference without dropping or duplicating any of the 17 already-correct
    // ones (see ResolverTest's sibling test for why an unconditional deeper walk overshoots this).
    CliResult r = runCli("find-refs", "DecisionHandler", "--scope=sidecar");

    assertThat(r.exitCode()).isZero();
    assertThat(r.stdout()).contains("DecisionHandler.java:40:");
    assertThat(r.stderr()).contains("-> 1 declaration(s), 19 result(s)");
  }

  @Test
  void findRefs_decisionHandler_printsTheOccurrenceCoverageLine() throws Exception {
    // The other half of alpha's #174 finding: reportTypeResolution had no occurrence-coverage
    // check at all (unlike reportMemberResolution), so a dropped reference had zero stderr
    // signal. Asserts the coverage line itself appears — not just that the right count of
    // results came back — so a future regression that silently drops a result AGAIN would still
    // be caught by the line disappearing, even if nobody re-counts the exact results by hand.
    CliResult r = runCli("find-refs", "DecisionHandler", "--scope=sidecar");

    assertThat(r.exitCode()).isZero();
    assertThat(r.stderr())
        .contains("occurrence(s) of 'DecisionHandler' accounted for")
        .contains("19 of");
  }

  @Test
  void helpFlag_exitsZeroWithoutBuildingAModel() throws Exception {
    long start = System.currentTimeMillis();
    CliResult r = runCli("--help");
    long elapsed = System.currentTimeMillis() - start;

    assertThat(r.exitCode()).isZero();
    assertThat(r.stdout()).contains("find-refs");
    // A model build is seconds; --help must short-circuit before that, not just print extra text.
    assertThat(elapsed).isLessThan(2000);
  }
}
