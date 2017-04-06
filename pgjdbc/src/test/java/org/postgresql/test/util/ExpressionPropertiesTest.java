/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.util.ExpressionProperties;
import org.postgresql.util.PGProperties;

import org.junit.Assert;
import org.junit.Test;

public class ExpressionPropertiesTest {
  @Test
  public void simpleReplace() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("server", "app1");
    p.put("file", "pgjdbc_${server}.txt");
    Assert.assertEquals("${server} should be replaced", "pgjdbc_app1.txt", p.getProperty("file"));
  }

  @Test
  public void replacementMissing() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("file", "pgjdbc_${server}.txt");
    Assert.assertEquals("${server} should be kept as is as there is no replacement",
        "pgjdbc_${server}.txt", p.getProperty("file"));
  }

  @Test
  public void multipleReplacements() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("server", "app1");
    p.put("file", "${server}${server}${server}${server}${server}");
    Assert.assertEquals("All the ${server} entries should be replaced",
        "app1app1app1app1app1", p.getProperty("file"));
  }

  @Test
  public void multipleParentProperties() {
    PGProperties p1 = new PGProperties();
    p1.set("server", "app1_${app.type}");
    PGProperties p2 = new PGProperties();
    p2.set("app.type", "production");

    ExpressionProperties p = new ExpressionProperties(p1, p2);
    p.put("file", "pgjdbc_${server}.txt");

    Assert.assertEquals("All the ${...} entries should be replaced",
        "pgjdbc_app1_production.txt", p.getProperty("file"));
  }

  @Test
  public void rawValue() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("server", "app1");
    p.put("file", "${server}${server}${server}${server}${server}");
    Assert.assertEquals("No replacements in raw value expected",
        "${server}${server}${server}${server}${server}", p.getRawPropertyValue("file"));
  }
}
