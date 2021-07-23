/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SharedTimer {
  // Incremented for each Timer created, this allows each to have a unique Timer name
  private static final AtomicInteger timerCount = new AtomicInteger(0);

  private static final Logger LOGGER = Logger.getLogger(SharedTimer.class.getName());

  private static final class DeferredLoader {
    static final SharedTimer INSTANCE = new SharedTimer();
  }

  private final AtomicInteger refCount = new AtomicInteger(0);
  private final StampedLock lock = new StampedLock();
  private @Nullable Timer timer;

  public SharedTimer() {
  }

  public static SharedTimer getSharedInstance() {
    return DeferredLoader.INSTANCE;
  }

  public int getRefCount() {
    return refCount.get();
  }

  public Timer getTimer() {
    //important to do this first to prevent releaseTimer from nulling out timer reference
    refCount.incrementAndGet();

    long stamp = lock.tryOptimisticRead();
    Timer timer = this.timer;
    if (!lock.validate(stamp)) {
      stamp = lock.readLock();
      try {
        timer = this.timer;
      } finally {
        lock.unlockRead(stamp);
      }
    }
    if (timer == null) {
      stamp = lock.writeLock();
      try {
        //now that we have write lock, need to double check
        timer = this.timer;
        if (timer == null) {
          int index = timerCount.incrementAndGet();

          /*
           Temporarily switch contextClassLoader to the one that loaded this driver to avoid TimerThread preventing current
           contextClassLoader - which may be the ClassLoader of a web application - from being GC:ed.
           */
          final ClassLoader prevContextCL = Thread.currentThread().getContextClassLoader();
          try {
            /*
             Scheduled tasks whould not need to use .getContextClassLoader, so we just reset it to null
             */
            Thread.currentThread().setContextClassLoader(null);

            this.timer = timer = new Timer("PostgreSQL-JDBC-SharedTimer-" + index, true);
          } finally {
            Thread.currentThread().setContextClassLoader(prevContextCL);
          }
        }
      } finally {
        lock.unlockWrite(stamp);
      }
    }
    return timer;
  }

  public void releaseTimer() {
    int count = refCount.decrementAndGet();
    if (count > 0) {
      // There are outstanding references to the timer so do nothing
      LOGGER.log(Level.FINEST, "Outstanding references still exist so not closing shared Timer");
    } else if (count == 0) {
      // This is the last usage of the Timer so cancel it so it's resources can be release.
      long stamp = lock.writeLock();
      try {
        //need to double check the count with the lock held
        if (refCount.get() == 0) {
          LOGGER.log(Level.FINEST, "No outstanding references to shared Timer, will cancel and close it");
          if (timer != null) {
            timer.cancel();
            timer = null;
          }
        }
      } finally {
        lock.unlockWrite(stamp);
      }
    } else {
      // Should not get here under normal circumstance, probably a bug in app code.
      LOGGER.log(Level.WARNING,
          "releaseTimer() called too many times; there is probably a bug in the calling code");
      refCount.compareAndSet(count, 0);
    }
  }
}
