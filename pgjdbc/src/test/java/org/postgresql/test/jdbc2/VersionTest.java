package org.postgresql.test.jdbc2;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.Utils;

import junit.framework.TestCase;

public class VersionTest extends TestCase {

  @Override
  protected void setUp() throws Exception {
    super.setUp();
  }

  public void testVersionParsing() {
    assertNotNull(Utils.class);
    System.out.println(Utils.class.getProtectionDomain().getCodeSource().getLocation());
    /* Boring versions */
    assertEquals(70400, Utils.parseServerVersionStr("7.4.0"));
    assertEquals(ServerVersion.v7_4.getVersionNum(), Utils.parseServerVersionStr("7.4.0"));
    assertEquals(90001, Utils.parseServerVersionStr("9.0.1"));
    assertEquals(90000, Utils.parseServerVersionStr("9.0.0"));
    assertEquals(ServerVersion.v9_0.getVersionNum(), Utils.parseServerVersionStr("9.0.0"));
    assertEquals(90201, Utils.parseServerVersionStr("9.2.1"));

    /* Major-only versions */
    assertEquals(70400, Utils.parseServerVersionStr("7.4"));
    assertEquals(90000, Utils.parseServerVersionStr("9.0"));
    assertEquals(90000, Utils.parseServerVersionStr("9.0"));
    assertEquals(ServerVersion.v9_0.getVersionNum(), Utils.parseServerVersionStr("9.0"));
    assertEquals(90200, Utils.parseServerVersionStr("9.2"));
    assertEquals(ServerVersion.v9_2.getVersionNum(), Utils.parseServerVersionStr("9.2"));

    /* Multi-digit versions */
    assertEquals(90410, Utils.parseServerVersionStr("9.4.10"));
    assertEquals(92010, Utils.parseServerVersionStr("9.20.10"));
    assertEquals(100000, Utils.parseServerVersionStr("10.0.0"));
    assertEquals(100103, Utils.parseServerVersionStr("10.1.3"));
    assertEquals(102010, Utils.parseServerVersionStr("10.20.10"));

    /* Out-of-range versions */
    try {
      Utils.parseServerVersionStr("9.20.100");
      fail("Should've rejected three-digit minor version");
    } catch (NumberFormatException ex) {
    }

    try {
      Utils.parseServerVersionStr("10.100.10");
      fail("Should've rejected three-digit second part of major version");
    } catch (NumberFormatException ex) {
    }

    /* Big first part is OK */
    assertEquals(1232010, Utils.parseServerVersionStr("123.20.10"));

    /* But not too big */
    try {
      Utils.parseServerVersionStr("12345.1.1");
      fail("Should've rejected five-digit second part of major version");
    } catch (NumberFormatException ex) {
    }

    /* Large numeric inputs are taken as already parsed */
    assertEquals(90104, Utils.parseServerVersionStr("90104"));
    assertEquals(90104, Utils.parseServerVersionStr("090104"));
    assertEquals(70400, Utils.parseServerVersionStr("070400"));
    assertEquals(100104, Utils.parseServerVersionStr("100104"));
    assertEquals(10000, Utils.parseServerVersionStr("10000"));

    /* --with-extra-version or beta/devel tags */
    assertEquals(90400, Utils.parseServerVersionStr("9.4devel"));
    assertEquals(90400, Utils.parseServerVersionStr("9.4beta1"));
    assertEquals(100000, Utils.parseServerVersionStr("10.0devel"));
    assertEquals(100000, Utils.parseServerVersionStr("10.0beta1"));
    assertEquals(90401, Utils.parseServerVersionStr("9.4.1bobs"));
    assertEquals(90401, Utils.parseServerVersionStr("9.4.1bobspatched9.4"));
    assertEquals(90401, Utils.parseServerVersionStr("9.4.1-bobs-patched-postgres-v2.2"));
  }
}
