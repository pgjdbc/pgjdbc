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

import java.lang.reflect.InvocationTargetException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class PGObjectSetTest extends BaseTest4 {
  private final String typeName;
  private final String expected;
  private final Class<? extends PGobject> type;

  public PGObjectSetTest(BinaryMode binaryMode, Class<? extends PGobject> type,
      String typeName, String expected) {
    setBinaryMode(binaryMode);
    this.expected = expected;
    this.type = type;
    this.typeName = typeName;
  }

  @Parameterized.Parameters(name = "binary = {0}, sql = {2}, type = {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode, PGobject.class, "inet",
          "PGobject(type=inet, value=null)"});
      ids.add(new Object[]{binaryMode, PGbox.class, "box",
          "PGbox(type=box, value=null)"});
      ids.add(new Object[]{binaryMode, PGcircle.class, "circle",
          "PGcircle(type=circle, value=null)"});
      ids.add(new Object[]{binaryMode, PGline.class, "line",
          "PGline(type=line, value=null)"});
      ids.add(new Object[]{binaryMode, PGlseg.class, "lseg",
          "PGlseg(type=lseg, value=null)"});
      ids.add(new Object[]{binaryMode, PGpath.class, "path",
          "PGpath(type=path, value=null)"});
      ids.add(new Object[]{binaryMode, PGpoint.class, "point",
          "PGpoint(type=point, value=null)"});
      ids.add(new Object[]{binaryMode, PGpolygon.class, "polygon",
          "PGpolygon(type=polygon, value=null)"});
      ids.add(new Object[]{binaryMode, PGmoney.class, "money",
          "PGmoney(type=money, value=null)"});
      ids.add(new Object[]{binaryMode, PGInterval.class, "interval",
          "PGInterval(type=interval, value=null)"});
    }
    return ids;
  }

  @Test
  public void setNullAsPGobject() throws SQLException {
    PGobject object = new PGobject();
    object.setType(typeName);
    object.setValue(null);
    testSet(object, expected, PGobject.class);
  }

  @Test
  public void setNullAsPGobjectSubtype() throws SQLException, NoSuchMethodException,
      IllegalAccessException, InvocationTargetException, InstantiationException {
    if (type == PGobject.class) {
      // We can't use PGobject without setType
      return;
    }
    PGobject object = type.getConstructor().newInstance();
    object.setValue(null);
    testSet(object, expected, type);
  }

  private void testSet(PGobject value, String expected, Class<? extends PGobject> type) throws SQLException {
    PreparedStatement ps = con.prepareStatement("select ?::" + value.getType());
    ps.setObject(1, value);
    ResultSet rs = ps.executeQuery();
    rs.next();
    assertEquals(
        "'select ?::" + value.getType() + "'.withParam(" + printObject(value) + ").getObject(1, " + type.getSimpleName() + ".class)",
        expected,
        printObject(rs.getObject(1, type))
    );
    if (expected.contains("value=null)")) {
      assertNull(
          "'select ?::" + value.getType() + "'.withParam(" + printObject(value) + ").getObject(1)",
          rs.getObject(1)
      );
    } else {
      assertEquals(
          "'select ?::" + value.getType() + "'.withParam(" + printObject(value) + ").getObject(1)",
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
