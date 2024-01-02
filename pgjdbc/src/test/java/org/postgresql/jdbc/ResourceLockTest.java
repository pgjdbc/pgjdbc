/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResourceLockTest {
  @Test
  void obtainClose() {
    final ResourceLock lock = new ResourceLock();

    assertFalse(lock.isLocked(),
        "lock.isLocked(). The newly created resource lock should be unlocked");
    assertFalse(lock.isHeldByCurrentThread(),
        "lock.isHeldByCurrentThread(). The newly created resource lock should not be held by the current thread");

    try (ResourceLock ignore = lock.obtain()) {
      assertTrue(lock.isLocked(),
          "lock.isLocked(). Obtained lock should be locked");
      assertTrue(lock.isHeldByCurrentThread(),
          "lock.isHeldByCurrentThread(). Obtained lock should be held by the current thread");
    }

    assertFalse(lock.isLocked(), "lock.isLocked(). Closed resource lock should be unlocked");
    assertFalse(lock.isHeldByCurrentThread(),
        "lock.isHeldByCurrentThread(). Closed resource lock should not be held by the current thread");
  }
}
