/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGEnvironment;
import org.postgresql.test.annotations.DisableLogger;
import org.postgresql.util.StubEnvironmentAndProperties;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.Test;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Service resource location used is decided based on availability of different environment
 * variables and file existence in user home directory. Tests verify selection of proper resource.
 * Also, resource content (section headers, comments, key-value pairs etc) can be written
 * creatively. Test verify several cases.
 *
 * @author Marek Läll
 */
@StubEnvironmentAndProperties
class PgServiceConfParserTest {

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : missing
  @Test
  void pgService11() throws Exception {
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), ""),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("service-nonexistent");
      assertNull(result);
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  void pgService21() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("service-nonexistent");
      assertNull(result);
      result = PgServiceConfParser.getServiceProperties("empty-service1");
      assertNotNull(result);
      assertTrue(result.isEmpty());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  void pgService22() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNotNull(result);
      assertEquals("test_dbname", result.get("PGDBNAME"));
      assertEquals("global-test-host.test.net", result.get("PGHOST"));
      assertEquals("5433", result.get("PGPORT"));
      assertEquals("admin", result.get("user"));
      assertEquals(4, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : exist - but file itself is missing
  // <service>                      : exist
  @Test
  void pgService23() throws Exception {
    String nonExistingDir = "non-existing-dir";
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), nonExistingDir),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNull(result);
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  void pgService31() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("service-nonexistent");
      assertNull(result);
      result = PgServiceConfParser.getServiceProperties("empty-service1");
      assertNotNull(result);
      assertTrue(result.isEmpty());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  void pgService32() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", "APPDATA", urlPath.getPath(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNotNull(result);
      assertEquals(" test_dbname", result.get("PGDBNAME"));
      assertEquals("local-test-host.test.net", result.get("PGHOST"));
      assertEquals("5433", result.get("PGPORT"));
      assertEquals("admin", result.get("user"));
      assertEquals(4, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  void pgService41() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("service-nonexistent");
      assertNull(result);
      result = PgServiceConfParser.getServiceProperties("empty-service1");
      assertNotNull(result);
      assertTrue(result.isEmpty());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  void pgService42() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNotNull(result);
      assertEquals("test_dbname", result.get("PGDBNAME"));
      assertEquals("pgservicefileEnv-test-host.test.net", result.get("PGHOST"));
      assertEquals("5433", result.get("PGPORT"));
      assertEquals("admin", result.get("user"));
      assertEquals("disable", result.get("sslmode"));
      assertEquals(5, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : exist - but file itself is missing
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  @DisableLogger(PgServiceConfParser.class)
  void pgService43() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    String nonExistingFile = "non-existing-file.conf";
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), nonExistingFile, PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNull(result);
    });
  }

  // "org.postgresql.pgservicefile" : exist
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  void pgService51() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile(), "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("service-nonexistent");
      assertNull(result);
      result = PgServiceConfParser.getServiceProperties("empty-service1");
      assertNotNull(result);
      assertTrue(result.isEmpty());
    });
  }

  // "org.postgresql.pgservicefile" : exist
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  void pgService52() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile(), "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNotNull(result);
      assertEquals("test_dbname", result.get("PGDBNAME"));
      assertEquals("pgservicefileProps-test-host.test.net", result.get("PGHOST"));
      assertEquals("5433", result.get("PGPORT"));
      assertEquals("admin", result.get("user"));
      assertEquals(4, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : exist - but file itself is missing
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  @DisableLogger(PgServiceConfParser.class)
  void pgService53() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    String nonExistingFile = "non-existing-file.conf";
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), nonExistingFile, "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNull(result);
    });
  }

  // resource content read tests
  @Test
  @DisableLogger(PgServiceConfParser.class)
  void pgService61() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", "APPDATA", urlPath.getPath(), PGEnvironment.PGSYSCONFDIR.getName(), ""),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result;
      // fail if there is space between key and equal sign
      result = PgServiceConfParser.getServiceProperties("fail-case-1");
      assertNull(result);
      // service name is case-sensitive
      result = PgServiceConfParser.getServiceProperties("fail-case-2");
      assertNull(result);
      // service name is case-sensitive
      result = PgServiceConfParser.getServiceProperties("fail-case-2");
      assertNull(result);
      // invalid line in the section
      result = PgServiceConfParser.getServiceProperties("fail-case-3");
      assertNull(result);
      // service name: space before and after name becomes part of name
      result = PgServiceConfParser.getServiceProperties(" success-case-3 ");
      assertNotNull(result);
      assertEquals("local-somehost3", result.get("PGHOST"));
      assertEquals(1, result.size());
      // service name: space inside name is part of name
      result = PgServiceConfParser.getServiceProperties("success case 4");
      assertNotNull(result);
      assertEquals("local-somehost4", result.get("PGHOST"));
      assertEquals(1, result.size());
    });
  }

  @Test
  void invalidLinesAreLoggedWithoutTheirValue() throws Exception {
    Path file = Files.createTempFile("pg_service", ".conf");
    Logger logger = Logger.getLogger(PgServiceConfParser.class.getName());
    TestLogHandler handler = new TestLogHandler();
    boolean useParentHandlers = logger.getUseParentHandlers();
    logger.addHandler(handler);
    logger.setUseParentHandlers(false);
    try {
      Files.write(file, ("[spaces]\npassword = secret-with-spaces\n"
          + "[colon]\npassword: secret-with-colon\n").getBytes(StandardCharsets.UTF_8));
      Resources.with(
          new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), file.toString())
      ).execute(() -> {
        assertNull(PgServiceConfParser.getServiceProperties("spaces"));
        assertNull(PgServiceConfParser.getServiceProperties("colon"));
      });
      assertEquals(2, handler.records.size());
      for (LogRecord record : handler.records) {
        String message = MessageFormat.format(record.getMessage(), record.getParameters());
        assertFalse(message.contains("secret"), message);
      }
    } finally {
      logger.removeHandler(handler);
      logger.setUseParentHandlers(useParentHandlers);
      Files.delete(file);
    }
  }
}
