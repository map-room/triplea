package org.triplea.tools.lspjava;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

/**
 * The five CLI commands, each a thin composition of {@link Resolver} + {@link ResolutionReport}.
 */
public final class Commands {
  private Commands() {}

  public static List<Entry> findDef(CtModel model, String query, ResolutionReport report) {
    List<CtType<?>> types = Resolver.findTypesByName(model, query);
    if (!types.isEmpty()) {
      report.reportTypeResolution("find-def", query, types, types.size());
      return types.stream().map(t -> entryFor(t, t.isInterface() ? "interface" : "class")).toList();
    }

    SymbolQuery q = SymbolQuery.parse(query);
    List<CtMethod<?>> methods = Resolver.findMethodsByName(model, q.ownerName(), q.memberOrBare());
    if (methods.isEmpty()) report.failUnresolvedType("find-def", query);
    int allOccurrences = Resolver.countAllOccurrencesOfName(model, q.memberOrBare());
    report.reportMemberResolution("find-def", q, methods, Set.of(), methods.size(), allOccurrences);
    return methods.stream().map(m -> entryFor(m, "method")).toList();
  }

  public static List<Entry> findRefs(CtModel model, String query, ResolutionReport report) {
    List<CtType<?>> types = Resolver.findTypesByName(model, query);
    if (!types.isEmpty()) {
      List<Entry> out = new ArrayList<>();
      int allOccurrences = 0;
      for (CtType<?> t : types) {
        out.add(entryFor(t, t.isInterface() ? "interface" : "class"));
        for (CtTypeReference<?> ref : Resolver.findTypeReferences(model, t)) {
          out.add(entryFor(ref, "reference"));
        }
        allOccurrences += Resolver.countAllTypeReferenceOccurrences(model, t);
      }
      // out.size() includes the declaration entries too (one per matched type), which
      // countAllTypeReferenceOccurrences doesn't count — add them so a fully-accounted-for
      // answer doesn't print a spurious "N of M" short by exactly types.size().
      report.reportTypeResolution(
          "find-refs", query, types, out.size(), allOccurrences + types.size());
      return out;
    }

    SymbolQuery q = SymbolQuery.parse(query);
    List<CtMethod<?>> methods = Resolver.findMethodsByName(model, q.ownerName(), q.memberOrBare());
    List<Entry> fieldEntries = findFieldRefs(model, q);
    if (methods.isEmpty() && fieldEntries == null) report.failUnresolvedMember("find-refs", q);
    if (methods.isEmpty()) return fieldEntries;

    Set<String> matchTypeNames = widenAcrossAll(methods);
    List<CtInvocation<?>> calls = Resolver.findCallSites(model, matchTypeNames, q.memberOrBare());
    int allOccurrences = Resolver.countAllOccurrencesOfName(model, q.memberOrBare());
    report.reportMemberResolution(
        "find-refs", q, methods, matchTypeNames, calls.size(), allOccurrences);

    List<Entry> out = new ArrayList<>();
    for (CtMethod<?> m : methods) out.add(entryFor(m, "method"));
    for (CtInvocation<?> inv : calls) out.add(entryFor(inv, "call"));
    return out;
  }

  /** Returns null (not empty) when no field by this name exists either, so the caller can fail. */
  private static List<Entry> findFieldRefs(CtModel model, SymbolQuery q) {
    List<CtField<?>> fields = new ArrayList<>();
    for (CtType<?> t : model.getAllTypes()) {
      if (q.ownerName() != null && !t.getSimpleName().equals(q.ownerName())) continue;
      CtField<?> f = t.getField(q.memberOrBare());
      if (f != null) fields.add(f);
    }
    if (fields.isEmpty()) return null;
    List<Entry> out = new ArrayList<>();
    for (CtField<?> f : fields) {
      out.add(entryFor(f, "field"));
      for (CtFieldAccess<?> access : Resolver.findFieldAccesses(model, f)) {
        out.add(entryFor(access, "field-access"));
      }
    }
    return out;
  }

  public static List<Entry> findCallers(CtModel model, String query, ResolutionReport report) {
    SymbolQuery q = SymbolQuery.parse(query);
    List<CtMethod<?>> methods = Resolver.findMethodsByName(model, q.ownerName(), q.memberOrBare());
    if (methods.isEmpty()) report.failUnresolvedMember("find-callers", q);

    Set<String> matchTypeNames = widenAcrossAll(methods);
    List<CtInvocation<?>> calls = Resolver.findCallSites(model, matchTypeNames, q.memberOrBare());
    int allOccurrences = Resolver.countAllOccurrencesOfName(model, q.memberOrBare());
    report.reportMemberResolution(
        "find-callers", q, methods, matchTypeNames, calls.size(), allOccurrences);

    return calls.stream().map(inv -> entryFor(inv, "call")).toList();
  }

  public static List<Entry> findImpls(CtModel model, String query, ResolutionReport report) {
    List<CtType<?>> types = Resolver.findTypesByName(model, query);
    if (types.isEmpty()) report.failUnresolvedType("find-impls", query);

    List<Entry> out = new ArrayList<>();
    for (CtType<?> target : types) {
      if (!target.isInterface() && !target.isClass()) continue;
      for (Resolver.Impl impl : Resolver.findDirectImplementors(model, target)) {
        out.add(entryFor(impl.type(), impl.relation()));
      }
    }
    report.reportTypeResolution("find-impls", query, types, out.size());
    return out;
  }

  public static List<Entry> findUnusedPublic(CtModel model, ResolutionReport report) {
    List<CtMethod<?>> unused = Resolver.findUnusedPublicMethods(model);
    report.note(
        String.format(
            "lsp-java find-unused-public: %d public method(s) with no resolvable caller "
                + "(static analysis only — reflection, test-only entry points, and framework "
                + "callback dispatch are invisible to this; verify before deleting).",
            unused.size()));
    return unused.stream()
        .map(
            m -> {
              CtType<?> owner = m.getDeclaringType();
              String label =
                  owner != null
                      ? owner.getQualifiedName() + "." + m.getSimpleName()
                      : m.getSimpleName();
              return entryFor(m, "unused-public", label);
            })
        .toList();
  }

  private static Set<String> widenAcrossAll(List<CtMethod<?>> methods) {
    Set<String> names = new java.util.LinkedHashSet<>();
    for (CtMethod<?> m : methods) names.addAll(Resolver.widenToAncestorInterfaceNames(m));
    return names;
  }

  private static Entry entryFor(spoon.reflect.declaration.CtElement e, String kind) {
    return entryFor(e, kind, null);
  }

  private static final Path REPO_ROOT = Path.of("").toAbsolutePath();

  private static Entry entryFor(spoon.reflect.declaration.CtElement e, String kind, String text) {
    // Falls back to the parent element's position when this node has none of its own — see
    // Resolver.resolvedPosition's doc for why that's a real occurrence, not a synthetic one.
    var pos = Resolver.resolvedPosition(e);
    if (pos == null) {
      return new Entry("(no position)", 0, 0, kind, text);
    }
    String file = relativize(pos.getFile().getPath());
    return new Entry(file, pos.getLine(), pos.getColumn(), kind, text);
  }

  /**
   * Relative to the CWD the tool was invoked from (the repo root, once `bin/lsp-java` cd's there).
   */
  private static String relativize(String absolutePath) {
    try {
      return REPO_ROOT.relativize(Path.of(absolutePath)).toString();
    } catch (IllegalArgumentException e) {
      return absolutePath; // different filesystem root (e.g. a symlinked /tmp on macOS) — fall back
    }
  }
}
