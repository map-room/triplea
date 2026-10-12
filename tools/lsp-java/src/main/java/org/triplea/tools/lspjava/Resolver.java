package org.triplea.tools.lspjava;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Symbol resolution over a Spoon {@link CtModel} — the Java-side analogue of {@code
 * resolveSymbol}/{@code collectRefs} in {@code scripts/lsp-ts.ts}.
 *
 * <p>Every method here is pure with respect to a given {@code CtModel}, so a unit test can build a
 * small real model (via {@code Launcher}) and assert against it directly, without going through the
 * CLI's argument parsing or process exit codes — the same split {@code lsp-ts.ts} uses between its
 * exported resolution internals and its {@code main()}.
 */
public final class Resolver {
  private Resolver() {}

  /** All top-level and nested types in the model whose simple name matches exactly. */
  public static List<CtType<?>> findTypesByName(CtModel model, String simpleName) {
    List<CtType<?>> out = new ArrayList<>();
    for (CtType<?> t : model.getAllTypes()) {
      if (t.getSimpleName().equals(simpleName)) out.add(t);
      collectNestedMatches(t, simpleName, out);
    }
    return out;
  }

  private static void collectNestedMatches(CtType<?> type, String simpleName, List<CtType<?>> out) {
    for (CtType<?> nested : type.getNestedTypes()) {
      if (nested.getSimpleName().equals(simpleName)) out.add(nested);
      collectNestedMatches(nested, simpleName, out);
    }
  }

  /**
   * Methods named {@code memberName}, optionally scoped to types whose simple name matches {@code
   * ownerName} (null = every type in the model). Multiple results are a real, reportable
   * possibility — overloads, and same-named methods on unrelated types when {@code ownerName} is
   * null — exactly the "distinct declarations share this name" case {@code lsp-ts.ts} already
   * reports rather than silently picking one.
   */
  public static List<CtMethod<?>> findMethodsByName(
      CtModel model, String ownerName, String memberName) {
    List<CtMethod<?>> out = new ArrayList<>();
    for (CtType<?> t : model.getAllTypes()) {
      if (ownerName != null && !t.getSimpleName().equals(ownerName)) continue;
      for (CtMethod<?> m : t.getMethods()) {
        if (m.getSimpleName().equals(memberName)) out.add(m);
      }
    }
    return out;
  }

  /**
   * Every simple name occurrence related to {@code memberName} in the whole model — method
   * declarations by that name (any owner) plus every invocation naming it — the denominator for the
   * coverage report, mirroring {@code allIdentifiers} in {@code lsp-ts.ts}'s {@code Resolution}.
   */
  public static int countAllOccurrencesOfName(CtModel model, String memberName) {
    int count = 0;
    for (CtType<?> t : model.getAllTypes()) {
      for (CtMethod<?> m : t.getMethods()) {
        if (m.getSimpleName().equals(memberName)) count++;
      }
      count +=
          t.getElements(new TypeFilter<>(CtInvocation.class)).stream()
              .filter(inv -> memberName.equals(inv.getExecutable().getSimpleName()))
              .count();
    }
    return count;
  }

  /**
   * The set of declaring-type simple names a call to {@code method} could be dispatched through:
   * {@code method}'s own declaring type, plus every ancestor interface/superclass that declares a
   * same-named method — i.e. every type whose static type at a call site could resolve to this
   * override.
   *
   * <p>This is {@code bin/lsp-java}'s one real gap against {@code bin/lsp-ts}: TypeScript's
   * language service resolves a call site to its exact symbol, so an interface-typed receiver is
   * never ambiguous. Spoon in {@code noClasspath} mode has no points-to analysis, so widening to
   * ancestors is necessary to find polymorphic call sites at all (a query for {@code
   * PurchaseExecutor.execute} finds nothing without it — the only real call site goes through a
   * field declared as the {@code DecisionExecutor} interface) — but it can also fold in a sibling
   * implementation's calls through that same interface (verified against the real sidecar: widening
   * to {@code DecisionExecutor} for {@code PurchaseExecutor.execute} also matches {@code
   * NoncombatMoveExecutor.execute} call sites, since both are invoked through the same
   * interface-typed field declarations). Callers of this method must report when widening happened
   * — see {@link ResolutionReport}.
   */
  public static Set<String> widenToAncestorInterfaceNames(CtMethod<?> method) {
    Set<String> names = new LinkedHashSet<>();
    CtType<?> owner = method.getDeclaringType();
    if (owner == null) return names;
    names.add(owner.getSimpleName());
    for (CtTypeReference<?> superRef : owner.getSuperInterfaces()) {
      CtType<?> superType = superRef.getTypeDeclaration();
      if (superType != null && declaresMethodNamed(superType, method.getSimpleName())) {
        names.add(superType.getSimpleName());
      }
    }
    CtTypeReference<?> superclassRef = owner.getSuperclass();
    if (superclassRef != null) {
      CtType<?> superclass = superclassRef.getTypeDeclaration();
      if (superclass != null && declaresMethodNamed(superclass, method.getSimpleName())) {
        names.add(superclass.getSimpleName());
      }
    }
    return names;
  }

  private static boolean declaresMethodNamed(CtType<?> type, String name) {
    for (CtMethod<?> m : type.getMethods()) {
      if (m.getSimpleName().equals(name)) return true;
    }
    return false;
  }

  /**
   * Every call site whose invoked executable's declaring type's simple name is in {@code
   * matchTypeNames}.
   */
  public static List<CtInvocation<?>> findCallSites(
      CtModel model, Set<String> matchTypeNames, String methodName) {
    List<CtInvocation<?>> out = new ArrayList<>();
    for (CtType<?> t : model.getAllTypes()) {
      for (CtInvocation<?> inv : t.getElements(new TypeFilter<>(CtInvocation.class))) {
        if (!methodName.equals(inv.getExecutable().getSimpleName())) continue;
        CtTypeReference<?> declType = inv.getExecutable().getDeclaringType();
        if (declType != null && matchTypeNames.contains(declType.getSimpleName())) {
          out.add(inv);
        }
      }
    }
    return out;
  }

  /** Classes in the model that {@code extends} or {@code implements} {@code target} directly. */
  public static List<Impl> findDirectImplementors(CtModel model, CtType<?> target) {
    List<Impl> out = new ArrayList<>();
    String targetName = target.getSimpleName();
    for (CtType<?> t : model.getAllTypes()) {
      CtTypeReference<?> superclass = t.getSuperclass();
      if (superclass != null && targetName.equals(superclass.getSimpleName())) {
        out.add(new Impl(t, "extends"));
        continue;
      }
      for (CtTypeReference<?> iface : t.getSuperInterfaces()) {
        if (targetName.equals(iface.getSimpleName())) {
          out.add(new Impl(t, "implements"));
          break;
        }
      }
    }
    return out;
  }

  /** A type implementing or extending a query target, and which relation it is. */
  public record Impl(CtType<?> type, String relation) {}

  /**
   * Every {@link CtTypeReference} in the model pointing at {@code target} — the type-reference half
   * of find-refs (field/parameter/return types, generic arguments, {@code instanceof}, casts,
   * {@code extends}/{@code implements} clauses; Spoon's own AST visits all of these as {@code
   * CtTypeReference} nodes). Excludes the declaration's own name.
   */
  public static List<CtTypeReference<?>> findTypeReferences(CtModel model, CtType<?> target) {
    List<CtTypeReference<?>> out = new ArrayList<>();
    String qualifiedName = target.getQualifiedName();
    for (CtType<?> t : model.getAllTypes()) {
      for (CtTypeReference<?> ref : t.getElements(new TypeFilter<>(CtTypeReference.class))) {
        if (!qualifiedName.equals(ref.getQualifiedName())) continue;
        if (positionOrParentPosition(ref) == null) continue;
        out.add(ref);
      }
    }
    return out;
  }

  /**
   * A {@link CtTypeReference}'s own position is frequently invalid even for a real, written-out
   * source occurrence: a static member access like {@code StepNameMapper.resolve(...)} wraps the
   * type name in a {@code CtTypeAccess} (or a method call wraps it in a {@code
   * CtExecutableReference}), and Spoon positions the WRAPPER, not the bare type-reference node
   * inside it. Falling back to the parent's position is what turns that real occurrence from a
   * silently-dropped reference into a reported one — verified directly against {@code
   * WireStateApplier.java}'s only use of {@code StepNameMapper}, which the naive
   * reference-position-only version missed entirely.
   */
  private static spoon.reflect.cu.SourcePosition positionOrParentPosition(
      spoon.reflect.declaration.CtElement e) {
    var pos = e.getPosition();
    if (pos != null && pos.isValidPosition()) return pos;
    var parent = e.getParent();
    if (parent == null) return null;
    var parentPos = parent.getPosition();
    return (parentPos != null && parentPos.isValidPosition()) ? parentPos : null;
  }

  /** Public for reuse by callers that need the same fallback to label an {@link Entry}. */
  public static spoon.reflect.cu.SourcePosition resolvedPosition(
      spoon.reflect.declaration.CtElement e) {
    return positionOrParentPosition(e);
  }

  /** Every read/write of {@code field} across the model. */
  public static List<CtFieldAccess<?>> findFieldAccesses(CtModel model, CtField<?> field) {
    List<CtFieldAccess<?>> out = new ArrayList<>();
    for (CtType<?> t : model.getAllTypes()) {
      for (CtFieldAccess<?> access : t.getElements(new TypeFilter<>(CtFieldAccess.class))) {
        if (access.getVariable() != null && access.getVariable().getDeclaration() == field) {
          out.add(access);
        }
      }
    }
    return out;
  }

  /**
   * Public methods in the model with no resolvable caller, applying the same ancestor-widening as
   * {@link #widenToAncestorInterfaceNames} — a method only reachable via an interface type is not
   * dead just because nothing calls it by its concrete declaring type's name.
   *
   * <p>Same honest caveat as {@code find-unused-exports} in {@code lsp-ts.ts}: reflection,
   * test-only usage via a different entry point, and framework callbacks (HTTP handler dispatch,
   * annotation-driven wiring) are invisible to a static call-site scan. This is a starting point
   * for a human to verify, not a deletion list.
   */
  public static List<CtMethod<?>> findUnusedPublicMethods(CtModel model) {
    List<CtMethod<?>> unused = new ArrayList<>();
    for (CtType<?> t : model.getAllTypes()) {
      for (CtMethod<?> m : t.getMethods()) {
        if (!m.isPublic()) continue;
        if (m.getSimpleName().equals("<init>")) continue;
        Set<String> matchTypeNames = widenToAncestorInterfaceNames(m);
        if (findCallSites(model, matchTypeNames, m.getSimpleName()).isEmpty()) {
          unused.add(m);
        }
      }
    }
    return unused;
  }
}
