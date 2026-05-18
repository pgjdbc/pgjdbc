/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.NIOInputStream;
import org.postgresql.core.PGStream;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background thread that detects incoming async data on the connection using
 * its own NIO Selector. Never reads from the socket — only checks readability
 * and sets a flag for the main thread to process.
 *
 * <p>Uses a <em>separate</em> Selector from the one in NIOInputStream to avoid
 * thread-safety issues with concurrent select()/selectNow() calls and the
 * shared selectedKeys() set.
 */
class NotificationPoller extends Thread {

  private static final Logger LOGGER = Logger.getLogger(NotificationPoller.class.getName());

  private volatile @org.checkerframework.checker.nullness.qual.Nullable Selector pollerSelector;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private volatile boolean dataAvailable;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition signalCondition = lock.newCondition();

  NotificationPoller(NIOInputStream nioInput, PGStream pgStream) {
    super("pgjdbc-notify-" + pgStream.getHostSpec());
    setDaemon(true);
    try {
      SocketChannel channel = nioInput.getChannel();
      this.pollerSelector = Selector.open();
      channel.register(this.pollerSelector, SelectionKey.OP_READ);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "Failed to create poller Selector", e);
      this.pollerSelector = null;
    }
  }

  /**
   * Returns true if the Selector detected incoming data.
   */
  boolean hasDataAvailable() {
    return dataAvailable;
  }

  /**
   * Clear the flag and wake the poller to resume checking.
   */
  void clearDataAvailable() {
    lock.lock();
    try {
      dataAvailable = false;
      signalCondition.signalAll();
    } finally {
      lock.unlock();
    }
  }

  void shutdown() {
    running.set(false);
    Selector sel = pollerSelector;
    if (sel != null) {
      sel.wakeup();
    }
    lock.lock();
    try {
      signalCondition.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void run() {
    LOGGER.log(Level.FINEST, "NotificationPoller started");
    Selector sel = pollerSelector;
    if (sel == null) {
      return;
    }
    try {
      while (running.get()) {
        try {
          // Block until data arrives or timeout — own Selector, no contention
          int ready = sel.select(500);
          sel.selectedKeys().clear();
          if (ready > 0) {
            dataAvailable = true;
            lock.lock();
            try {
              while (dataAvailable && running.get()) {
                signalCondition.await();
              }
            } finally {
              lock.unlock();
            }
          }
        } catch (InterruptedException e) {
          // shutdown
        } catch (IOException e) {
          if (running.get()) {
            LOGGER.log(Level.FINE, "NotificationPoller I/O error", e);
            break;
          }
        }
      }
    } finally {
      try {
        sel.close();
      } catch (IOException e) {
        // best effort
      }
      pollerSelector = null;
      LOGGER.log(Level.FINEST, "NotificationPoller stopped");
    }
  }
}
