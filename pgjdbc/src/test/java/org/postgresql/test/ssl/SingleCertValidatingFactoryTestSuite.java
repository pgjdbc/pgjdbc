/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;

@RunWith(Parameterized.class)
public class SingleCertValidatingFactoryTestSuite {
  private static String IS_ENABLED_PROP_NAME = "testsinglecertfactory";

  /**
   * <p>This method returns the parameters that JUnit will use when constructing this class for
   * testing. It returns a collection of arrays, each containing a single value for the JDBC URL to
   * test against.</p>
   *
   * <p>To point the test at a different set of test databases edit the JDBC URL list accordingly. By
   * default it points to the test databases setup by the pgjdbc-test-vm virtual machine.</p>
   *
   * <p>Note: The test assumes that the username as password for all the test databases are the same
   * (pulled from system properties).</p>
   */
  @Parameters
  public static Collection<Object[]> data() throws IOException {
    Properties props = new Properties();
    File sslTestFile =
        TestUtil.getFile(System.getProperty("ssltest.properties", "ssltest.properties"));
    props.load(new FileInputStream(sslTestFile));
    String testSingleCertFactory = props.getProperty(IS_ENABLED_PROP_NAME);
    boolean skipTest = testSingleCertFactory == null || "".equals(testSingleCertFactory);
    if (skipTest) {
      System.out.println("Skipping SingleCertSocketFactoryTests. To enable set the property "
          + IS_ENABLED_PROP_NAME + "=true in the ssltest.properties file.");
      return Collections.emptyList();
    }

    return Arrays.asList(new Object[][]{
        {"jdbc:postgresql://localhost:5432/test"},
        //        {"jdbc:postgresql://localhost:10090/test"},
        //        {"jdbc:postgresql://localhost:10091/test"},
        //        {"jdbc:postgresql://localhost:10092/test"},
        //        {"jdbc:postgresql://localhost:10093/test"},
    });
  }

  // The valid and invalid server SSL certfiicates:
  private static final String goodServerCertPath = "../certdir/goodroot.crt";
  private static final String badServerCertPath = "../certdir/badroot.crt";

  private String getGoodServerCert() {
    return loadFile(goodServerCertPath);
  }

  private String getBadServerCert() {
    return loadFile(badServerCertPath);
  }

  protected String getUsername() {
    return System.getProperty("username");
  }

  protected String getPassword() {
    return System.getProperty("password");
  }

  private String serverJdbcUrl;

  public SingleCertValidatingFactoryTestSuite(String serverJdbcUrl) {
    this.serverJdbcUrl = serverJdbcUrl;
  }

  protected String getServerJdbcUrl() {
    return serverJdbcUrl;
  }

  /**
   * Helper method to create a connection using the additional properties specified in the "info"
   * paramater.
   *
   * @param info The additional properties to use when creating a connection
   */
  protected Connection getConnection(Properties info) throws SQLException {
    String url = getServerJdbcUrl();
    info.setProperty("user", getUsername());
    info.setProperty("password", getPassword());
    return DriverManager.getConnection(url, info);
  }

  /**
   * Tests whether a given throwable or one of it's root causes matches of a given class.
   */
  private boolean matchesExpected(Throwable t, Class<? extends Throwable> expectedThrowable)
      throws SQLException {
    if (t == null || expectedThrowable == null) {
      return false;
    }
    if (expectedThrowable.isAssignableFrom(t.getClass())) {
      return true;
    }
    return matchesExpected(t.getCause(), expectedThrowable);
  }

  protected void testConnect(Properties info, boolean sslExpected) throws SQLException {
    testConnect(info, sslExpected, null);
  }

  /**
   * Connects to the database with the given connection properties and then verifies that connection
   * is using SSL.
   */
  protected void testConnect(Properties info, boolean sslExpected,
      Class<? extends Throwable> expectedThrowable) throws SQLException {
    Connection conn = null;
    try {
      conn = getConnection(info);
      Statement stmt = conn.createStatement();
      // Basic SELECT test:
      ResultSet rs = stmt.executeQuery("SELECT 1");
      rs.next();
      Assert.assertEquals(1, rs.getInt(1));
      rs.close();
      // Verify SSL usage is as expected:
      rs = stmt.executeQuery("SELECT ssl_is_used()");
      rs.next();
      boolean sslActual = rs.getBoolean(1);
      Assert.assertEquals(sslExpected, sslActual);
      stmt.close();
    } catch (Exception e) {
      if (matchesExpected(e, expectedThrowable)) {
        // do nothing and just suppress the exception
        return;
      } else {
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        } else if (e instanceof SQLException) {
          throw (SQLException) e;
        } else {
          throw new RuntimeException(e);
        }
      }
    } finally {
      if (conn != null) {
        try {
          conn.close();
        } catch (Exception e) {
        }
      }
    }

    if (expectedThrowable != null) {
      Assert.fail("Expected exception " + expectedThrowable.getName() + " but it did not occur.");
    }
  }

  /**
   * Connect using SSL and attempt to validate the server's certificate but don't actually provide
   * it. This connection attempt should *fail* as the client should reject the server.
   */
  @Test
  public void connectSSLWithValidationNoCert() throws SQLException {
    Properties info = new Properties();
    info.setProperty("ssl", "true");
    testConnect(info, true, javax.net.ssl.SSLHandshakeException.class);
  }

  /**
   * <p>Connect using SSL and attempt to validate the server's certificate against the wrong pre shared
   * certificate. This test uses a pre generated certificate that will *not* match the test
   * PostgreSQL server (the certificate is for properssl.example.com).</p>
   *
   * <p>This connection uses a custom SSLSocketFactory using a custom trust manager that validates the
   * remote server's certificate against the pre shared certificate.</p>
   *
   * <p>This test should throw an exception as the client should reject the server since the
   * certificate does not match.</p>
   */
  @Test
  public void connectSSLWithValidationWrongCert() throws SQLException, IOException {
    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", "file:" + badServerCertPath);
    testConnect(info, true, javax.net.ssl.SSLHandshakeException.class);
  }

  @Test
  public void fileCertInvalid() throws SQLException, IOException {
    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", "file:foo/bar/baz");
    testConnect(info, true, java.io.FileNotFoundException.class);
  }

  @Test
  public void stringCertInvalid() throws SQLException, IOException {
    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", "foobar!");
    testConnect(info, true, java.security.GeneralSecurityException.class);
  }

  /**
   * Connect using SSL and attempt to validate the server's certificate against the proper pre
   * shared certificate. The certificate is specified as a String. Note that the test read's the
   * certificate from a local file.
   */
  @Test
  public void connectSSLWithValidationProperCertFile() throws SQLException, IOException {
    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", "file:" + goodServerCertPath);
    testConnect(info, true);
  }

  /**
   * Connect using SSL and attempt to validate the server's certificate against the proper pre
   * shared certificate. The certificate is specified as a String (eg. the "----- BEGIN CERTIFICATE
   * ----- ... etc").
   */
  @Test
  public void connectSSLWithValidationProperCertString() throws SQLException, IOException {
    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", getGoodServerCert());
    testConnect(info, true);
  }

  /**
   * Connect using SSL and attempt to validate the server's certificate against the proper pre
   * shared certificate. The certificate is specified as a system property.
   */
  @Test
  public void connectSSLWithValidationProperCertSysProp() throws SQLException, IOException {
    // System property name we're using for the SSL cert. This can be anything.
    String sysPropName = "org.postgresql.jdbc.test.sslcert";

    try {
      System.setProperty(sysPropName, getGoodServerCert());

      Properties info = new Properties();
      info.setProperty("ssl", "true");
      info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
      info.setProperty("sslfactoryarg", "sys:" + sysPropName);
      testConnect(info, true);
    } finally {
      // Clear it out when we're done:
      System.setProperty(sysPropName, "");
    }
  }

  /**
   * <p>Connect using SSL and attempt to validate the server's certificate against the proper pre
   * shared certificate. The certificate is specified as an environment variable.</p>
   *
   * <p>Note: To execute this test successfully you need to set the value of the environment variable
   * DATASOURCE_SSL_CERT prior to running the test.</p>
   *
   * <p>Here's one way to do it: $ DATASOURCE_SSL_CERT=$(cat certdir/goodroot.crt) ant clean test</p>
   */
  @Test
  public void connectSSLWithValidationProperCertEnvVar() throws SQLException, IOException {
    String envVarName = "DATASOURCE_SSL_CERT";
    if (System.getenv(envVarName) == null) {
      System.out.println(
          "Skipping test connectSSLWithValidationProperCertEnvVar (env variable is not defined)");
      return;
    }

    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", "env:" + envVarName);
    testConnect(info, true);
  }

  /**
   * Connect using SSL using a system property to specify the SSL certificate but not actually
   * having it set. This tests whether the proper exception is thrown.
   */
  @Test
  public void connectSSLWithValidationMissingSysProp() throws SQLException, IOException {
    // System property name we're using for the SSL cert. This can be anything.
    String sysPropName = "org.postgresql.jdbc.test.sslcert";

    try {
      System.setProperty(sysPropName, "");

      Properties info = new Properties();
      info.setProperty("ssl", "true");
      info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
      info.setProperty("sslfactoryarg", "sys:" + sysPropName);
      testConnect(info, true, java.security.GeneralSecurityException.class);
    } finally {
      // Clear it out when we're done:
      System.setProperty(sysPropName, "");
    }
  }

  /**
   * Connect using SSL using an environment var to specify the SSL certificate but not actually
   * having it set. This tests whether the proper exception is thrown.
   */
  @Test
  public void connectSSLWithValidationMissingEnvVar() throws SQLException, IOException {
    // Use an environment variable that does *not* exist:
    String envVarName = "MISSING_DATASOURCE_SSL_CERT";
    if (System.getenv(envVarName) != null) {
      System.out
          .println("Skipping test connectSSLWithValidationMissingEnvVar (env variable is defined)");
      return;
    }

    Properties info = new Properties();
    info.setProperty("ssl", "true");
    info.setProperty("sslfactory", "org.postgresql.ssl.SingleCertValidatingFactory");
    info.setProperty("sslfactoryarg", "env:" + envVarName);
    testConnect(info, true, java.security.GeneralSecurityException.class);
  }

  ///////////////////////////////////////////////////////////////////

  /**
   * Utility function to load a file as a string.
   */
  public static String loadFile(String path) {
    BufferedReader br = null;
    try {
      br = new BufferedReader(new InputStreamReader(new FileInputStream(path)));
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
        sb.append("\n");
      }
      return sb.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (br != null) {
        try {
          br.close();
        } catch (Exception e) {
        }
      }
    }
  }
}
