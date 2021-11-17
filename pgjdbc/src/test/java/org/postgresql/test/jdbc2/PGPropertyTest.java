/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.test.TestUtil;
import org.postgresql.util.URLCoder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.sql.DriverPropertyInfo;
import java.util.ArrayList;
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
   * Test that we can get and set all default values and all choices (if any).
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

  @Test
  public void testSortOrder() {
    String prevName = null;
    for (PGProperty property : PGProperty.values()) {
      String name = property.name();
      if (prevName != null) {
        assertTrue("PGProperty names should be sorted in ascending order: " + name + " < " + prevName, name.compareTo(prevName) > 0);
      }
      prevName = name;
    }
  }

  /**
   * Test that the enum constant is common with the underlying property name.
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
   * Test if the datasource has getter and setter for all properties.
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
      if (!property.getName().startsWith("PG") && property != PGProperty.SERVICE) {
        assertTrue("Missing getter/setter for property [" + property.getName() + "] in ["
            + BaseDataSource.class + "]", propertyDescriptors.containsKey(property.getName()));

        assertNotNull("No getter for property [" + property.getName() + "] in ["
            + BaseDataSource.class + "]",
            propertyDescriptors.get(property.getName()).getReadMethod());

        assertNotNull("No setter for property [" + property.getName() + "] in ["
            + BaseDataSource.class + "]",
            propertyDescriptors.get(property.getName()).getWriteMethod());
      }
    }

    // test readability/writability of default value
    for (PGProperty property : PGProperty.values()) {
      if (!property.getName().startsWith("PG") && property != PGProperty.SERVICE) {
        Object propertyValue =
            propertyDescriptors.get(property.getName()).getReadMethod().invoke(dataSource);
        propertyDescriptors.get(property.getName()).getWriteMethod().invoke(dataSource,
            propertyValue);
      }
    }
  }

  /**
   * Test to make sure that setURL doesn't overwrite autosave
   * more should be put in but this scratches the current itch
   */
  @Test
  public void testOverWriteDSProperties() throws Exception {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setAutosave(AutoSave.CONSERVATIVE);
    dataSource.setURL("jdbc:postgresql://localhost:5432/postgres");
    assertSame(dataSource.getAutosave(),AutoSave.CONSERVATIVE);
  }

  /**
   * Test that {@link PGProperty#isPresent(Properties)} returns a correct result in all cases.
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
    Object value = PGProperty.READ_ONLY.get(empty);
    assertNotNull(value);
    assertFalse(PGProperty.READ_ONLY.isPresent(empty));
  }

  @Test
  public void testNullValue() {
    Properties empty = new Properties();
    assertNull(PGProperty.LOGGER_LEVEL.getSetString(empty));
    Properties withLogging = new Properties();
    withLogging.setProperty(PGProperty.LOGGER_LEVEL.getName(), "OFF");
    assertNotNull(PGProperty.LOGGER_LEVEL.getSetString(withLogging));
  }

  @Test
  public void testEncodedUrlValues() {
    String databaseName = "d&a%ta+base";
    String userName = "&u%ser";
    String password = "p%a&s^s#w!o@r*";
    String url = "jdbc:postgresql://"
        + "localhost" + ":" + 5432 + "/"
        + URLCoder.encode(databaseName)
        + "?user=" + URLCoder.encode(userName)
        + "&password=" + URLCoder.encode(password);
    Properties parsed = Driver.parseURL(url, new Properties());
    assertEquals("database", databaseName, PGProperty.PG_DBNAME.get(parsed));
    assertEquals("user", userName, PGProperty.USER.get(parsed));
    assertEquals("password", password, PGProperty.PASSWORD.get(parsed));
  }

  @Test
  public void testLowerCamelCase() {
    // These are legacy properties excluded for backward compatibility.
    ArrayList<String> excluded = new ArrayList<String>();
    excluded.add("LOG_LEVEL"); // Remove with PR #722
    excluded.add("PREPARED_STATEMENT_CACHE_SIZE_MIB"); // preparedStatementCacheSizeMi[B]
    excluded.add("DATABASE_METADATA_CACHE_FIELDS_MIB"); // databaseMetadataCacheFieldsMi[B]
    excluded.add("STRING_TYPE"); // string[t]ype
    excluded.add("SSL_MODE"); // ssl[m]ode
    excluded.add("SSL_FACTORY"); // ssl[f]actory
    excluded.add("SSL_FACTORY_ARG"); // ssl[f]actory[a]rg
    excluded.add("SSL_HOSTNAME_VERIFIER"); // ssl[h]ostname[v]erifier
    excluded.add("SSL_CERT"); // ssl[c]ert
    excluded.add("SSL_KEY"); // ssl[k]ey
    excluded.add("SSL_ROOT_CERT"); // ssl[r]oot[c]ert
    excluded.add("SSL_PASSWORD"); // ssl[p]assword
    excluded.add("SSL_PASSWORD_CALLBACK"); // ssl[p]assword[c]allback
    excluded.add("APPLICATION_NAME"); // [A]pplicationName
    excluded.add("GSS_LIB"); // gss[l]ib
    excluded.add("REWRITE_BATCHED_INSERTS"); // re[W]riteBatchedInserts

    for (PGProperty property : PGProperty.values()) {
      if (!property.name().startsWith("PG")) { // Ignore all properties that start with PG
        String[] words = property.name().split("_");
        if (words.length == 1) {
          assertEquals(words[0].toLowerCase(), property.getName());
        } else {
          if (!excluded.contains(property.name())) {
            String word = "";
            for (int i = 0; i < words.length; i++) {
              if (i == 0) {
                word = words[i].toLowerCase();
              } else {
                word += words[i].substring(0, 1).toUpperCase() + words[i].substring(1).toLowerCase();
              }
            }
            assertEquals(word, property.getName());
          }
        }
      }
    }
  }

  @Test
  public void testEncodedUrlValuesFromDataSource() {
    String databaseName = "d&a%ta+base";
    String userName = "&u%ser";
    String password = "p%a&s^s#w!o@r*";
    String applicationName = "Laurel&Hardy=Best?Yes";
    PGSimpleDataSource dataSource = new PGSimpleDataSource();

    dataSource.setDatabaseName(databaseName);
    dataSource.setUser(userName);
    dataSource.setPassword(password);
    dataSource.setApplicationName(applicationName);

    Properties parsed = Driver.parseURL(dataSource.getURL(), new Properties());
    assertEquals("database", databaseName, PGProperty.PG_DBNAME.get(parsed));
    // datasources do not pass username and password as URL parameters
    assertFalse("user", PGProperty.USER.isPresent(parsed));
    assertFalse("password", PGProperty.PASSWORD.isPresent(parsed));
    assertEquals("APPLICATION_NAME", applicationName, PGProperty.APPLICATION_NAME.get(parsed));
  }
}
