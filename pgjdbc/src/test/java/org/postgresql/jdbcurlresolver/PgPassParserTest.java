/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

/**
 * Resource content (* matching, escape character handling, comments etc) can be written
 * creatively. Test verify several cases.
 *
 * @author Marek Läll
 */
public class PgPassParserTest {

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
    URL urlPath = getClass().getResource("/pgpass/.pgpass");
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

}
