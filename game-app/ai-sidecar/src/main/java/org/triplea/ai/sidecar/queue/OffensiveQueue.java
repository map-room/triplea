package org.triplea.ai.sidecar.queue;

import java.util.concurrent.ConcurrentLinkedDeque;

public final class OffensiveQueue {
  private final String sessionId;
  private final ConcurrentLinkedDeque<Object> jobs = new ConcurrentLinkedDeque<>();

  public OffensiveQueue(final String sessionId) {
    this.sessionId = sessionId;
  }

  public String sessionId() {
    return sessionId;
  }

  public void enqueue(final Object job) {
    jobs.addLast(job);
  }

  public Object poll() {
    return jobs.pollFirst();
  }

  public int size() {
    return jobs.size();
  }

  public boolean isEmpty() {
    return jobs.isEmpty();
  }
}
