/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@code hasInsecurePosixPermissions} follows libpq. A key file owned by root may be read
 * by its group, and a key file owned by anyone else may be read only by its owner. Neither
 * may be written or executed by its group, and neither may be read, written or executed by
 * other users.
 */
class BaseX509KeyManagerTest {

  @ParameterizedTest(name = "{0} is insecure: {1}")
  @CsvSource({
      "rw-------, false",
      "r--------, false",
      "rwx------, false",
      "rw-r-----, true",
      "rw--w----, true",
      "rw---x---, true",
      "rw----r--, true",
      "rw-----w-, true",
      "rw------x, true",
  })
  void aKeyFileOwnedByTheUserIsReadableOnlyByItsOwner(String permissions, boolean insecure) {
    assertEquals(insecure, hasInsecurePosixPermissions(permissions, false));
  }

  @ParameterizedTest(name = "{0} is insecure: {1}")
  @CsvSource({
      "rw-r-----, false",
      "r--r-----, false",
      "rw-------, false",
      "r--------, false",
      "rw-rw----, true",
      "rw-r-x---, true",
      "rw-r--r--, true",
      "rw-r---w-, true",
      "rw-r----x, true",
  })
  void aKeyFileOwnedByRootIsAlsoReadableByItsGroup(String permissions, boolean insecure) {
    assertEquals(insecure, hasInsecurePosixPermissions(permissions, true));
  }

  private static boolean hasInsecurePosixPermissions(String permissions, boolean ownedByRoot) {
    return BaseX509KeyManager.hasInsecurePosixPermissions(
        PosixFilePermissions.fromString(permissions), ownedByRoot);
  }
}
