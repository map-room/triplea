package org.triplea.tools.lspjava;

import java.util.ArrayList;
import java.util.List;
import spoon.Launcher;
import spoon.reflect.CtModel;

/**
 * lsp-java — semantic Java query CLI using Spoon, mirroring {@code bin/lsp-ts}'s interface (#2404).
 * See that script's own doc comment and the design posted on map-room#2404 for the rationale behind
 * each choice mirrored (or deliberately not mirrored) here.
 */
public final class Main {
  private Main() {}

  private static final String HELP =
      """
      lsp-java — Semantic Java query CLI (Spoon)

      Usage:
        bin/lsp-java <command> [args] [--json] [--quiet] [--scope=sidecar|all]
        bin/lsp-java --help

      Commands:
        find-refs <name>              All references to a type, method, or field
        find-def <name>                Declaration site(s)
        find-callers <Type.method>     Call sites of a method (bare <method> also accepted)
        find-impls <name>              Classes that extend/implement <name> (interfaces and classes)
        find-unused-public             Public methods with no resolvable caller

      Options:
        --json             Output as a JSON array
        --quiet            Suppress the stderr resolution report
        --scope=sidecar    Index only game-app/ai-sidecar (fast, ~1.4s) — the common case
        --scope=all         Index ai-sidecar + game-core + ai (default, ~6.7s; needed for
                            cross-module queries like `find-impls AbstractProAi`)

      Output format (default): <file>:<line>:<col> (<kind>)

      Exit codes:
        0  query ran (an empty result means "searched, found none")
        1  usage or operational error
        3  symbol did not resolve to a declaration — nothing was searched.
           Never read exit 3 as "no references": cross-check with grep.

      Known limitation (see map-room#2404's design comment for detail): find-callers and
      find-unused-public widen a method query to any ancestor interface/superclass sharing its
      name, since Spoon's noClasspath mode cannot resolve a polymorphic call site to its exact
      implementation. This can fold in a sibling implementation's calls through the same
      interface. The stderr report says explicitly when this widening happened.
      """;

  public static void main(String[] args) {
    boolean jsonFlag = false;
    boolean quietFlag = false;
    String scope = "all";
    List<String> positional = new ArrayList<>();
    for (String a : args) {
      if (a.equals("--json")) jsonFlag = true;
      else if (a.equals("--quiet")) quietFlag = true;
      else if (a.startsWith("--scope=")) scope = a.substring("--scope=".length());
      else positional.add(a);
    }

    if (positional.isEmpty()
        || positional.get(0).equals("--help")
        || positional.get(0).equals("-h")) {
      System.out.println(HELP);
      return;
    }

    String command = positional.get(0);
    List<String> rest = positional.subList(1, positional.size());
    ResolutionReport report = new ResolutionReport(quietFlag);
    CtModel model = buildModel(scope);

    List<Entry> result;
    switch (command) {
      case "find-refs" -> {
        requireArg(command, rest, 1, "<name>");
        result = Commands.findRefs(model, rest.get(0), report);
      }
      case "find-def" -> {
        requireArg(command, rest, 1, "<name>");
        result = Commands.findDef(model, rest.get(0), report);
      }
      case "find-callers" -> {
        requireArg(command, rest, 1, "<Type.method>");
        result = Commands.findCallers(model, rest.get(0), report);
      }
      case "find-impls" -> {
        requireArg(command, rest, 1, "<name>");
        result = Commands.findImpls(model, rest.get(0), report);
      }
      case "find-unused-public" -> result = Commands.findUnusedPublic(model, report);
      default -> {
        System.err.println("Unknown command: " + command);
        System.err.println("Run bin/lsp-java --help for usage");
        System.exit(1);
        return;
      }
    }

    printEntries(result, jsonFlag);
  }

  private static void requireArg(String command, List<String> rest, int n, String usage) {
    if (rest.size() < n) {
      System.err.println("Usage: bin/lsp-java " + command + " " + usage);
      System.exit(1);
    }
  }

  /**
   * Default scope is every module needed for the issue's own acceptance demos with no flag
   * (ai-sidecar + game-core + ai, ~6.7s measured). {@code --scope=sidecar} narrows to just
   * ai-sidecar (~1.4s) for faster iteration on sidecar-local queries — see the design comment on
   * map-room#2404 for the measured numbers behind this split.
   */
  private static CtModel buildModel(String scope) {
    Launcher launcher = new Launcher();
    launcher.getEnvironment().setNoClasspath(true);
    launcher.getEnvironment().setComplianceLevel(21);
    launcher.getEnvironment().setIgnoreSyntaxErrors(true);
    launcher.addInputResource("game-app/ai-sidecar/src/main/java");
    if (!scope.equals("sidecar")) {
      launcher.addInputResource("game-app/game-core/src/main/java");
      launcher.addInputResource("game-app/ai/src/main/java");
    }
    return launcher.buildModel();
  }

  private static void printEntries(List<Entry> entries, boolean asJson) {
    if (asJson) {
      StringBuilder sb = new StringBuilder("[\n");
      for (int i = 0; i < entries.size(); i++) {
        sb.append("  ").append(entries.get(i).toJson());
        if (i < entries.size() - 1) sb.append(',');
        sb.append('\n');
      }
      sb.append(']');
      System.out.println(sb);
      return;
    }
    if (entries.isEmpty()) {
      System.out.println("(no results)");
      return;
    }
    for (Entry e : entries) System.out.println(e.toLine());
  }
}
