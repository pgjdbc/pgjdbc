/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.util;

import org.postgresql.core.Logger;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicInteger;

public class SharedTimer {
  // Incremented for each Timer created, this allows each to have a unique Timer name
  private static AtomicInteger timerCount = new AtomicInteger(0);

  private Logger log;
  private volatile Timer timer = null;
  private AtomicInteger refCount = new AtomicInteger(0);

  public SharedTimer(Logger log) {
    this.log = log;
  }

  public int getRefCount() {
    return refCount.get();
  }

  public synchronized Timer getTimer() {
    if (timer == null) {
      int index = timerCount.incrementAndGet();

      /*
       Temporarily switch contextClassLoader to the one that loaded this driver to avoid TimerThread preventing current
       contextClassLoader - which may be the ClassLoader of a web application - from being GC:ed.
       */
      final ClassLoader prevContextCL = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());

        /*
         Do this as a privileged action, not because it requires privileges but to avoid that the inherited
         AccessControlContext of the TimerThread contains references to the ClassLoader of calling code, potentially
         preventing them from being garbage collected after a web app redeploy.
         */
        timer = AccessController.doPrivileged(new PrivilegedAction<Timer>() {
          @Override
          public Timer run() {
            return new Timer("PostgreSQL-JDBC-SharedTimer-" + index, true);
          }
        });
      } finally {
        Thread.currentThread().setContextClassLoader(prevContextCL);
      }
    }
    refCount.incrementAndGet();
    return timer;
  }

  public synchronized void releaseTimer() {
    int count = refCount.decrementAndGet();
    if (count > 0) {
      // There are outstanding references to the timer so do nothing
      log.debug("Outstanding references still exist so not closing shared Timer");
    } else if (count == 0) {
      // This is the last usage of the Timer so cancel it so it's resources can be release.
      log.debug("No outstanding references to shared Timer, will cancel and close it");
      if (timer != null) {
        timer.cancel();
        timer = null;
      }
    } else {
      // Should not get here under normal circumstance, probably a bug in app code.
      log.debug(
          "releaseTimer() called too many times; there is probably a bug in the calling code");
      refCount.set(0);
    }
  }
}
