package org.triplea.ai.sidecar;

/**
 * Deliberate, throwaway compile break for map-room/map-room#2015 — proves CI's cache-free
 * compileTestJava catches a real compile error with a real red run. This file and its sibling
 * spotless-break file are reverted before merge; see the PR description.
 */
class ThrowawayCompileBreakTest {
  void intentionallyDoesNotCompile() {
    thisMethodDoesNotExistAnywhere();
  }
}
