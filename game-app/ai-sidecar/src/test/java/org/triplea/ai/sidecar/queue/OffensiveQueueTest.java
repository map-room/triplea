package org.triplea.ai.sidecar.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

class OffensiveQueueTest {
  @Test
  void enqueueIncrementsSize() {
    final OffensiveQueue q = new OffensiveQueue("s-1");
    assertEquals(0, q.size());
    q.enqueue((Callable<String>) () -> "x");
    assertEquals(1, q.size());
  }

  @Test
  void fifoOrderPreserved() throws Exception {
    final OffensiveQueue q = new OffensiveQueue("s-1");
    q.enqueue((Callable<String>) () -> "a");
    q.enqueue((Callable<String>) () -> "b");
    assertEquals("a", ((Callable<String>) q.poll()).call().toString());
    assertEquals("b", ((Callable<String>) q.poll()).call().toString());
  }

  @Test
  void pollOnEmptyReturnsNull() {
    final OffensiveQueue q = new OffensiveQueue("s-1");
    assertEquals(null, q.poll());
  }

  @Test
  void sessionIdAccessible() {
    final OffensiveQueue q = new OffensiveQueue("s-7");
    assertEquals("s-7", q.sessionId());
  }

  @Test
  void isEmptyReflectsState() {
    final OffensiveQueue q = new OffensiveQueue("s-1");
    assertTrue(q.isEmpty());
    q.enqueue((Callable<String>) () -> "x");
    assertFalse(q.isEmpty());
  }
}
