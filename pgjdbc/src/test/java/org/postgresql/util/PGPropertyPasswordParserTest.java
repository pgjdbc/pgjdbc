/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.PGEnvironment;

import org.junit.jupiter.api.Test;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.net.URL;

/**
 * Password resource location used is decided based on availability of different environment
 * variables and file existence in user home directory. Tests verify selection of proper resource.
 * Also, resource content (* matching, escape character handling, comments etc) can be written
 * creatively. Test verify several cases.
 *
 * @author Marek Läll
 */
class PGPropertyPasswordParserTest {

  // "org.postgresql.pgpassfile" : missing
  // "PGPASSFILE"                : missing
  // ".pgpass"                   : missing
  @Test
  void getPassword11() throws Exception {
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), "", "APPDATA", "/tmp/dir-non-existent"),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), "",   "user.home", "/tmp/dir-non-existent")
    ).execute(() -> {
      String result = PGPropertyPasswordParser.getPassword("localhost", "5432", "postgres", "postgres");
      assertNull(result);
    });
  }

  // "org.postgresql.pgpassfile" : missing
  // "PGPASSFILE"                : missing
  // ".pgpass"                   : exist
  // <password line>             : exist
  @Test
  void getPassword22() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), "", "APPDATA", urlPath.getPath() ),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), "",  "user.home", urlPath.getPath())
    ).execute(() -> {
      String result = PGPropertyPasswordParser.getPassword("localhost", "5432", "postgres",
          "postgres");
      assertEquals("postgres1", result);
      result = PGPropertyPasswordParser.getPassword("localhost2", "5432", "postgres", "postgres");
      assertEquals("postgres\\", result);
      result = PGPropertyPasswordParser.getPassword("localhost3", "5432", "postgres", "postgres");
      assertEquals("postgres:", result);
      result = PGPropertyPasswordParser.getPassword("localhost4", "5432", "postgres", "postgres");
      assertEquals("postgres1:", result);
      result = PGPropertyPasswordParser.getPassword("localhost5", "5432", "postgres", "postgres");
      assertEquals("postgres5", result);
      result = PGPropertyPasswordParser.getPassword("localhost6", "5432", "postgres", "postgres");
      assertEquals("post\\gres\\", result);
      result = PGPropertyPasswordParser.getPassword("localhost7", "5432", "postgres", "postgres");
      assertEquals(" ab cd", result);
      result = PGPropertyPasswordParser.getPassword("localhost8", "5432", "postgres", "postgres");
      assertEquals("", result);
      //
      result = PGPropertyPasswordParser.getPassword("::1", "1234", "colon:db", "colon:user");
      assertEquals("pass:pass", result);
      result = PGPropertyPasswordParser.getPassword("::1", "12345", "colon:db", "colon:user");
      assertEquals("pass:pass1", result);
      result = PGPropertyPasswordParser.getPassword("::1", "1234", "slash\\db", "slash\\user");
      assertEquals("pass\\pass", result);
      result = PGPropertyPasswordParser.getPassword("::1", "12345", "slash\\db", "slash\\user");
      assertEquals("pass\\pass1", result);
      //
      result = PGPropertyPasswordParser.getPassword("any", "5432", "postgres", "postgres");
      assertEquals("anyhost5", result);
      result = PGPropertyPasswordParser.getPassword("localhost11", "9999", "postgres", "postgres");
      assertEquals("anyport5", result);
      result = PGPropertyPasswordParser.getPassword("localhost12", "5432", "anydb", "postgres");
      assertEquals("anydb5", result);
      result = PGPropertyPasswordParser.getPassword("localhost13", "5432", "postgres", "anyuser");
      assertEquals("anyuser5", result);
      //
      result = PGPropertyPasswordParser.getPassword("anyhost", "6544", "anydb", "anyuser");
      assertEquals("absolute-any", result);
    });
  }

  // "org.postgresql.pgpassfile" : missing
  // "PGPASSFILE"                : exist
  // ".pgpass"                   : exist
  // <password line>             : missing
  @Test
  void getPassword31() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgpassfileEnv.conf");
    assertNotNull(urlFileEnv);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), urlFileEnv.getFile(), "APPDATA", urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      String result = PGPropertyPasswordParser.getPassword("localhost-missing", "5432", "postgres1", "postgres2");
      assertNull(result);
    });
  }

  // "org.postgresql.pgpassfile" : missing
  // "PGPASSFILE"                : exist
  // ".pgpass"                   : exist
  // <password line>             : exist
  @Test
  void getPassword32() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgpassfileEnv.conf");
    assertNotNull(urlFileEnv);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), urlFileEnv.getPath(), "APPDATA", urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), "", "user.home", urlPath.getPath())
    ).execute(() -> {
      String result = PGPropertyPasswordParser.getPassword("localhost", "5432", "postgres1",
          "postgres2");
      assertEquals("postgres3", result);
    });
  }


  // "org.postgresql.pgpassfile" : exist
  // "PGPASSFILE"                : exist
  // ".pgpass"                   : exist
  // <password line>             : missing
  @Test
  void getPassword41() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgpassfileEnv.conf");
    assertNotNull(urlFileEnv);
    URL urlFileProps = getClass().getResource("/pg_service/pgpassfileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), urlFileEnv.getFile(), "APPDATA", urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(),"", "user.home", urlPath.getPath())
    ).execute(() -> {
      String result = PGPropertyPasswordParser.getPassword("localhost-missing", "5432", "postgres1", "postgres2");
      assertNull(result);
    });
  }

  // "org.postgresql.pgpassfile" : exist
  // "PGPASSFILE"                : exist
  // ".pgpass"                   : exist
  // <password line>             : exist
  @Test
  void getPassword42() throws Exception {
    URL urlPath = getClass().getResource("/pg_service");
    assertNotNull(urlPath);
    URL urlFileEnv = getClass().getResource("/pg_service/pgpassfileEnv.conf");
    assertNotNull(urlFileEnv);
    URL urlFileProps = getClass().getResource("/pg_service/pgpassfileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), urlFileEnv.getPath(),"APPDATA", urlPath.getPath()),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), urlFileProps.getFile(), "user.home", urlPath.getPath())
    ).execute(() -> {
      String result = PGPropertyPasswordParser.getPassword("localhost77", "5432", "any", "postgres11");
      assertEquals("postgres22", result);
      result = PGPropertyPasswordParser.getPassword("localhost888", "5432", "any", "postgres11");
      assertNull(result);
      result = PGPropertyPasswordParser.getPassword("localhost999", "5432", "any", "postgres11");
      assertNull(result);
    });
  }

}
