/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import org.postgresql.Driver;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import se.jiderhamn.classloader.PackagesLoadedOutsideClassLoader;
import se.jiderhamn.classloader.leak.JUnitClassloaderRunner;
import se.jiderhamn.classloader.leak.Leaks;

/**
 * <p>Test case that verifies that the use of {@link org.postgresql.util.SharedTimer} within
 * {@link org.postgresql.Driver} does not cause ClassLoader leaks.</p>
 *
 * <p>The class is placed in {@code jdbc41} package so it won't be tested in JRE6 build.
 * {@link JUnitClassloaderRunner} does not support JRE6, so we have to skip the test there.</p>
 *
 * @author Mattias Jiderhamn
 */
@RunWith(JUnitClassloaderRunner.class)
@PackagesLoadedOutsideClassLoader(packages = "org.postgresql", addToDefaults = true)
public class SharedTimerClassLoaderLeakTest {

  /** Starting a {@link org.postgresql.util.SharedTimer} should not cause ClassLoader leaks. */
  @Leaks(false)
  @Test
  public void sharedTimerDoesNotCauseLeak() {
    Driver.getSharedTimer().getTimer(); // Start timer
  }

  @After
  public void tearDown() {
    Driver.getSharedTimer().releaseTimer();
  }
}
