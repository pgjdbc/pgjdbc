package org.postgresql.test.module;

import org.junit.jupiter.api.Test;

import org.postgresql.Driver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

public class PlainModuleTest {
  @Test
  public void moduleShouldNotBeAutomatic() {
    var module = Driver.class.getModule().getDescriptor();
    assertFalse(module.isAutomatic());
  }

  @Test
  public void driverVersionShouldBePositive() throws Exception {
    Class<?> driverClass = Class.forName("org.postgresql.Driver");
    java.sql.Driver driver = (java.sql.Driver) driverClass.getConstructor().newInstance();

    // We use regular assert instead of hamcrest since
    // org.hamcrest.Matchers not found by org.ops4j.pax.tipi.hamcrest.core

    assertPositive("driver.getMajorVersion()", driver.getMajorVersion());
    assertPositive("driver.getMinorVersion()", driver.getMinorVersion());
  }

  private void assertPositive(String message, int value) {
    if (value > 0) {
      return;
    }
    fail(message + " should be positive, actual value is " + value);
  }
}
