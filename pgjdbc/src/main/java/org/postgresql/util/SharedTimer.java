/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.jdbc.ResourceLock;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.Cleaner;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SharedTimer {
  static class TimerCleanup implements Runnable {
    private final Timer timer;

    TimerCleanup(Timer timer) {
      this.timer = timer;
    }

    @Override
    public void run() {
      timer.cancel();
    }
  }

  // Incremented for each Timer created, this allows each to have a unique Timer name
  private static final AtomicInteger timerCount = new AtomicInteger(0);
  private static final Cleaner cleaner = Cleaner.create();

  private static final Logger LOGGER = Logger.getLogger(SharedTimer.class.getName());
  private volatile @Nullable Timer timer;
  private final AtomicInteger refCount = new AtomicInteger(0);
  private final ResourceLock lock = new ResourceLock();
  private Cleaner.Cleanable cleanable;

  public SharedTimer() {
  }

  public int getRefCount() {
    return refCount.get();
  }

  public Timer getTimer() {
    try (ResourceLock ignore = lock.obtain()) {
      Timer timer = this.timer;
      if (timer == null) {
        int index = timerCount.incrementAndGet();

        /*
         Temporarily switch contextClassLoader to the one that loaded this driver to avoid TimerThread preventing current
         contextClassLoader - which may be the ClassLoader of a web application - from being GC:ed.
         */
        final ClassLoader prevContextCL = Thread.currentThread().getContextClassLoader();
        try {
          /*
           Scheduled tasks should not need to use .getContextClassLoader, so we just reset it to null
           */
          Thread.currentThread().setContextClassLoader(null);

          this.timer = timer = new Timer("PostgreSQL-JDBC-SharedTimer-" + index, true);
          cleanable = cleaner.register(refCount, new TimerCleanup(timer));

        } finally {
          Thread.currentThread().setContextClassLoader(prevContextCL);
        }
      }
      refCount.incrementAndGet();
      return timer;
    }
  }

  public void releaseTimer() {
    try (ResourceLock ignore = lock.obtain()) {
      int count = refCount.decrementAndGet();
      if (count > 0) {
        // There are outstanding references to the timer so do nothing
        LOGGER.log(Level.FINEST, "Outstanding references still exist so not closing shared Timer");
      } else if (count == 0) {
        // This is the last usage of the Timer so cancel it so it's resources can be release.
        LOGGER.log(Level.FINEST, "No outstanding references to shared Timer, will cancel and close it");
        if (cleanable != null) {
          cleanable.clean();
          timer = null;
          cleanable = null;
        }
      } else {
        // Should not get here under normal circumstance, probably a bug in app code.
        LOGGER.log(Level.WARNING,
            "releaseTimer() called too many times; there is probably a bug in the calling code");
        refCount.set(0);
      }
    }
  }
}
