/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.util.OSUtil;
import org.postgresql.util.StubEnvironmentAndProperties;

import org.junit.jupiter.api.Test;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.net.URL;
import java.util.Properties;

/**
 * Service resource location used is decided based on availability of different environment
 * variables and file existence in user home directory. Tests verify selection of proper resource.
 * Also, resource content (section headers, comments, key-value pairs etc) can be written
 * creatively. Test verifies several cases.
 *
 * @author Marek Läll
 */
@StubEnvironmentAndProperties
public class PgServiceConfParserTest {

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : missing
  @Test
  public void pgService11() throws Exception {
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), ""),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("service-nonexistent"));
      assertEquals("Resource file [.pg_service.conf] not found", exception.getMessage());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  public void pgService21() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("service-nonexistent"));
      assertEquals("Definition of service [service-nonexistent] not found", exception.getMessage());
      Properties result = PgServiceConfParser.getServiceProperties("empty-service1");
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
  public void pgService22() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNotNull(result);
      assertEquals("test_dbname", PGProperty.DBNAME.getOrNull(result));
      assertEquals("global-test-host.test.net", PGProperty.HOST.getOrNull(result));
      assertEquals("5433", PGProperty.PORT.getOrNull(result));
      assertEquals("admin", PGProperty.USER.getOrNull(result));
      assertEquals(4, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : missing
  // "PGSYSCONFDIR"                 : exist - but file itself is missing
  // <service>                      : exist
  @Test
  public void pgService23() throws Exception {
    String nonExistingDir = "non-existing-dir";
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), nonExistingDir),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", "/tmp/dir-nonexistent")
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("test-service1"));
      if (OSUtil.isWindows()) {
        assertEquals("Failed to handle resource [non-existing-dir\\pg_service.conf] with error [non-existing-dir\\pg_service.conf (The system cannot find the path specified)]", exception.getMessage());
      } else {
        assertEquals("Failed to handle resource [non-existing-dir/pg_service.conf] with error [non-existing-dir/pg_service.conf (No such file or directory)]", exception.getMessage());
      }
    });
  }


  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : missing
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  public void pgService31() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("service-nonexistent"));
      assertEquals("Definition of service [service-nonexistent] not found", exception.getMessage());
      Properties result = PgServiceConfParser.getServiceProperties("empty-service1");
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
  public void pgService32() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", "APPDATA", urlPath.getPath(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      Properties result = PgServiceConfParser.getServiceProperties("test-service1");
      assertNotNull(result);
      assertEquals(" test_dbname", PGProperty.DBNAME.getOrNull(result));
      assertEquals("local-test-host.test.net", PGProperty.HOST.getOrNull(result));
      assertEquals("5433", PGProperty.PORT.getOrNull(result));
      assertEquals("admin", PGProperty.USER.getOrNull(result));
      assertEquals(4, result.size());
    });
  }


  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  public void pgService41() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("service-nonexistent"));
      assertEquals("Definition of service [service-nonexistent] not found", exception.getMessage());
      Properties result = PgServiceConfParser.getServiceProperties("empty-service1");
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
  public void pgService42() throws Exception {
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
      assertEquals("test_dbname", PGProperty.DBNAME.getOrNull(result));
      assertEquals("pgservicefileEnv-test-host.test.net", PGProperty.HOST.getOrNull(result));
      assertEquals("5433", PGProperty.PORT.getOrNull(result));
      assertEquals("admin", PGProperty.USER.getOrNull(result));
      assertEquals("disable", PGProperty.SSL_MODE.getOrNull(result));
      assertEquals(5, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : missing
  // "PGSERVICEFILE"                : exist - but file itself is missing
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  public void pgService43() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    String nonExistingFile = "non-existing-file.conf";
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), nonExistingFile, PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("test-service1"));
      if (OSUtil.isWindows()) {
        assertEquals("Failed to handle resource [non-existing-file.conf] with error [non-existing-file.conf (The system cannot find the file specified)]", exception.getMessage());
      } else {
        assertEquals("Failed to handle resource [non-existing-file.conf] with error [non-existing-file.conf (No such file or directory)]", exception.getMessage());
      }
    });
  }


  // "org.postgresql.pgservicefile" : exist
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : missing
  @Test
  public void pgService51() throws Exception {
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
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("service-nonexistent"));
      assertEquals("Definition of service [service-nonexistent] not found", exception.getMessage());
      Properties result = PgServiceConfParser.getServiceProperties("empty-service1");
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
  public void pgService52() throws Exception {
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
      assertEquals("test_dbname", PGProperty.DBNAME.getOrNull(result));
      assertEquals("pgservicefileProps-test-host.test.net", PGProperty.HOST.getOrNull(result));
      assertEquals("5433", PGProperty.PORT.getOrNull(result));
      assertEquals("admin", PGProperty.USER.getOrNull(result));
      assertEquals(4, result.size());
    });
  }

  // "org.postgresql.pgservicefile" : exist - but file itself is missing
  // "PGSERVICEFILE"                : exist
  // ".pg_service.conf"             : exist
  // "PGSYSCONFDIR"                 : exist
  // <service>                      : exist
  @Test
  public void pgService53() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgservicefileEnv.conf");
    assertNotNull(urlFileEnv);
    String nonExistingFile = "non-existing-file.conf";
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), urlFileEnv.getFile(), PGEnvironment.PGSYSCONFDIR.getName(), urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), nonExistingFile, "user.home", urlPath.getPath())
    ).execute(() -> {
      JdbcUrlResolverFatalException exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("test-service1"));
      if (OSUtil.isWindows()) {
        assertEquals("Failed to handle resource [non-existing-file.conf] with error [non-existing-file.conf (The system cannot find the file specified)]", exception.getMessage());
      } else {
        assertEquals("Failed to handle resource [non-existing-file.conf] with error [non-existing-file.conf (No such file or directory)]", exception.getMessage());
      }
    });
  }


  // resource content read tests
  @Test
  public void pgService61() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICEFILE.getName(), "", "APPDATA", urlPath.getPath(), PGEnvironment.PGSYSCONFDIR.getName(), ""),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      JdbcUrlResolverFatalException exception;
      // fail if there is space between key and equal sign
      exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("fail-case-1"));
      assertEquals("Got invalid key: line number [19], value [host =local-somehost1]", exception.getMessage());
      // service name is case-sensitive
      exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("fail-case-2"));
      assertEquals("Definition of service [fail-case-2] not found", exception.getMessage());
      // invalid line in the section
      exception = assertThrows(JdbcUrlResolverFatalException.class, () -> PgServiceConfParser.getServiceProperties("fail-case-3"));
      assertEquals("Not valid line: line number [27], value [host]", exception.getMessage());
      // service name: space before and after name becomes part of name
      Properties result = PgServiceConfParser.getServiceProperties(" success-case-3 ");
      assertNotNull(result);
      assertEquals("local-somehost3", PGProperty.HOST.getOrNull(result));
      assertEquals(1, result.size());
      // service name: space inside name is part of name
      result = PgServiceConfParser.getServiceProperties("success case 4");
      assertNotNull(result);
      assertEquals("local-somehost4", PGProperty.HOST.getOrNull(result));
      assertEquals(1, result.size());
    });
  }

}
