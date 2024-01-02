/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.util.ExpressionProperties;

import org.junit.jupiter.api.Test;

import java.util.Properties;

class ExpressionPropertiesTest {
  @Test
  void simpleReplace() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("server", "app1");
    p.put("file", "pgjdbc_${server}.txt");
    assertEquals("pgjdbc_app1.txt", p.getProperty("file"), "${server} should be replaced");
  }

  @Test
  void replacementMissing() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("file", "pgjdbc_${server}.txt");
    assertEquals("pgjdbc_${server}.txt", p.getProperty("file"), "${server} should be kept as is as there is no replacement");
  }

  @Test
  void multipleReplacements() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("server", "app1");
    p.put("file", "${server}${server}${server}${server}${server}");
    assertEquals("app1app1app1app1app1", p.getProperty("file"), "All the ${server} entries should be replaced");
  }

  @Test
  void multipleParentProperties() {
    Properties p1 = new Properties();
    p1.setProperty("server", "app1_${app.type}");
    Properties p2 = new Properties();
    p2.setProperty("app.type", "production");

    ExpressionProperties p = new ExpressionProperties(p1, p2);
    p.put("file", "pgjdbc_${server}.txt");

    assertEquals("pgjdbc_app1_production.txt", p.getProperty("file"), "All the ${...} entries should be replaced");
  }

  @Test
  void rawValue() {
    ExpressionProperties p = new ExpressionProperties();
    p.put("server", "app1");
    p.put("file", "${server}${server}${server}${server}${server}");
    assertEquals("${server}${server}${server}${server}${server}", p.getRawPropertyValue("file"), "No replacements in raw value expected");
  }
}
