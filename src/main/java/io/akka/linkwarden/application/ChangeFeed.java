package io.akka.linkwarden.application;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * What changed, for the screens watching. SPEC-001 R99.
 *
 * <p>Every screen holding a subscription registers a queue here, and each change is offered to all
 * of them. A queue that has filled is dropped from rather than blocking the writer: a browser tab
 * that stopped reading must not be able to stall the request that changed something.
 *
 * <p>This is in-process on purpose. A subscription is held by one node for as long as it lasts, so
 * a change made on another node is one the holder does not see; what closes that is the re-read a
 * reconnect performs, which is the same mechanism that closes a dropped connection.
 */
public final class ChangeFeed {

  /** One change, named by what it was about rather than by what it did. */
  public record Change(String kind, int id, Instant at) {

    /** Sent when nothing has changed for a while, so a silent connection is still known to be up. */
    public static Change heartbeat(Instant at) {
      return new Change("heartbeat", 0, at);
    }

    public boolean isHeartbeat() {
      return kind.equals("heartbeat");
    }
  }

  private static final int QUEUE_DEPTH = 256;

  /** How long one wait blocks for before it looks up. */
  private static final long SLICE_MILLIS = 250;

  private final List<BlockingQueue<Change>> subscribers = new CopyOnWriteArrayList<>();

  public void publish(Change change) {
    for (BlockingQueue<Change> queue : subscribers) queue.offer(change);
  }

  public BlockingQueue<Change> subscribe() {
    BlockingQueue<Change> queue = new LinkedBlockingQueue<>(QUEUE_DEPTH);
    subscribers.add(queue);
    return queue;
  }

  public void unsubscribe(BlockingQueue<Change> queue) {
    subscribers.remove(queue);
  }

  /**
   * The next change, or a heartbeat when none arrives inside the window.
   *
   * <p>Waited for in short slices rather than in one long one. A subscription that the reader has
   * gone away from is only noticed when this returns, so the slice is also how long a shutdown
   * waits for the last reader; a single long wait held the runtime open past its own limit.
   */
  public Change nextOrHeartbeat(BlockingQueue<Change> queue, long seconds) {
    long slices = Math.max(1, seconds * 1000 / SLICE_MILLIS);
    for (long i = 0; i < slices; i++) {
      try {
        Change change = queue.poll(SLICE_MILLIS, TimeUnit.MILLISECONDS);
        if (change != null) return change;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return Change.heartbeat(Instant.now());
  }

  public int subscriberCount() {
    return subscribers.size();
  }
}
