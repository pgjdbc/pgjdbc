/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

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

@ParameterizedClass
@MethodSource("data")
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

  /**
   * The v3 protocol counts bind parameters in an unsigned int16, so up to 65535 binds are
   * described and round-trip, and {@code prepareStatement} refuses more with
   * {@link PSQLState#INVALID_PARAMETER_VALUE}. {@link PreferQueryMode#SIMPLE} inlines the values
   * into the SQL text instead of binding them, so it accepts any count.
   */
  @Test
  public void upTo65535BindsAreDescribedAndRoundTripAndMoreAreRefused() throws SQLException {
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

      if (preferQueryMode != PreferQueryMode.SIMPLE) {
        // Only Describe Statement, which getParameterMetaData() sends, makes the server send
        // ParameterDescription, so no other call here reads that 6 + 4 * numBinds byte message.
        assertEquals(numBinds, ps.getParameterMetaData().getParameterCount(),
            "ps.getParameterMetaData().getParameterCount()");
      }

      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        Array res = rs.getArray(1);
        Object[] elements = (Object[]) res.getArray();
        String actual = Arrays.toString(elements);

        if (preferQueryMode == PreferQueryMode.SIMPLE || numBinds <= 65535) {
          assertEquals(expected, actual, () -> "SELECT query with " + numBinds + " binds");
        } else {
          fail("con.prepareStatement(..." + numBinds + " binds) should fail since the wire protocol allows only 65535 parameters");
        }
      }
    } catch (SQLException e) {
      if (preferQueryMode != PreferQueryMode.SIMPLE && numBinds > 65535) {
        assertEquals(
            PSQLState.INVALID_PARAMETER_VALUE.getState(),
            e.getSQLState(),
            () -> "con.prepareStatement(..." + numBinds + " binds) should fail since the wire protocol allows only 65535 parameters"
        );
      } else {
        throw e;
      }
    }
  }
}
