package org.triplea.tools.lspjava;

import java.util.List;
import java.util.Set;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/**
 * Stderr resolution reporting and the exit-3 unresolved-symbol path — ported from {@code
 * scripts/lsp-ts.ts}'s {@code reportResolution}/{@code failUnresolved}. The principle carries over
 * unchanged: a silently-empty answer is the tool's most dangerous failure mode, because callers are
 * told to trust this over grep (#3657). Exit 3 means "nothing was searched," never "searched, found
 * nothing."
 */
public final class ResolutionReport {
  public static final int EXIT_UNRESOLVED = 3;

  private final boolean quiet;

  public ResolutionReport(boolean quiet) {
    this.quiet = quiet;
  }

  public void note(String line) {
    if (!quiet) System.err.println(line);
  }

  /** Abort: no type in the indexed scope has this simple name. */
  public void failUnresolvedType(String command, String query) {
    System.err.printf("lsp-java %s: could not resolve a type named '%s'.%n", command, query);
    System.err.println(
        "  No type with that simple name exists in the indexed scope "
            + "(game-app/ai-sidecar, game-app/game-core, game-app/ai by default).");
    System.err.println(
        "  Check the spelling, widen scope with --scope=all, or this symbol may live elsewhere.");
    System.err.println(
        "  Cross-check with: grep -rn 'class "
            + query
            + "\\|interface "
            + query
            + "' game-app/*/src/main/java");
    System.exit(EXIT_UNRESOLVED);
  }

  /** Abort: no method anywhere (optionally scoped to an owner) has this simple name. */
  public void failUnresolvedMember(String command, SymbolQuery q) {
    System.err.printf("lsp-java %s: could not resolve a method named '%s'.%n", command, q.raw());
    if (q.ownerName() != null) {
      System.err.printf(
          "  No type named '%s' declares a method named '%s' in the indexed scope.%n",
          q.ownerName(), q.memberOrBare());
    } else {
      System.err.printf("  No method named '%s' exists in the indexed scope.%n", q.memberOrBare());
    }
    System.err.println("  This is a limitation of lsp-java, NOT evidence the method is unused.");
    System.err.println(
        "  Cross-check with: grep -rn '"
            + (q.memberName() != null ? q.memberName() : q.raw())
            + "(' game-app/*/src/main/java");
    System.exit(EXIT_UNRESOLVED);
  }

  /**
   * Report a successful type resolution with no occurrence-coverage check: how many types matched,
   * and ambiguity if &gt;1. For commands whose result isn't a count of "references to the name" in
   * the first place — {@code find-def} (returns declarations, not usages) and {@code find-impls}
   * (returns subtype count, which has no reason to track how often the name is textually used
   * elsewhere) — comparing against total occurrences would flag every healthy answer as
   * "incomplete." Mirrors {@code lsp-ts.ts}'s own {@code checkCoverage: false} for find-def.
   */
  public void reportTypeResolution(
      String command, String query, List<CtType<?>> matches, int resultCount) {
    note(
        String.format(
            "lsp-java %s: type '%s' -> %d declaration(s), %d result(s).",
            command, query, matches.size(), resultCount));
    if (matches.size() > 1) {
      note("  Multiple types share this simple name — results are the union across all of them:");
      for (CtType<?> t : matches) {
        note("    " + t.getQualifiedName() + " (" + positionOf(t) + ")");
      }
    }
  }

  /**
   * Report a successful type resolution for a command whose result IS meant to be every occurrence
   * of the type ({@code find-refs}) — adds the occurrence-coverage line {@link
   * #reportTypeResolution(String, String, List, int)} deliberately skips, mirroring {@link
   * #reportMemberResolution}'s role for member queries.
   *
   * <p>Added after alpha's #174 review: {@code find-refs DecisionHandler} was silently dropping the
   * {@code DecisionHandler.class} literal with zero stderr signal — exactly the #3657 failure mode
   * this report exists to catch, just previously unguarded on the type-query path. Without this
   * method, a future reference shape {@link Resolver#positionOrParentPosition} still can't resolve
   * (even with its now-bounded walk) would vanish the same way, again with no trace.
   */
  public void reportTypeResolution(
      String command, String query, List<CtType<?>> matches, int resultCount, int allOccurrences) {
    reportTypeResolution(command, query, matches, resultCount);
    if (allOccurrences > resultCount) {
      note(
          String.format(
              "  %d of %d occurrence(s) of '%s' accounted for; the rest could not be resolved to"
                  + " a position (an unresolvable AST shape, not evidence they don't exist —"
                  + " cross-check with grep if precision matters).",
              resultCount, allOccurrences, query));
    }
  }

  /**
   * Report a successful member resolution, including the ancestor-widening caveat whenever the
   * match set grew beyond the method's own declaring type — see {@link
   * Resolver#widenToAncestorInterfaceNames} for why this happens and what it costs in precision.
   */
  public void reportMemberResolution(
      String command,
      SymbolQuery q,
      List<CtMethod<?>> decls,
      Set<String> matchTypeNames,
      int resultCount,
      int allOccurrences) {
    note(
        String.format(
            "lsp-java %s: method '%s' -> %d declaration(s), %d result(s).",
            command, q.raw(), decls.size(), resultCount));

    if (decls.size() > 1) {
      note("  Multiple methods share this name — results are the union across all of them:");
      for (CtMethod<?> m : decls) {
        CtType<?> owner = m.getDeclaringType();
        note(
            "    "
                + (owner != null ? owner.getQualifiedName() : "?")
                + "."
                + m.getSimpleName()
                + " ("
                + positionOf(m)
                + ")");
      }
    }

    boolean widened =
        decls.stream().anyMatch(m -> Resolver.widenToAncestorInterfaceNames(m).size() > 1);
    if (widened) {
      note("  WIDENED to an ancestor interface/superclass sharing this method name — see --help.");
      note("  Match set: " + matchTypeNames + ". Call sites through OTHER implementations of the");
      note("  same interface may be included too (Spoon's noClasspath mode has no points-to");
      note("  analysis to tell them apart). Verify each result's receiver if precision matters.");
    }

    if (allOccurrences > resultCount) {
      note(
          String.format(
              "  %d of %d occurrence(s) of '%s' accounted for; the rest are unrelated same-named symbols.",
              resultCount, allOccurrences, q.memberName() != null ? q.memberName() : q.raw()));
    }
  }

  private static String positionOf(spoon.reflect.declaration.CtElement e) {
    var pos = e.getPosition();
    if (pos == null || !pos.isValidPosition()) return "no position";
    return pos.getFile().getName() + ":" + pos.getLine();
  }
}
