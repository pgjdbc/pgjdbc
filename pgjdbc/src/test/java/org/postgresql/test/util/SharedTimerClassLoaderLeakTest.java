package org.postgresql.test.util;

import org.postgresql.Driver;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import se.jiderhamn.classloader.PackagesLoadedOutsideClassLoader;
import se.jiderhamn.classloader.leak.JUnitClassloaderRunner;
import se.jiderhamn.classloader.leak.Leaks;

/**
 * Test case that verifies that the use of {@link org.postgresql.util.SharedTimer} within
 * {@link org.postgresql.Driver} does not cause ClassLoader leaks
 * @author Mattias Jiderhamn
 */
@RunWith(JUnitClassloaderRunner.class)
@PackagesLoadedOutsideClassLoader(packages = "org.postgresql", addToDefaults = true)
public class SharedTimerClassLoaderLeakTest {

  /** Starting a {@link org.postgresql.util.SharedTimer} should not cause ClassLoader leaks */
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
