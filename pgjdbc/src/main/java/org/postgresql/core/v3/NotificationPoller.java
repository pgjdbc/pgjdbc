/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.NIOInputStream;
import org.postgresql.core.PGStream;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background thread that detects incoming async data on the connection using
 * NIO Selector. Never reads from the socket — only checks readability and
 * sets a flag for the main thread to process.
 *
 * <p>Uses {@link NIOInputStream#waitForData(long)} which calls
 * {@code Selector.select(timeout)} — blocks efficiently without CPU waste.
 *
 * <p>Thread safety: the poller only calls waitForData() when dataAvailable is
 * false (i.e., the main thread is not reading). When the main thread is executing
 * a query, the poller is parked waiting for clearDataAvailable() to be called.
 */
class NotificationPoller extends Thread {

  private static final Logger LOGGER = Logger.getLogger(NotificationPoller.class.getName());

  private final NIOInputStream nioInput;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private volatile boolean dataAvailable;
  private final Object signal = new Object();

  NotificationPoller(NIOInputStream nioInput, PGStream pgStream) {
    super("pgjdbc-notify-" + pgStream.getHostSpec());
    setDaemon(true);
    this.nioInput = nioInput;
  }

  /**
   * Returns true if the Selector detected incoming data.
   * Main thread should check this and process async messages.
   */
  boolean hasDataAvailable() {
    return dataAvailable;
  }

  /**
   * Clear the flag and wake the poller to resume checking.
   */
  void clearDataAvailable() {
    synchronized (signal) {
      dataAvailable = false;
      signal.notifyAll();
    }
  }

  void shutdown() {
    running.set(false);
    nioInput.wakeup();
    synchronized (signal) {
      signal.notifyAll();
    }
  }

  @Override
  public void run() {
    LOGGER.log(Level.FINEST, "NotificationPoller started");
    while (running.get()) {
      try {
        // Block until data is available (no CPU waste)
        if (nioInput.waitForData(500)) {
          dataAvailable = true;
          // Wait until main thread clears the flag
          synchronized (signal) {
            while (dataAvailable && running.get()) {
              signal.wait();
            }
          }
        }
      } catch (InterruptedException e) {
        // shutdown or wakeup
      } catch (IOException e) {
        if (running.get()) {
          LOGGER.log(Level.FINE, "NotificationPoller I/O error", e);
          break;
        }
      }
    }
    LOGGER.log(Level.FINEST, "NotificationPoller stopped");
  }
}
