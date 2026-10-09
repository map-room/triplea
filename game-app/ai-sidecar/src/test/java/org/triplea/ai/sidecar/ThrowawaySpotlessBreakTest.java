package org.triplea.ai.sidecar;

/**
 * Deliberate, throwaway spotless (google-java-format) violation for map-room/map-room#2015 — proves
 * CI's cache-free spotlessJavaCheck catches a real formatting break with a real red run. Reverted
 * before merge.
 */
class ThrowawaySpotlessBreakTest {
        void badlyIndented() {
    int    x =    1;
            System.out.println(x);
  }
}
