package org.triplea.ai.sidecar.wire;

/**
 * Wire-format pairwise relationship between two TripleA-known players.
 * The pair (a, b) is emitted in lex-sorted order (a &lt; b) so the applier never
 * needs to handle both orderings. {@code kind} is one of "war", "allied", "neutral".
 */
public record WireRelationship(String a, String b, String kind) {}
