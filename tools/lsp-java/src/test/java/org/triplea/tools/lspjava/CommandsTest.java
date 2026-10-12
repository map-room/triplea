package org.triplea.tools.lspjava;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

/**
 * {@link Commands}-level tests for cases {@link ResolverTest} doesn't cover: query-string parsing
 * edge cases feeding into the actual command, not just {@link Resolver} directly.
 */
class CommandsTest {
  private static CtModel model;

  @BeforeAll
  static void buildRealModel() {
    Launcher launcher = new Launcher();
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setComplianceLevel(21);
    launcher.getEnvironment().setIgnoreSyntaxErrors(true);
    launcher.addInputResource("game-app/ai-sidecar/src/main/java");
    model = launcher.buildModel();
  }

  @Test
  void findCallers_bareUndottedMethodName_stillResolves() {
    // Regression: SymbolQuery.parse("execute") (no dot) leaves memberName() null — findCallers
    // must read memberOrBare(), not memberName() directly, or this silently searches for a
    // method literally named null and returns nothing.
    var report = new ResolutionReport(true);
    var entries = Commands.findCallers(model, "execute", report);

    assertThat(entries).isNotEmpty();
    assertThat(entries).anySatisfy(e -> assertThat(e.file()).contains("DecisionHandler.java"));
  }

  @Test
  void findCallers_dottedOwnerAndBareMethod_agreeWithUnqualifiedOnMatchCount() {
    var report = new ResolutionReport(true);
    var qualified =
        Commands.findCallers(model, "org.triplea.ai.sidecar.exec.PurchaseExecutor.execute", report);
    var bareOwner = Commands.findCallers(model, "PurchaseExecutor.execute", report);

    assertThat(qualified).hasSameSizeAs(bareOwner);
  }
}
