/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGmoney;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class PGObjectGetTest extends BaseTest4 {
  private final String sqlExpression;
  private final Class<? extends PGobject> type;
  private final String expected;
  private final String stringValue;

  public PGObjectGetTest(BinaryMode binaryMode, String sqlExpression,
      Class<? extends PGobject> type, String expected, String stringValue) {
    setBinaryMode(binaryMode);
    this.sqlExpression = sqlExpression;
    this.type = type;
    this.expected = expected;
    this.stringValue = stringValue;
  }

  @Parameterized.Parameters(name = "binary = {0}, sql = {1}, type = {2}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode, "null::inet", PGobject.class,
          "PGobject(type=inet, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::box", PGbox.class,
          "PGbox(type=box, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::circle", PGcircle.class,
          "PGcircle(type=circle, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::line", PGline.class,
          "PGline(type=line, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::lseg", PGlseg.class,
          "PGlseg(type=lseg, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::path", PGpath.class,
          "PGpath(type=path, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::point", PGpoint.class,
          "PGpoint(type=point, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::polygon", PGpolygon.class,
          "PGpolygon(type=polygon, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::money", PGmoney.class,
          "PGmoney(type=money, value=null)", null});
      ids.add(new Object[]{binaryMode, "null::interval", PGInterval.class,
          "PGInterval(type=interval, value=null)", null});
    }
    return ids;
  }

  @Test
  public void getAsPGobject() throws SQLException {
    testGet(sqlExpression, expected, PGobject.class);
  }

  @Test
  public void getAsPGobjectSubtype() throws SQLException {
    testGet(sqlExpression, expected, type);
  }

  @Test
  public void getAsString() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select " + sqlExpression);
    ResultSet rs = ps.executeQuery();
    rs.next();
    assertEquals(
        "'" + sqlExpression + "'.getString(1)",
        stringValue,
        rs.getString(1)
    );
  }

  private void testGet(final String s, String expected, Class<? extends PGobject> type) throws SQLException {
    PreparedStatement ps = con.prepareStatement("select " + s);
    ResultSet rs = ps.executeQuery();
    rs.next();
    assertEquals(
        "'" + s + "'.getObject(1, " + type.getSimpleName() + ".class)",
        expected,
        printObject(rs.getObject(1, type))
    );
    if (expected.contains("value=null)")) {
      // For some reason we return objects as nulls
      assertNull(
          "'select " + s + "'.getObject(1)",
          rs.getObject(1)
      );
    } else {
      assertEquals(
          "'select " + s + "'.getObject(1)",
          expected,
          printObject(rs.getObject(1))
      );
    }
  }

  String printObject(@Nullable Object object) {
    if (!(object instanceof PGobject)) {
      return String.valueOf(object);
    }
    PGobject pg = (PGobject) object;
    return pg.getClass().getSimpleName() + "(type=" + pg.getType() + ", value=" + pg.getValue() + ")";
  }
}
