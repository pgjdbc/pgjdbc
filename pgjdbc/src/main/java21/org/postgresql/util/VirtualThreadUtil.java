/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * Virtual thread utilities.
 */
public class VirtualThreadUtil {

  /**
   * Returns whether the current thread is a virtual thread
   *
   * @return whether the current thread is a virtual thread
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
}
