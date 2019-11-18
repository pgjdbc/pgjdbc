/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class QuotationTest extends BaseTest4 {
  private enum QuoteStyle {
    SIMPLE("'"), DOLLAR_NOTAG("$$"), DOLLAR_A("$a$"), DOLLAR_DEF("$DEF$"),
    SMILING_FACE("$o‿o$")
    ;

    private final String quote;

    QuoteStyle(String quote) {
      this.quote = quote;
    }

    @Override
    public String toString() {
      return quote;
    }
  }

  private final String expr;
  private final String expected;

  public QuotationTest(QuoteStyle quoteStyle, String expected, String expr) {
    this.expected = expected;
    this.expr = expr;
  }

  @Parameterized.Parameters(name = "{index}: quotes(style={0}, src={1}, quoted={2})")
  public static Iterable<Object[]> data() {
    Collection<String> prefix = new ArrayList<String>();
    // Too many prefixes make test run long
    prefix.add("");
    prefix.add("/*\n$\n*//* ? *//*{fn *//* now} */");
    prefix.add("-- $\n");
    prefix.add("--\n/* $ */");

    Collection<Object[]> ids = new ArrayList<Object[]>();
    Collection<String> garbageValues = new ArrayList<String>();
    garbageValues.add("{fn now}");
    garbageValues.add("{extract}");
    garbageValues.add("{select}");
    garbageValues.add("?select");
    garbageValues.add("select?");
    garbageValues.add("??select");
    garbageValues.add("}{");
    garbageValues.add("{");
    garbageValues.add("}");
    garbageValues.add("--");
    garbageValues.add("/*");
    garbageValues.add("*/");
    for (QuoteStyle quoteStyle : QuoteStyle.values()) {
      garbageValues.add(quoteStyle.toString());
    }
    for (char ch = 'a'; ch <= 'z'; ch++) {
      garbageValues.add(Character.toString(ch));
    }

    for (QuoteStyle quoteStyle : QuoteStyle.values()) {
      for (String garbage : garbageValues) {
        String unquoted = garbage;
        for (int i = 0; i < 3; i++) {
          String quoted = unquoted;
          if (quoteStyle == QuoteStyle.SIMPLE) {
            quoted = quoted.replaceAll("'", "''");
          }
          quoted = quoteStyle.toString() + quoted + quoteStyle.toString();
          if (quoted.endsWith("$$$") && quoteStyle == QuoteStyle.DOLLAR_NOTAG) {
            // $$$a$$$ is parsed like $$ $a $$ $ -> thus we skip this test
            continue;
          }
          if (quoteStyle != QuoteStyle.SIMPLE && garbage.equals(quoteStyle.toString())) {
            // $a$$a$$a$ is not valid
            continue;
          }
          String expected = unquoted;
          for (String p : prefix) {
            ids.add(new Object[]{quoteStyle, expected, p + quoted});
          }
          if (unquoted.length() == 1) {
            char ch = unquoted.charAt(0);
            if (ch >= 'a' && ch <= 'z') {
              // Will assume if 'a' works, then 'aa', 'aaa' will also work
              break;
            }
          }
          unquoted += garbage;
        }
      }
    }

    return ids;
  }

  @Test
  public void quotedString() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select " + expr);
    try {
      ResultSet rs = ps.executeQuery();
      rs.next();
      String val = rs.getString(1);
      Assert.assertEquals(expected, val);
    } catch (SQLException e) {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void bindInTheMiddle() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select " + expr + ", ?, " + expr);
    try {
      ps.setInt(1, 42);
      ResultSet rs = ps.executeQuery();
      rs.next();
      String val1 = rs.getString(1);
      String val3 = rs.getString(3);
      Assert.assertEquals(expected, val1);
      Assert.assertEquals(expected, val3);
    } catch (SQLException e) {
      TestUtil.closeQuietly(ps);
    }
  }

}
