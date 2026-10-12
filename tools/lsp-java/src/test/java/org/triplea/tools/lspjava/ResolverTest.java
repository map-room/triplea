package org.triplea.tools.lspjava;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/**
 * Exercises {@link Resolver} against the REAL triplea sidecar + game-core source tree, not a
 * hand-built fixture. A synthetic 3-class fixture could easily miss the interface-widening case
 * this suite exists to pin down (see {@link #findCallers_widensThroughAncestorInterface} and its
 * sibling negative test) — the same "tests from the same premise confirm the bug" risk applies to
 * writing a symbol-resolution tool's own tests as to writing the tool.
 */
class ResolverTest {
  private static CtModel model;

  @BeforeAll
  static void buildRealModel() {
    Launcher launcher = new Launcher();
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setComplianceLevel(21);
    launcher.getEnvironment().setIgnoreSyntaxErrors(true);
    launcher.addInputResource("game-app/ai-sidecar/src/main/java");
    launcher.addInputResource("game-app/game-core/src/main/java");
    model = launcher.buildModel();
  }

  @Test
  void findTypesByName_findsRealSidecarClass() {
    List<CtType<?>> types = Resolver.findTypesByName(model, "PurchaseExecutor");
    assertThat(types).hasSize(1);
    assertThat(types.get(0).getQualifiedName())
        .isEqualTo("org.triplea.ai.sidecar.exec.PurchaseExecutor");
  }

  @Test
  void findTypesByName_unknownNameReturnsEmpty_notException() {
    // Session was deleted from the sidecar (triplea#91) — a real "doesn't exist" case, not
    // "exists with zero references."
    assertThat(Resolver.findTypesByName(model, "Session")).isEmpty();
  }

  @Test
  void findMethodsByName_scopedToOwnerFindsExactlyOne() {
    List<CtMethod<?>> methods = Resolver.findMethodsByName(model, "PurchaseExecutor", "execute");
    assertThat(methods).hasSize(1);
  }

  @Test
  void findMethodsByName_unscopedFindsAllOverloadsAcrossTypes() {
    // DecisionExecutor, PurchaseExecutor, and NoncombatMoveExecutor each declare "execute".
    List<CtMethod<?>> methods = Resolver.findMethodsByName(model, null, "execute");
    assertThat(methods).hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  void widenToAncestorInterfaceNames_includesTheInterfaceDecisionExecutor() {
    CtMethod<?> execute = Resolver.findMethodsByName(model, "PurchaseExecutor", "execute").get(0);
    Set<String> widened = Resolver.widenToAncestorInterfaceNames(execute);
    assertThat(widened).contains("PurchaseExecutor", "DecisionExecutor");
  }

  @Test
  void findCallers_widensThroughAncestorInterface() {
    // DecisionHandler calls PurchaseExecutor.execute through a field DECLARED as the
    // DecisionExecutor interface type (not as PurchaseExecutor) — the real call site in
    // game-app/ai-sidecar/src/main/java/org/triplea/ai/sidecar/http/DecisionHandler.java:100.
    // Without widening to the ancestor interface, this call is invisible (see the next test).
    CtMethod<?> execute = Resolver.findMethodsByName(model, "PurchaseExecutor", "execute").get(0);
    Set<String> widened = Resolver.widenToAncestorInterfaceNames(execute);
    List<CtInvocation<?>> calls = Resolver.findCallSites(model, widened, "execute");
    assertThat(calls).isNotEmpty();
    assertThat(calls)
        .anySatisfy(
            call ->
                assertThat(call.getPosition().getFile().getName())
                    .isEqualTo("DecisionHandler.java"));
  }

  @Test
  void findCallers_withoutWidening_missesTheRealCallSite() {
    // Negative control for the test above: proves the widening is load-bearing, not incidental.
    // Matching ONLY the exact declaring type ("PurchaseExecutor") finds nothing, because the real
    // call site is through the interface-typed field, not a PurchaseExecutor-typed one.
    List<CtInvocation<?>> calls =
        Resolver.findCallSites(model, Set.of("PurchaseExecutor"), "execute");
    assertThat(calls).isEmpty();
  }

  @Test
  void findCallers_widenedMatchSetAlsoCatchesASiblingImplementation() {
    // The documented precision caveat, proven rather than asserted: the same widened match set
    // used for PurchaseExecutor.execute also matches NoncombatMoveExecutor's call site, since
    // DecisionHandler holds both as the same interface type. If a future Spoon/triplea change
    // makes this NOT happen (e.g. real points-to analysis), this test should be revisited rather
    // than silently left green for the wrong reason.
    CtMethod<?> purchaseExecute =
        Resolver.findMethodsByName(model, "PurchaseExecutor", "execute").get(0);
    Set<String> widened = Resolver.widenToAncestorInterfaceNames(purchaseExecute);
    List<CtInvocation<?>> calls = Resolver.findCallSites(model, widened, "execute");
    assertThat(calls).hasSize(2); // both DecisionHandler call sites, lines 100 and 105
  }

  @Test
  void findDirectImplementors_abstractProAi_findsProAiAndFastAi() {
    CtType<?> abstractProAi = Resolver.findTypesByName(model, "AbstractProAi").get(0);
    List<Resolver.Impl> impls = Resolver.findDirectImplementors(model, abstractProAi);
    assertThat(impls.stream().map(i -> i.type().getSimpleName()))
        .containsExactlyInAnyOrder("ProAi", "FastAi");
    assertThat(impls).allSatisfy(i -> assertThat(i.relation()).isEqualTo("extends"));
  }

  @Test
  void findTypeReferences_fallsBackToParentPositionForStaticMemberAccess() {
    // StepNameMapper.resolve(...) in WireStateApplier.java: the CtTypeReference node itself has
    // no position (Spoon positions the wrapping CtTypeAccess instead) — the naive
    // reference-position-only version missed this real occurrence entirely.
    CtType<?> stepNameMapper = Resolver.findTypesByName(model, "StepNameMapper").get(0);
    List<spoon.reflect.reference.CtTypeReference<?>> refs =
        Resolver.findTypeReferences(model, stepNameMapper);
    assertThat(refs)
        .anySatisfy(
            ref ->
                assertThat(Resolver.resolvedPosition(ref).getFile().getName())
                    .isEqualTo("WireStateApplier.java"));
  }

  @Test
  void findTypeReferences_decisionHandler_walksTwoLevelsForTheClassLiteral() {
    // alpha's #174 review: find-refs DecisionHandler returned 18 results (1 declaration + 17
    // references), silently missing the DecisionHandler.class literal at
    // DecisionHandler.java:40 — a System.getLogger(DecisionHandler.class.getName()) call. That
    // literal's CtTypeReference has no position of its own, and neither does its immediate
    // parent (a CtTypeAccess) — the value is two levels up, on a synthetic CtFieldRead for the
    // .class pseudo-field. Pinned at exactly 19 (not 18, and not some other number): the fix
    // must add the missing reference WITHOUT losing or duplicating any of the 17 already-correct
    // ones — a naive unconditional 2-level walk was verified, while building this fix, to also
    // pull in 9 unrelated self-call/self-field-read entries nothing asked for.
    CtType<?> decisionHandler = Resolver.findTypesByName(model, "DecisionHandler").get(0);
    List<spoon.reflect.reference.CtTypeReference<?>> refs =
        Resolver.findTypeReferences(model, decisionHandler);

    assertThat(refs)
        .as("the .class literal at line 40, previously silently dropped")
        .anySatisfy(
            ref -> {
              var pos = Resolver.resolvedPosition(ref);
              assertThat(pos.getFile().getName()).isEqualTo("DecisionHandler.java");
              assertThat(pos.getLine()).isEqualTo(40);
            });
    // +1 for the declaration itself (DecisionHandler.java:38), reported separately by
    // Commands.findRefs, not by findTypeReferences — see the 19-total assertion in
    // CommandsTest for the full find-refs answer including the declaration.
    assertThat(refs).hasSize(18);
  }

  @Test
  void countAllTypeReferenceOccurrences_exceedsResolvedCount_whenSomeOccurrencesAreUnresolvable() {
    // The denominator that drives the type-query coverage line (alpha's #174 review: find-refs
    // had no equivalent of find-callers'/find-def's occurrence-coverage check at all, so a
    // dropped reference — like the .class literal above — had zero stderr signal). Some
    // DecisionHandler-named identifiers genuinely can't be resolved to any position within the
    // bounded walk (verified: this is nonzero for the real sidecar), so the denominator must
    // exceed the resolved count for the coverage line in ResolutionReportTest to have something
    // real to report on.
    CtType<?> decisionHandler = Resolver.findTypesByName(model, "DecisionHandler").get(0);
    int resolvedCount = Resolver.findTypeReferences(model, decisionHandler).size();
    int allOccurrences = Resolver.countAllTypeReferenceOccurrences(model, decisionHandler);

    assertThat(allOccurrences).isGreaterThan(resolvedCount);
  }
}
