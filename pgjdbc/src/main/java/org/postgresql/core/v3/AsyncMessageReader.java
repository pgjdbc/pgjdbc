/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.PGStream;
import org.postgresql.core.ProtocolMessage;

import java.io.EOFException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Daemon thread that continuously reads complete protocol messages from a {@link PGStream}
 * and places them into a {@link MessageQueue} for consumption by the query executor.
 *
 * <p>The reader thread blocks on the socket when no data is available. When a message is
 * fully read, it is enqueued. If the queue is full (backpressure), the reader blocks until
 * space is available.</p>
 *
 * <p>On I/O errors, the error is enqueued so the consumer can rethrow it, and the reader
 * stops.</p>
 */
final class AsyncMessageReader implements Runnable {
  private static final Logger LOGGER = Logger.getLogger(AsyncMessageReader.class.getName());

  private final PGStream pgStream;
  private final MessageQueue queue;
  private volatile boolean running = true;
  private final Thread thread;

  /**
   * Creates and starts the async reader thread.
   *
   * @param pgStream the stream to read from
   * @param queue the queue to write messages into
   */
  @SuppressWarnings({"all", "initialization", "argument", "assignment"})
  AsyncMessageReader(PGStream pgStream, MessageQueue queue) {
    this.pgStream = pgStream;
    this.queue = queue;
    this.thread = new Thread(this, "pgjdbc-async-reader");
    this.thread.setDaemon(true);
    this.thread.start();
  }

  @Override
  public void run() {
    try {
      while (running && !Thread.currentThread().isInterrupted()) {
        ProtocolMessage msg = pgStream.readFullMessage();
        queue.put(msg);
      }
    } catch (EOFException e) {
      if (running) {
        try {
          queue.putError(e);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    } catch (IOException e) {
      if (running) {
        LOGGER.log(Level.FINE, "Async reader encountered I/O error", e);
        try {
          queue.putError(e);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Signals the reader to stop and interrupts the thread.
   * After this call, no more messages will be enqueued.
   */
  void shutdown() {
    running = false;
    thread.interrupt();
  }

  /**
   * Returns whether the reader thread is still alive.
   *
   * @return true if the reader thread is running
   */
  boolean isAlive() {
    return thread.isAlive();
  }
}
