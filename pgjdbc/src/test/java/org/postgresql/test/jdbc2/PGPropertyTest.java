/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.net.URLEncoder;
import java.sql.DriverPropertyInfo;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;


public class PGPropertyTest {

  /**
   * Some tests modify the "ssl" system property. To not disturb other test cases in the suite store
   * the value of the property and restore it.
   */
  private String bootSSLPropertyValue;

  @Before
  public void setUp() {
    bootSSLPropertyValue = System.getProperty("ssl");
  }

  @After
  public void tearDown() {
    if (bootSSLPropertyValue == null) {
      System.getProperties().remove("ssl");
    } else {
      System.setProperty("ssl", bootSSLPropertyValue);
    }
  }

  /**
   * Test that we can get and set all default values and all choices (if any)
   */
  @Test
  public void testGetSetAllProperties() {
    Properties properties = new Properties();
    for (PGProperty property : PGProperty.values()) {
      String value = property.get(properties);
      assertEquals(property.getDefaultValue(), value);

      property.set(properties, value);
      assertEquals(value, property.get(properties));

      if (property.getChoices() != null && property.getChoices().length > 0) {
        for (String choice : property.getChoices()) {
          property.set(properties, choice);
          assertEquals(choice, property.get(properties));
        }
      }
    }
  }

  /**
   * Test that the enum constant is common with the underlying property name
   */
  @Test
  public void testEnumConstantNaming() {
    for (PGProperty property : PGProperty.values()) {
      String enumName = property.name().replaceAll("_", "");
      assertEquals("Naming of the enum constant [" + property.name()
          + "] should follow the naming of its underlying property [" + property.getName()
          + "] in PGProperty", property.getName().toLowerCase(), enumName.toLowerCase());
    }
  }

  @Test
  public void testDriverGetPropertyInfo() {
    Driver driver = new Driver();
    DriverPropertyInfo[] infos = driver.getPropertyInfo(
        "jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true",
        // this is the example we give in docs
        new Properties());
    for (DriverPropertyInfo info : infos) {
      if ("user".equals(info.name)) {
        assertEquals("fred", info.value);
      } else if ("password".equals(info.name)) {
        assertEquals("secret", info.value);
      } else if ("ssl".equals(info.name)) {
        assertEquals("true", info.value);
      }
    }
  }

  /**
   * Test if the datasource has getter and setter for all properties
   */
  @Test
  public void testDataSourceProperties() throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    BeanInfo info = Introspector.getBeanInfo(dataSource.getClass());

    // index PropertyDescriptors by name
    Map<String, PropertyDescriptor> propertyDescriptors =
        new TreeMap<String, PropertyDescriptor>(String.CASE_INSENSITIVE_ORDER);
    for (PropertyDescriptor propertyDescriptor : info.getPropertyDescriptors()) {
      propertyDescriptors.put(propertyDescriptor.getName(), propertyDescriptor);
    }

    // test for the existence of all read methods (getXXX/isXXX) and write methods (setXXX) for all
    // known properties
    for (PGProperty property : PGProperty.values()) {
      if (!property.getName().startsWith("PG")) {
        assertTrue("Missing getter/setter for property [" + property.getName() + "] in ["
            + BaseDataSource.class + "]", propertyDescriptors.containsKey(property.getName()));

        assertNotNull("Not getter for property [" + property.getName() + "] in ["
            + BaseDataSource.class + "]",
            propertyDescriptors.get(property.getName()).getReadMethod());

        assertNotNull("Not setter for property [" + property.getName() + "] in ["
            + BaseDataSource.class + "]",
            propertyDescriptors.get(property.getName()).getWriteMethod());
      }
    }

    // test readability/writability of default value
    for (PGProperty property : PGProperty.values()) {
      if (!property.getName().startsWith("PG")) {
        Object propertyValue =
            propertyDescriptors.get(property.getName()).getReadMethod().invoke(dataSource);
        propertyDescriptors.get(property.getName()).getWriteMethod().invoke(dataSource,
            propertyValue);
      }
    }
  }

  /**
   * Test that {@link PGProperty#isPresent(Properties)} returns a correct result in all cases
   */
  @Test
  public void testIsPresentWithParseURLResult() throws Exception {
    Properties givenProperties = new Properties();
    givenProperties.setProperty("user", TestUtil.getUser());
    givenProperties.setProperty("password", TestUtil.getPassword());

    Properties sysProperties = System.getProperties();
    sysProperties.remove("ssl");
    System.setProperties(sysProperties);
    Properties parsedProperties = Driver.parseURL(TestUtil.getURL(), givenProperties);
    assertFalse("SSL property should not be present",
        PGProperty.SSL.isPresent(parsedProperties));

    System.setProperty("ssl", "true");
    givenProperties.setProperty("ssl", "true");
    parsedProperties = Driver.parseURL(TestUtil.getURL(), givenProperties);
    assertTrue("SSL property should be present", PGProperty.SSL.isPresent(parsedProperties));

    givenProperties.setProperty("ssl", "anotherValue");
    parsedProperties = Driver.parseURL(TestUtil.getURL(), givenProperties);
    assertTrue("SSL property should be present", PGProperty.SSL.isPresent(parsedProperties));

    parsedProperties = Driver.parseURL(TestUtil.getURL() + "&ssl=true", null);
    assertTrue("SSL property should be present", PGProperty.SSL.isPresent(parsedProperties));
  }

  /**
   * Check whether the isPresent method really works.
   */
  @Test
  public void testPresenceCheck() {
    Properties empty = new Properties();
    Object value = PGProperty.LOG_LEVEL.get(empty);
    assertNotNull(value);
    assertFalse(PGProperty.LOG_LEVEL.isPresent(empty));
  }

  @Test
  public void testNullValue() {
    Properties empty = new Properties();
    assertNull(PGProperty.LOG_LEVEL.getSetString(empty));
    Properties withLogging = new Properties();
    withLogging.setProperty(PGProperty.LOG_LEVEL.getName(), "2");
    assertNotNull(PGProperty.LOG_LEVEL.getSetString(withLogging));
  }

  @Test
  public void testEncodedUrlValues() {
    String databaseName = "d&a%ta+base";
    String userName = "&u%ser";
    String password = "p%a&s^s#w!o@r*";
    String url = "jdbc:postgresql://"
            + "localhost" + ":" + 5432 + "/"
            + URLEncoder.encode(databaseName)
            + "?user=" + URLEncoder.encode(userName)
            + "&password=" + URLEncoder.encode(password);
    Properties parsed = Driver.parseURL(url, new Properties());
    assertEquals("database", databaseName, PGProperty.PG_DBNAME.get(parsed));
    assertEquals("user", userName, PGProperty.USER.get(parsed));
    assertEquals("password", password, PGProperty.PASSWORD.get(parsed));
  }
}
