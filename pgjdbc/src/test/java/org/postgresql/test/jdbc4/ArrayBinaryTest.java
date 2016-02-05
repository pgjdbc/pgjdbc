
package org.postgresql.test.jdbc4;

import org.postgresql.jdbc.PgConnection;

import java.sql.*;

import java.util.Properties;

public class ArrayBinaryTest extends ArrayTest {
  public ArrayBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    forceBinary(props);
  }

  public void testToString() throws SQLException {

    Double []d  = new Double[4];

    d[0] = 3.5;
    d[1] = -4.5;
    d[2] = 10.0 / 3;
    d[3] = 77.0;

    Array arr = con.createArrayOf("float8", d);
    PreparedStatement pstmt = con.prepareStatement("INSERT INTO arrtest(floatarr) VALUES (?)");
    ResultSet rs = null;

    try {

      pstmt.setArray(1, arr);
      pstmt.execute();

    } finally {

      pstmt.close();

    }


    try
    {
      Statement stmt = con.createStatement();
      ((PgConnection)con).setForceBinary(true);

      rs = stmt.executeQuery("select floatarr from arrtest");

      while (rs.next()) {

        Array doubles = rs.getArray(1);
        assertEquals("If the strings start with { then the driver did not use binary",
            "[3.5, -4.5, 3.3333333333333335, 77.0]",doubles.toString());
      }

    } finally {

      if (rs != null)
        rs.close();
    }

  }
}
