/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Before;
import org.junit.Test;

import java.io.Writer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLXML;
import java.sql.Statement;

public class PgSQLXMLTest extends BaseTest4 {

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "xmltab","x xml");
  }

  @Test
  public void setCharacterStream() throws  Exception {
    String exmplar = "<x>value</x>";
    SQLXML pgSQLXML = con.createSQLXML();
    Writer writer = pgSQLXML.setCharacterStream();
    writer.write(exmplar);
    PreparedStatement preparedStatement = con.prepareStatement("insert into xmltab values (?)");
    preparedStatement.setSQLXML(1,pgSQLXML);
    preparedStatement.execute();

    Statement statement = con.createStatement();
    ResultSet rs = statement.executeQuery("select * from xmltab");
    assertTrue(rs.next());
    SQLXML result = rs.getSQLXML(1);
    assertNotNull(result);
    assertEquals(exmplar, result.getString());
  }
}
