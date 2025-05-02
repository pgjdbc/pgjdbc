/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Properties;

/**
 * Helper class to instantiate objects. Note: the class is <b>NOT</b> public API, so it is subject
 * to change.
 */
public class ObjectFactory {

  /**
   * Instantiates a class using the appropriate constructor. If a constructor with a single
   * Propertiesparameter exists, it is used. Otherwise, if tryString is true a constructor with a
   * single String argument is searched if it fails, or tryString is true a no argument constructor
   * is tried.
   *
   * @param <T> type of expected class
   * @param expectedClass expected class of type T, if the classname instantiated doesn't match
   *                     the expected type of this class this method will fail
   * @param classname name of the class to instantiate
   * @param info parameter to pass as Properties
   * @param tryString whether to look for a single String argument constructor
   * @param stringarg parameter to pass as String
   * @return the instantiated class
   * @throws ClassNotFoundException if something goes wrong
   * @throws SecurityException if something goes wrong
   * @throws NoSuchMethodException if something goes wrong
   * @throws IllegalArgumentException if something goes wrong
   * @throws InstantiationException if something goes wrong
   * @throws IllegalAccessException if something goes wrong
   * @throws InvocationTargetException if something goes wrong
   */
  public static <T> T instantiate(Class<T> expectedClass, String classname, Properties info,
      boolean tryString,
      @Nullable String stringarg)
      throws ClassNotFoundException, SecurityException, NoSuchMethodException,
          IllegalArgumentException, InstantiationException, IllegalAccessException,
          InvocationTargetException {
    @Nullable Object[] args = {info};
    Constructor<?> ctor = null;
    Class<? extends T> cls;
    try {
      //first use the TCCL, but fall back to the CL that loaded this class if the TCCL fails
      cls = Class.forName(classname, false, Thread.currentThread().getContextClassLoader()).asSubclass(expectedClass);
    } catch (ClassNotFoundException e) {
      cls = Class.forName(classname).asSubclass(expectedClass);
    }
    try {
      ctor = cls.getConstructor(Properties.class);
    } catch (NoSuchMethodException ignored) {
      // Try String-based constructor later
    }
    if (tryString && ctor == null) {
      try {
        ctor = cls.getConstructor(String.class);
        args = new String[]{stringarg};
      } catch (NoSuchMethodException ignored) {
        // Try no-argument constructor below
      }
    }
    if (ctor == null) {
      ctor = cls.getConstructor();
      args = new Object[0];
    }
    return ctor.newInstance(args);
  }
}
