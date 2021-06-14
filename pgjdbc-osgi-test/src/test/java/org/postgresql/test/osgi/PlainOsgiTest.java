/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.osgi;

import static org.ops4j.pax.exam.CoreOptions.options;
import static org.postgresql.test.osgi.DefaultPgjdbcOsgiOptions.defaultPgjdbcOsgiOptions;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

import java.sql.Driver;

/**
 * The purpose of the class is to ensure {@code postgresql} bundle activation does not fail
 * in case {@code org.osgi.service.jdbc} is not available.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public class PlainOsgiTest {
  @Configuration
  public Option[] config() {
    return options(
        defaultPgjdbcOsgiOptions()
    );
  }

  @Test
  public void driverVersionShouldBePositive() throws Exception {
    Class<?> driverClass = Class.forName("org.postgresql.Driver");
    Driver driver = (Driver) driverClass.getConstructor().newInstance();

    // We use regular assert instead of hamcrest since
    // org.hamcrest.Matchers not found by org.ops4j.pax.tipi.hamcrest.core

    assertPositive("driver.getMajorVersion()", driver.getMajorVersion());
    assertPositive("driver.getMinorVersion()", driver.getMinorVersion());
  }

  private void assertPositive(String message, int value) {
    if (value > 0) {
      return;
    }
    Assert.fail(message + " should be positive, actual value is " + value);
  }
}
