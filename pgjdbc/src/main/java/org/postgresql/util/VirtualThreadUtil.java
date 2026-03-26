/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Virtual thread utilities.
 */
public class VirtualThreadUtil {

  private static final @Nullable Method IS_VIRTUAL_METHOD;

  static {
    Method method = null;
    try {
      method = Thread.class.getMethod( "isVirtual");
    } catch (NoSuchMethodException e) {
      // Ignore
    }
    IS_VIRTUAL_METHOD = method;
  }

  /**
   * Returns whether the current thread is a virtual thread
   *
   * @return whether the current thread is a virtual thread
   */
  @SuppressWarnings("unboxing.of.nullable")
  public static boolean isVirtualThread() {
    try {
      return IS_VIRTUAL_METHOD != null
          && (boolean) IS_VIRTUAL_METHOD.invoke( Thread.currentThread() );
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException( e );
    }
  }
}
