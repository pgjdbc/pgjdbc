/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;

class ArraysTest {

  @Test
  void nonArrayNotSupported() throws Exception {
    assertThrows(PSQLException.class, () -> {
      ArrayEncoding.getArrayEncoder("asdflkj");
    });
  }

  @Test
  void noByteArray() throws Exception {
    assertThrows(PSQLException.class, () -> {
      ArrayEncoding.getArrayEncoder(new byte[]{});
    });
  }

  @Test
  void binaryNotSupported() throws Exception {
    assertThrows(SQLFeatureNotSupportedException.class, () -> {
      final ArrayEncoding.ArrayEncoder<BigDecimal[]> support = ArrayEncoding.getArrayEncoder(new BigDecimal[]{});

      assertFalse(support.supportBinaryRepresentation(Oid.FLOAT8_ARRAY));

      support.toBinaryRepresentation(null, new BigDecimal[]{BigDecimal.valueOf(3)}, Oid.FLOAT8_ARRAY);
    });
  }

  @Test
  public void testArrayEquals() throws SQLException {
    //because of install the postgresql at the VM, need to specify the url user and password for testing.
    String url = "jdbc:postgresql://192.168.100.80/test";
    String user = "postgres";
    String password = "123456";
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    Connection connection = DriverManager.getConnection(url,props);
    Statement statement =  connection.createStatement();

    statement.executeQuery("SELECT * FROM person");
    Array pgArray1 = new PgArray((BaseConnection) connection, Oid.BYTEA_ARRAY, new byte[]{'1','2','3'});
    Array pgArray2 = new PgArray((BaseConnection) connection, Oid.BYTEA_ARRAY, new byte[]{'1','2','3'});
    Assertions.assertEquals(pgArray1,pgArray2);

    Array pgArray3 = new PgArray((BaseConnection) connection, Oid.BYTEA_ARRAY, new byte[]{1,2,3});
    Array pgArray4 = new PgArray((BaseConnection) connection, Oid.BYTEA_ARRAY, new byte[]{1,2,3});
    Assertions.assertEquals(pgArray3,pgArray4);

    Array pgArray5 = new PgArray((BaseConnection) connection, Oid.BIT_ARRAY, new byte[]{1,2,3});
    Array pgArray6 = new PgArray((BaseConnection) connection, Oid.BYTEA_ARRAY, new byte[]{1,2,3});
    Assertions.assertNotEquals(pgArray5,pgArray6);

    Array pgArray7 = new PgArray((BaseConnection) connection, Oid.JSON, new byte[]{1,2,3});
    Array pgArray8 = new PgArray((BaseConnection) connection, Oid.BYTEA_ARRAY, new byte[]{1,2,3});
    Assertions.assertNotEquals(pgArray7,pgArray8);

    Array pgArray9 = new PgArray((BaseConnection) connection, Oid.VARCHAR, "{}");
    Array pgArray10 = new PgArray((BaseConnection) connection, Oid.VARCHAR, "{}");
    Assertions.assertEquals(pgArray9,pgArray10);

    Array pgArray11 = new PgArray((BaseConnection) connection, Oid.VARCHAR, "{\t \n 'testing1', \t \n 'testing2'}");
    Array pgArray12 = new PgArray((BaseConnection) connection, Oid.VARCHAR, "{\t \n 'testing1', \t \n 'testing2'}");
    Assertions.assertEquals(pgArray11,pgArray12);

    Array pgArray13 = new PgArray((BaseConnection) connection, Oid.VARCHAR_ARRAY, "{\t \n 'testing1', \t \n 'testing2'}");
    Array pgArray14 = new PgArray((BaseConnection) connection, Oid.VARCHAR, "{\t \n 'testing1', \t \n 'testing2'}");
    Assertions.assertNotEquals(pgArray13,pgArray14);
  }
}
