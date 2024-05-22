/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

class Utils {

  // On posix filesystems it is expected that PGPASS_FILE_NAME file does not have rights for "group"and/or "others"
  // i.e. file rights must be "chmod 600 ..." or "-rw-------"
  // the following code will change file rights to "600" to assure that tests does not fail
  static void setPgpassFilePermissions(String fileName) throws IOException {
    URL urlPath = PgPassParserTest.class.getResource(fileName);
    assertNotNull(urlPath);
    Path path = new File(urlPath.getPath()).toPath();
    FileSystem fileSystem = path.getFileSystem();
    if (fileSystem.supportedFileAttributeViews().contains("posix")) {
      Set<PosixFilePermission> newPermissions = new HashSet<>();
      newPermissions.add(PosixFilePermission.OWNER_READ);
      newPermissions.add(PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(path, newPermissions);
    }
  }

}
