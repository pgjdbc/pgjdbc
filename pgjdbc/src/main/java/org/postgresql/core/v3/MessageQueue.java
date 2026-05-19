/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.ProtocolMessage;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe queue for passing protocol messages from the reader thread to the consumer.
 *
 * <p>Each entry is either a valid {@link ProtocolMessage} or an {@link IOException} indicating
 * a read failure. The consumer must check {@link Entry#getError()} before accessing the message.</p>
 */
final class MessageQueue {
  private final BlockingQueue<Entry> queue;

  /**
   * Creates a new message queue with the given capacity.
   *
   * @param capacity maximum number of buffered messages before the reader blocks
   */
  MessageQueue(int capacity) {
    this.queue = new ArrayBlockingQueue<>(capacity);
  }

  /**
   * Puts a successfully read message into the queue. Blocks if the queue is full.
   *
   * @param message the protocol message
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  void put(ProtocolMessage message) throws InterruptedException {
    queue.put(new Entry(message, null));
  }

  /**
   * Puts an error into the queue to signal the consumer of a read failure.
   *
   * @param error the I/O error that occurred
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  void putError(IOException error) throws InterruptedException {
    queue.put(new Entry(null, error));
  }

  /**
   * Takes the next entry from the queue, blocking until one is available.
   *
   * @return the next entry
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  Entry take() throws InterruptedException {
    return queue.take();
  }

  /**
   * Polls for the next entry without blocking.
   *
   * @return the next entry, or null if the queue is empty
   */
  @Nullable Entry poll() {
    return queue.poll();
  }

  /**
   * Polls for the next entry with a timeout.
   *
   * @param timeoutMs maximum time to wait in milliseconds
   * @return the next entry, or null if the timeout elapsed
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  @Nullable Entry poll(long timeoutMs) throws InterruptedException {
    return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Drains all remaining entries from the queue.
   */
  void clear() {
    queue.clear();
  }

  /**
   * Returns the number of entries currently in the queue.
   *
   * @return current queue size
   */
  int size() {
    return queue.size();
  }

  /**
   * An entry in the message queue: either a message or an error.
   */
  static final class Entry {
    private final @Nullable ProtocolMessage message;
    private final @Nullable IOException error;

    Entry(@Nullable ProtocolMessage message, @Nullable IOException error) {
      this.message = message;
      this.error = error;
    }

    /**
     * Returns the protocol message, or null if this entry represents an error.
     *
     * @return the message, or null
     */
    @Nullable ProtocolMessage getMessage() {
      return message;
    }

    /**
     * Returns the error, or null if this entry contains a valid message.
     *
     * @return the error, or null
     */
    @Nullable IOException getError() {
      return error;
    }

    /**
     * Returns true if this entry represents an error.
     *
     * @return true if error
     */
    boolean isError() {
      return error != null;
    }
  }
}
