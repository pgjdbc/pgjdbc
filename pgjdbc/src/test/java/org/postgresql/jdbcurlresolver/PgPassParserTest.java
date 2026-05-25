/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.util.StubEnvironmentAndProperties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource content (* matching, escape character handling, comments etc) can be written
 * creatively. Test verify several cases.
 *
 * @author Marek Läll
 */
@StubEnvironmentAndProperties
public class PgPassParserTest {

  private static final String PGPASS_FILE_NAME = "/pgpass/.pgpass";

  @BeforeAll
  public static void setUp() throws Exception {
    // fix pgpass file permissions
    Utils.setPgpassFilePermissions(PGPASS_FILE_NAME);
  }

  @Test
  public void getPassword11() {
    URL urlPath = getClass().getResource("/pgpass");
    assertNotNull(urlPath);
    String nonExistingFile = urlPath.getPath() + File.separator + "non_existing_file";
    String result = PgPassParser.getPassword(nonExistingFile, "localhost", "5432", "postgres", "postgres");
    assertNull(result);
  }

  @Test
  public void getPassword22() {
    URL urlPath = getClass().getResource(PGPASS_FILE_NAME);
    assertNotNull(urlPath);
    String existingFile = urlPath.getPath();
    String result = PgPassParser.getPassword(existingFile, "localhost", "5432", "postgres", "postgres");
    assertEquals("postgres1", result);
    result = PgPassParser.getPassword(existingFile, "localhost2", "5432", "postgres", "postgres");
    assertEquals("postgres\\", result);
    result = PgPassParser.getPassword(existingFile, "localhost3", "5432", "postgres", "postgres");
    assertEquals("postgres:", result);
    result = PgPassParser.getPassword(existingFile, "localhost4", "5432", "postgres", "postgres");
    assertEquals("postgres1:", result);
    result = PgPassParser.getPassword(existingFile, "localhost5", "5432", "postgres", "postgres");
    assertEquals("postgres5", result);
    result = PgPassParser.getPassword(existingFile, "localhost6", "5432", "postgres", "postgres");
    assertEquals("post\\gres\\", result);
    result = PgPassParser.getPassword(existingFile, "localhost7", "5432", "postgres", "postgres");
    assertEquals(" ab cd", result);
    result = PgPassParser.getPassword(existingFile, "localhost8", "5432", "postgres", "postgres");
    assertEquals("", result);
    //
    result = PgPassParser.getPassword(existingFile, "::1", "1234", "colon:db", "colon:user");
    assertEquals("pass:pass", result);
    result = PgPassParser.getPassword(existingFile, "::1", "12345", "colon:db", "colon:user");
    assertEquals("pass:pass1", result);
    result = PgPassParser.getPassword(existingFile, "::1", "1234", "slash\\db", "slash\\user");
    assertEquals("pass\\pass", result);
    result = PgPassParser.getPassword(existingFile, "::1", "12345", "slash\\db", "slash\\user");
    assertEquals("pass\\pass1", result);
    //
    result = PgPassParser.getPassword(existingFile, "any", "5432", "postgres", "postgres");
    assertEquals("anyhost5", result);
    result = PgPassParser.getPassword(existingFile, "localhost11", "9999", "postgres", "postgres");
    assertEquals("anyport5", result);
    result = PgPassParser.getPassword(existingFile, "localhost12", "5432", "anydb", "postgres");
    assertEquals("anydb5", result);
    result = PgPassParser.getPassword(existingFile, "localhost13", "5432", "postgres", "anyuser");
    assertEquals("anyuser5", result);
    //
    result = PgPassParser.getPassword(existingFile, "anyhost", "6544", "anydb", "anyuser");
    assertEquals("absolute-any", result);
  }

  @Test
  public void getPassword30() throws IOException {
    //
    List<PosixFilePermission> permissionList = Arrays.asList(
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE
    );
    //
    File file = null;
    try {
      file = File.createTempFile("pgpass_with_different_rights", ".tmp");
      Path path = file.toPath();
      FileSystem fileSystem = path.getFileSystem();
      if (fileSystem.supportedFileAttributeViews().contains("posix")) {
        // write one line to temp file
        Files.write(path, "*:*:*:*:pass".getBytes(StandardCharsets.UTF_8));
        // test
        for (PosixFilePermission permission : permissionList) {
          // set permissions
          Set<PosixFilePermission> newPermissions = new HashSet<>();
          newPermissions.add(PosixFilePermission.OWNER_READ);
          newPermissions.add(permission);
          Files.setPosixFilePermissions(path, newPermissions);
          // verify result
          String result = PgPassParser.getPassword(file.getPath(), "x", "x", "x", "x");
          if (permission.name().startsWith("OWNER_")) {
            assertEquals("pass", result);
          } else {
            assertNull(result);
          }
        }
      }
    } finally {
      if (file != null) {
        file.delete();
      }
    }
  }

}
