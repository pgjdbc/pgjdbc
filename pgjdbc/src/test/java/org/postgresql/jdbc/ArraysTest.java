/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.*;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

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
  public void testArray() throws SQLException {
    String url="";
    String user="";
    String password="";
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    Connection connection = DriverManager.getConnection(url,props);
    // new array
//     String[] arrayElements = {"Dawn Zhong1", "Dawn Zhong2"};
//     Byte[] arrayElements = {'1','2'};
//
//     // insert data into the database
//     PgPreparedStatement statement = (PgPreparedStatement) connection.prepareStatement("INSERT INTO person (test) VALUES (?)");
//     statement.setArray(1, connection.createArrayOf("varchar", arrayElements));
//     statement.executeUpdate();

    // test equals
    Statement statement =  connection.createStatement();
    // SELECT columns FROM table. query data from database, then get the array data.
    String sql = "";
    PgResultSet pgResultSet = (PgResultSet) statement.executeQuery(sql);
    PgArray pgArray1 = (PgArray) pgResultSet.getArray(1);
    pgResultSet.next();
    PgArray pgArray2 = (PgArray) pgResultSet.getArray(1);
    System.out.println(pgArray1.equals(pgArray2));
  }
}
