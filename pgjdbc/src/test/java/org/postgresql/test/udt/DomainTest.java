/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Before;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Map;

public class DomainTest extends BaseTest4 {

  protected static final String EMAIL_SQL_TYPE = "public.email";
  protected static final String PORT_SQL_TYPE = "\"Port\"";
  protected static final String SALE_DATE_SQL_TYPE = "sale_date";

  protected static final String EMAIL1 = "foo@bar.baz";
  protected static final String EMAIL2 = "test2@example.com";
  protected static final String EMAIL3 = "test@example.com";

  protected static final int PORT1 = 1024;
  protected static final int PORT2 = 1337;
  protected static final int PORT3 = 16384;

  protected static final Timestamp TS1        = new Timestamp(2016 - 1900,  0,  9,  1,  2,  3, 0);
  protected static final Timestamp TS2        = new Timestamp(2018 - 1900, 11, 25,  5, 18, 25, 565869000);
  protected static final Timestamp TS3        = new Timestamp(2020 - 1900,  4, 23, 11, 22,  0, 0);
  protected static final Timestamp TS_INVALID = new Timestamp(1976 - 1900,  3,  3,  1, 22,  0, 0);

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();

    Map<String, Class<?>> typeMap = con.getTypeMap();

    TestUtil.createDomain(con, EMAIL_SQL_TYPE, "text", "value like '%_@_%'"); // Not a true email validation - just for testing
    TestUtil.createTable(con, "testemail", "email " + EMAIL_SQL_TYPE);
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testemail VALUES (?)");
    try {
      // Can add as the base type
      pstmt.setString(1, EMAIL3);
      pstmt.executeUpdate();
      // Can also insert as Email object
      pstmt.setObject(1, new Email(EMAIL2));
      pstmt.executeUpdate();
      pstmt.setObject(1, new Email(EMAIL1), Types.OTHER);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
    typeMap.put(EMAIL_SQL_TYPE, Email.class);

    TestUtil.createDomain(con, PORT_SQL_TYPE, "integer", "value >= 1 and value <= 65535");
    TestUtil.createTable(con, "testport", "port " + PORT_SQL_TYPE + " primary key");
    pstmt = con.prepareStatement("INSERT INTO testport VALUES (?)");
    try {
      // Can add as the base type
      pstmt.setInt(1, PORT1);
      pstmt.executeUpdate();
      // Can also insert as Port object
      pstmt.setObject(1, new PortImpl(PORT3));
      pstmt.executeUpdate();
      pstmt.setObject(1, new PortImpl(PORT2), Types.OTHER);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
    typeMap.put(PORT_SQL_TYPE, PortImpl.class);

    TestUtil.createDomain(con, SALE_DATE_SQL_TYPE, "timestamp with time zone", "value >= '2016-01-01'");
    TestUtil.createTable(con, "sales", "date " + SALE_DATE_SQL_TYPE);
    pstmt = con.prepareStatement("INSERT INTO sales VALUES (?)");
    try {
      // Can add as the base type
      pstmt.setTimestamp(1, TS1);
      pstmt.executeUpdate();
      // Can also insert as SaleDate object
      pstmt.setObject(1, new SaleDate(TS3));
      pstmt.executeUpdate();
      pstmt.setObject(1, new SaleDate(TS2), Types.OTHER);
      pstmt.executeUpdate();
    } finally {
      pstmt.close();
    }
    typeMap.put(SALE_DATE_SQL_TYPE, SaleDate.class);

    con.setTypeMap(typeMap);

  }

  @After
  @Override
  public void tearDown() throws SQLException {
    try {
      TestUtil.closeDB(con);

      con = TestUtil.openDB();

      TestUtil.dropTable(con, "sales");
      TestUtil.dropDomain(con, SALE_DATE_SQL_TYPE);

      TestUtil.dropTable(con, "testport");
      TestUtil.dropDomain(con, PORT_SQL_TYPE);

      TestUtil.dropTable(con, "testemail");
      TestUtil.dropDomain(con, EMAIL_SQL_TYPE);
    } finally {
      super.tearDown();
    }
  }
}
