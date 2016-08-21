package org.postgresql.test.util;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Version;

import org.junit.Assert;
import org.junit.Test;


public class ServerVersionTest {

  @Test
  public void testParseFullFormat() throws Exception {
    Version version = ServerVersion.from("9.6.0");

    Assert.assertEquals(version, ServerVersion.v9_6);
  }

  @Test
  public void test96GreatThan95() throws Exception {
    boolean result = ServerVersion.v9_6.getVersionNum() >= ServerVersion.v9_5.getVersionNum();
    Assert.assertTrue(result);
  }

  @Test
  public void testParse83devel() throws Exception {
    Version version = ServerVersion.from("8.3devel");

    Assert.assertEquals(version, ServerVersion.v8_3);
  }

  @Test
  public void testParse10devel() throws Exception {
    Version version = ServerVersion.from("10devel");

    Assert.assertEquals(version, ServerVersion.v10);
  }

  @Test
  public void test10develGreatThan95() throws Exception {
    Version version = ServerVersion.from("10devel");

    boolean result = version.getVersionNum() >= ServerVersion.v9_5.getVersionNum();
    Assert.assertTrue(result);
  }
}
