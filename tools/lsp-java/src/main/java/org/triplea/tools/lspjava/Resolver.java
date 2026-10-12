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
    return allTypeReferenceOccurrences(model, target).resolved;
  }

  /**
   * Every {@link CtTypeReference} in the model matching {@code target}'s qualified name, counted
   * regardless of whether a usable position was ever found for it — the denominator for
   * find-refs/find-def's type-query coverage report, mirroring {@link #countAllOccurrencesOfName}'s
   * role for member queries (excluding the gated-bookkeeping shapes {@link #isGatedBookkeeping}
   * identifies, same as the resolved count does — see that method's doc). Without this denominator,
   * a reference {@link #positionOrParentPosition} genuinely can't resolve vanishes with no stderr
   * signal — the exact #3657 failure mode, just on the type-query path instead of the member one
   * (alpha's #174 review: the {@code DecisionHandler.class} literal was silently missing with zero
   * trace).
   */
  public static int countAllTypeReferenceOccurrences(CtModel model, CtType<?> target) {
    Occurrences occ = allTypeReferenceOccurrences(model, target);
    return occ.resolved.size() + occ.unresolvedCount;
  }

  private record Occurrences(List<CtTypeReference<?>> resolved, int unresolvedCount) {}

  /**
   * Shared walk for {@link #findTypeReferences} and {@link #countAllTypeReferenceOccurrences}.
   * Walking up to a parent's position (needed for #174's {@code .class}-literal case) means two
   * DISTINCT {@code CtTypeReference} nodes for the same textual occurrence (e.g. a declared
   * variable's type reference and a nested one Spoon builds for the same spot) can both resolve to
   * the SAME ancestor position. One surface occurrence should be one reported result, so resolved
   * references are deduped by their resolved (file, line, col) — by AST node identity, #174's own
   * fix would have double-reported several existing results. References with no walkable position
   * at all can't be deduped this way (nothing to key on); each is counted separately in the
   * denominator, which only risks over-counting (the safe direction — it can only make the coverage
   * line fire when it otherwise wouldn't, never hide a real gap).
   */
  private static Occurrences allTypeReferenceOccurrences(CtModel model, CtType<?> target) {
    List<CtTypeReference<?>> resolved = new ArrayList<>();
    Set<String> seenPositions = new LinkedHashSet<>();
    int unresolvedCount = 0;
    String qualifiedName = target.getQualifiedName();
    for (CtType<?> t : model.getAllTypes()) {
      for (CtTypeReference<?> ref : t.getElements(new TypeFilter<>(CtTypeReference.class))) {
        if (!qualifiedName.equals(ref.getQualifiedName())) continue;
        var pos = positionOrParentPosition(ref);
        if (pos == null) {
          // Only count toward the denominator when there was a real chance of a missed
          // reference. isGatedBookkeeping's refs are the SAME shape positionOrParentPosition
          // refuses to extend past (every unqualified self-call/self-field-read in the class) —
          // counting all of those as "unresolved" would make the coverage line fire on nearly
          // every type query regardless of whether anything is actually missing, drowning out
          // the signal it exists for.
          if (!isGatedBookkeeping(ref)) unresolvedCount++;
          continue;
        }
        String key = pos.getFile().getPath() + ":" + pos.getLine() + ":" + pos.getColumn();
        if (!seenPositions.add(key)) continue;
        resolved.add(ref);
      }
    }
    return new Occurrences(resolved, unresolvedCount);
  }

  /**
   * Resolves a usable position for {@code e}, matching exactly what alpha's #174 review expects:
   * the original 1-level fallback (try {@code e}'s own position, else its immediate parent's — this
   * is the behavior alpha already reviewed and approved for 18 of {@code DecisionHandler}'s 19 real
   * occurrences) PLUS one more level, needed only for the {@code .class}-literal case ({@code
   * DecisionHandler.class} nests the type reference inside a {@code CtTypeAccess} — itself
   * positionless — inside a synthetic field-read two levels up, not one; verified directly against
   * that exact reference, and against {@code WireStateApplier.java}'s {@code
   * StepNameMapper.resolve(...)}, which needs only 1 level).
   *
   * <p>The second-level extension is gated, not unconditional: it only fires when the immediate
   * parent (the node whose own position was invalid) is a plain, explicit {@code CtTypeAccess} —
   * the shape both the {@code .class} literal and an explicit static-member access share. Extending
   * past a {@code CtExecutableReference}, a {@code CtFieldReference}, or an implicit
   * (unqualified-access) {@code CtTypeAccess} is refused, because Spoon attaches declaring-type
   * links of exactly those shapes to EVERY unqualified self-call or self-field-read in a class —
   * extending past them unconditionally was verified (while building this fix) to turn 1 missing
   * reference into 10 new ones, none of which alpha's review asked for or saw. Gating only the
   * 1-to-2 extension — never touching whether depth 0 or depth 1 itself resolves — is what keeps
   * all 18 of alpha's originally-reviewed results unchanged: verified directly, widening to this
   * gated 2-level walk changes {@code find-refs DecisionHandler}'s count from 18 to exactly 19, not
   * 18 replaced by a different 28.
   */
  private static spoon.reflect.cu.SourcePosition positionOrParentPosition(
      spoon.reflect.declaration.CtElement e) {
    var ownPos = e.getPosition();
    if (ownPos != null && ownPos.isValidPosition()) return ownPos;

    var parent = e.getParent();
    if (parent == null) return null;
    var parentPos = parent.getPosition();
    if (parentPos != null && parentPos.isValidPosition()) return parentPos;

    boolean parentIsPlainTypeAccess =
        parent instanceof spoon.reflect.code.CtTypeAccess && !parent.isImplicit();
    if (!parentIsPlainTypeAccess) return null;

    var grandparent = parent.getParent();
    if (grandparent == null) return null;
    var grandparentPos = grandparent.getPosition();
    return (grandparentPos != null && grandparentPos.isValidPosition()) ? grandparentPos : null;
  }

  /**
   * True exactly when {@link #positionOrParentPosition} refused to even attempt the 2-level
   * extension for {@code ref} (both {@code ref}'s own and its immediate parent's positions are
   * invalid, and the parent isn't the plain {@code CtTypeAccess} shape the extension requires) — as
   * opposed to having attempted it and failed. The distinction matters for the coverage denominator
   * in {@link #allTypeReferenceOccurrences}: a gated ref was never going to be a reportable
   * reference regardless of walk depth (it's the same self-call/self-field-read bookkeeping {@link
   * #positionOrParentPosition}'s doc describes), so it shouldn't count as a "missed" occurrence any
   * more than it counts as a found one. A ref that reached the right shape and still found no
   * position is a genuine gap and should count.
   */
  private static boolean isGatedBookkeeping(spoon.reflect.declaration.CtElement e) {
    var ownPos = e.getPosition();
    if (ownPos != null && ownPos.isValidPosition()) return false;
    var parent = e.getParent();
    if (parent == null) return false;
    var parentPos = parent.getPosition();
    if (parentPos != null && parentPos.isValidPosition()) return false;
    return !(parent instanceof spoon.reflect.code.CtTypeAccess) || parent.isImplicit();
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
