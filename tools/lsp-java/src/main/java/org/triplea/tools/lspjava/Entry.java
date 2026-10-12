package org.triplea.tools.lspjava;

/** One output row: a file position plus a kind label and optional extra text. */
public record Entry(String file, int line, int col, String kind, String text) {
  public Entry(String file, int line, int col, String kind) {
    this(file, line, col, kind, null);
  }

  /**
   * {@code file:line:col (kind)} — the default (non-JSON) output line, matching {@code bin/lsp-ts}.
   */
  public String toLine() {
    String suffix = text != null ? " — " + text : "";
    return file + ":" + line + ":" + col + " (" + kind + ")" + suffix;
  }

  /** Minimal hand-written JSON object — no library dependency for a 5-field shape. */
  public String toJson() {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"file\":").append(Json.quote(file)).append(',');
    sb.append("\"line\":").append(line).append(',');
    sb.append("\"col\":").append(col).append(',');
    sb.append("\"kind\":").append(Json.quote(kind));
    if (text != null) sb.append(",\"text\":").append(Json.quote(text));
    sb.append('}');
    return sb.toString();
  }
}
