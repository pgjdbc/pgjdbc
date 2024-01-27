/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RunWith(Parameterized.class)
public class PreparedStatement64KBindsTest extends BaseTest4 {
  private final int numBinds;
  private final PreferQueryMode preferQueryMode;
  private final BinaryMode binaryMode;

  public PreparedStatement64KBindsTest(int numBinds, PreferQueryMode preferQueryMode,
      BinaryMode binaryMode) {
    this.numBinds = numBinds;
    this.preferQueryMode = preferQueryMode;
    this.binaryMode = binaryMode;
  }

  @Parameterized.Parameters(name = "numBinds={0}, preferQueryMode={1}, binaryMode={2}}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (PreferQueryMode preferQueryMode : PreferQueryMode.values()) {
      for (BinaryMode binaryMode : BinaryMode.values()) {
        for (int numBinds : new int[]{32766, 32767, 32768, 65534, 65535, 65536}) {
          ids.add(new Object[]{numBinds, preferQueryMode, binaryMode});
        }
      }
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PREFER_QUERY_MODE.set(props, preferQueryMode.value());
    setBinaryMode(binaryMode);
  }

  @Test
  public void executeWith65535BindsWorks() throws SQLException {
    String sql = Collections.nCopies(numBinds, "?").stream()
        .collect(Collectors.joining(",", "select ARRAY[", "]"));

    try (PreparedStatement ps = con.prepareStatement(sql)) {
      for (int i = 1; i <= numBinds; i++) {
        ps.setString(i, "v" + i);
      }
      String expected = Arrays.toString(
          IntStream.rangeClosed(1, numBinds)
              .mapToObj(i -> "v" + i).toArray()
      );

      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Array res = rs.getArray(1);
        Object[] elements = (Object[]) res.getArray();
        String actual = Arrays.toString(elements);

        if (preferQueryMode == PreferQueryMode.SIMPLE || numBinds <= 65535) {
          Assert.assertEquals("SELECT query with " + numBinds + " should work", actual, expected);
        } else {
          Assert.fail("con.prepareStatement(..." + numBinds + " binds) should fail since the wire protocol allows only 65535 parameters");
        }
      }
    } catch (SQLException e) {
      if (preferQueryMode != PreferQueryMode.SIMPLE && numBinds > 65535) {
        Assert.assertEquals(
            "con.prepareStatement(..." + numBinds + " binds) should fail since the wire protocol allows only 65535 parameters. SQL State is ",
            PSQLState.INVALID_PARAMETER_VALUE.getState(),
            e.getSQLState()
        );
      } else {
        throw e;
      }
    }
  }
}
