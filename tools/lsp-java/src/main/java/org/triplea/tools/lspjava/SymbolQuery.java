package org.triplea.tools.lspjava;

/**
 * Parses a query string into an optional owning-type simple name, an optional member name, and a
 * bare (type-only) interpretation — the caller (each command) decides which fields it needs.
 *
 * <p>Mirrors {@code bin/lsp-ts}'s own choice to match by simple name rather than resolving a strict
 * fully-qualified path: {@code "PurchaseExecutor.execute"} and {@code "execute"} are both legal
 * queries. A package-qualified type like {@code "org.triplea.ai.sidecar.exec.PurchaseExecutor"} is
 * read as the bare type name {@code "PurchaseExecutor"} — package qualifiers are accepted but never
 * required or used to disambiguate further, matching {@code bin/lsp-ts}, which never disambiguates
 * by module path either.
 */
public record SymbolQuery(String raw, String ownerName, String memberName, String bareName) {

  /**
   * The member name to search for, for every command that treats this query as a member lookup:
   * {@code memberName} when the query was dotted, else {@code bareName} (a single undotted word
   * like {@code "execute"} is a bare member name for these commands — only {@code find-impls} ever
   * reads a single word as a type name instead, via {@code bareName} directly).
   */
  public String memberOrBare() {
    return memberName != null ? memberName : bareName;
  }

  /**
   * Splits on the last {@code '.'}. The segment after it is read as a member name only when it
   * starts with a lowercase letter (Java field/method convention) — this is what distinguishes
   * {@code "org.triplea.ai.sidecar.exec.PurchaseExecutor"} (a bare, package-qualified type) from
   * {@code "org.triplea.ai.sidecar.exec.PurchaseExecutor.execute"} (a member query). {@code
   * bareName} is always the last dotted segment, for commands that only ever mean "a type."
   */
  public static SymbolQuery parse(String raw) {
    String bareName = lastSegment(raw);
    int lastDot = raw.lastIndexOf('.');
    if (lastDot < 0) {
      return new SymbolQuery(raw, null, null, bareName);
    }
    String tail = raw.substring(lastDot + 1);
    boolean tailIsMember = !tail.isEmpty() && Character.isLowerCase(tail.charAt(0));
    if (!tailIsMember) {
      return new SymbolQuery(raw, null, null, bareName);
    }
    String ownerPart = raw.substring(0, lastDot);
    String ownerSimpleName = lastSegment(ownerPart);
    return new SymbolQuery(raw, ownerSimpleName, tail, bareName);
  }

  private static String lastSegment(String dotted) {
    int i = dotted.lastIndexOf('.');
    return i < 0 ? dotted : dotted.substring(i + 1);
  }
}
