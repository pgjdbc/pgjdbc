/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.SslMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.SocketException;
import java.security.cert.CertPathValidatorException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import javax.net.ssl.SSLHandshakeException;

@RunWith(Parameterized.class)
public class SslTest extends BaseTest4 {
  enum Hostname {
    GOOD("localhost"),
    BAD("127.0.0.1"),
    ;

    final String value;

    Hostname(String value) {
      this.value = value;
    }
  }

  enum TestDatabase {
    hostdb,
    hostnossldb,
    hostssldb,
    hostsslcertdb,
    certdb,
    ;

    public static final TestDatabase[] VALUES = values();

    public boolean requiresClientCert() {
      return this == certdb || this == hostsslcertdb;
    }

    public boolean requiresSsl() {
      return this == certdb || this == hostssldb || this == hostsslcertdb;
    }

    public boolean rejectsSsl() {
      return this == hostnossldb;
    }
  }

  enum ClientCertificate {
    EMPTY(""),
    GOOD("goodclient"),
    BAD("badclient"),
    ;

    public static final ClientCertificate[] VALUES = values();
    public final String fileName;

    ClientCertificate(String fileName) {
      this.fileName = fileName;
    }
  }

  enum ClientRootCertificate {
    EMPTY(""),
    GOOD("goodroot"),
    BAD("badroot"),
    ;

    public static final ClientRootCertificate[] VALUES = values();
    public final String fileName;

    ClientRootCertificate(String fileName) {
      this.fileName = fileName;
    }
  }

  @Parameterized.Parameter(0)
  public Hostname host;

  @Parameterized.Parameter(1)
  public TestDatabase db;

  @Parameterized.Parameter(2)
  public SslMode sslmode;

  @Parameterized.Parameter(3)
  public ClientCertificate clientCertificate;

  @Parameterized.Parameter(4)
  public ClientRootCertificate clientRootCertificate;

  @Parameterized.Parameter(5)
  public String certdir;

  @Parameterized.Parameters(name = "host={0}, db={1} sslMode={2}, cCert={3}, cRootCert={4}")
  public static Iterable<Object[]> data() {
    Properties prop = TestUtil.loadPropertyFiles("ssltest.properties");
    String enableSslTests = prop.getProperty("enable_ssl_tests");
    if (!Boolean.valueOf(enableSslTests)) {
      System.out.println("enableSslTests is " + enableSslTests + ", skipping SSL tests");
      return Collections.emptyList();
    }

    Collection<Object[]> tests = new ArrayList<Object[]>();


    File certDirFile = TestUtil.getFile(prop.getProperty("certdir"));
    String certdir = certDirFile.getAbsolutePath();

    for (SslMode sslMode : SslMode.VALUES) {
      for (Hostname hostname : Hostname.values()) {
        for (TestDatabase database : TestDatabase.VALUES) {
          for (ClientCertificate clientCertificate : ClientCertificate.VALUES) {
            for (ClientRootCertificate rootCertificate : ClientRootCertificate.VALUES) {
              if ((sslMode == SslMode.DISABLE
                  || database.rejectsSsl())
                  && (clientCertificate != ClientCertificate.GOOD
                  || rootCertificate != ClientRootCertificate.GOOD)) {
                // When SSL is disabled, it does not make sense to verify "bad certificates"
                // since certificates are NOT used in plaintext connections
                continue;
              }
              if (database.rejectsSsl()
                  && (sslMode.verifyCertificate()
                      || hostname == Hostname.BAD)
              ) {
                // DB would reject SSL connection, so it makes no sense to test cases like verify-full
                continue;
              }
              tests.add(
                  new Object[]{hostname, database, sslMode, clientCertificate, rootCertificate,
                      certdir});
            }
          }
        }
      }
    }

    return tests;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    props.put(TestUtil.SERVER_HOST_PORT_PROP, host.value + ":" + TestUtil.getPort());
    props.put(TestUtil.DATABASE_PROP, db.toString());
    PGProperty.SSL_MODE.set(props, sslmode.value);
    if (clientCertificate == ClientCertificate.EMPTY) {
      PGProperty.SSL_CERT.set(props, "");
      PGProperty.SSL_KEY.set(props, "");
    } else {
      PGProperty.SSL_CERT.set(props,
          certdir + "/" + clientCertificate.fileName + ".crt");
      PGProperty.SSL_KEY.set(props,
          certdir + "/" + clientCertificate.fileName + ".pk8");
    }
    if (clientRootCertificate == ClientRootCertificate.EMPTY) {
      PGProperty.SSL_ROOT_CERT.set(props, "");
    } else {
      PGProperty.SSL_ROOT_CERT.set(props,
          certdir + "/" + clientRootCertificate.fileName + ".crt");
    }
  }

  @Override
  public void setUp() throws Exception {
    SQLException e = null;
    try {
      super.setUp();
    } catch (SQLException ex) {
      e = ex;
    }

    try {
      // Note that checkErrorCodes throws AssertionError for unexpected cases
      checkErrorCodes(e);
    } catch (AssertionError ae) {
      // Make sure original SQLException is printed as well even in case of AssertionError
      if (e != null) {
        ae.initCause(e);
      }
      throw ae;
    }
  }

  private void assertClientCertRequired(SQLException e, String caseName) {
    if (e == null) {
      Assert.fail(caseName + " should result in failure of client validation");
    }
    Assert.assertEquals(caseName + " ==> CONNECTION_FAILURE is expected",
        PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState());
  }

  private void checkErrorCodes(SQLException e) {
    if (e == null && sslmode == SslMode.ALLOW && !db.requiresSsl()) {
      // allowed to connect with plain connection
      return;
    }

    if (clientRootCertificate == ClientRootCertificate.EMPTY
        && (sslmode == SslMode.VERIFY_CA || sslmode == SslMode.VERIFY_FULL)) {
      String caseName = "rootCertificate is missing and sslmode=" + sslmode;
      if (e == null) {
        Assert.fail(caseName + " should result in FileNotFound exception for root certificate");
      }
      Assert.assertEquals(caseName + " ==> CONNECTION_FAILURE is expected",
          PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState());
      FileNotFoundException fnf = findCause(e, FileNotFoundException.class);
      if (fnf == null) {
        Assert.fail(caseName + " ==> FileNotFoundException should be present in getCause chain");
      }
      return;
    }

    if (db.requiresSsl() && sslmode == SslMode.DISABLE) {
      String caseName = "sslmode=DISABLE and database " + db + " requires SSL";
      if (e == null) {
        Assert.fail(caseName + " should result in connection failure");
      }
      Assert.assertEquals(caseName + " ==> INVALID_AUTHORIZATION_SPECIFICATION is expected",
          PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState());
      return;
    }

    if (db.rejectsSsl() && sslmode.requireEncryption()) {
      String caseName =
          "database " + db + " rejects SSL, and sslmode " + sslmode + " requires encryption";
      if (e == null) {
        Assert.fail(caseName + " should result in connection failure");
      }
      Assert.assertEquals(caseName + " ==> INVALID_AUTHORIZATION_SPECIFICATION is expected",
          PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState());
      return;
    }

    // Server certificate, server hostname, and client certificate can be validated in any order
    // So we have three validators and expect at least one of them to match
    List<AssertionError> errors = null;
    try {
      if (assertServerCertificate(e)) {
        return;
      }
    } catch (AssertionError ae) {
      errors = addError(errors, ae);
    }

    try {
      if (assertServerHostname(e)) {
        return;
      }
    } catch (AssertionError ae) {
      errors = addError(errors, ae);
    }


    try {
      if (assertClientCertificate(e)) {
        return;
      }
    } catch (AssertionError ae) {
      errors = addError(errors, ae);
    }

    if (sslmode == SslMode.ALLOW && db.requiresSsl()) {
      // Allow tries to connect with non-ssl first, and it always throws the first error even after try SSL.
      // "If SSL was expected to fail" (e.g. invalid certificate), and db requiresSsl, then ALLOW
      // should fail as well
      String caseName =
          "sslmode=ALLOW and db " + db + " requires SSL, and there are expected SSL failures";
      if (errors == null) {
        if (e != null) {
          Assert.fail(caseName + " ==> connection should be upgraded to SSL with no failures");
        }
      } else {
        if (e == null) {
          Assert.fail(caseName + " ==> connection should fail");
        }
        Assert.assertEquals(caseName + " ==> INVALID_AUTHORIZATION_SPECIFICATION is expected",
            PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState());
      }
      // ALLOW is ok
      return;
    }

    if (errors == null) {
      if (e == null) {
        // Assume "no exception" was expected.
        // The cases like "successfully connected in sslmode=DISABLE to SSLONLY db"
        // should be handled with assertions above
        return;
      }
      Assert.fail("SQLException present when it was not expected");
    }

    AssertionError firstError = errors.get(0);
    if (errors.size() == 1) {
      throw firstError;
    }

    for (int i = 1; i < errors.size(); i++) {
      AssertionError error = errors.get(i);
      // addSuppressed is Java 1.7+
      //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
      firstError.addSuppressed(error);
      //#endif
      error.printStackTrace();
    }

    throw firstError;
  }

  private List<AssertionError> addError(List<AssertionError> errors, AssertionError ae) {
    if (errors == null) {
      errors = new ArrayList<AssertionError>();
    }
    errors.add(ae);
    return errors;
  }

  /**
   * Checks server certificate validation error.
   *
   * @param e connection exception or null if no exception
   * @return true when validation pass, false when the case is not applicable
   * @throws AssertionError when exception does not match expectations
   */
  private boolean assertServerCertificate(SQLException e) {
    if (clientRootCertificate == ClientRootCertificate.GOOD
        || (sslmode != SslMode.VERIFY_CA && sslmode != SslMode.VERIFY_FULL)) {
      return false;
    }

    String caseName = "Server certificate is " + clientRootCertificate + " + sslmode=" + sslmode;
    if (e == null) {
      Assert.fail(caseName + " should result in failure of server validation");
    }

    Assert.assertEquals(caseName + " ==> CONNECTION_FAILURE is expected",
        PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState());
    CertPathValidatorException validatorEx = findCause(e, CertPathValidatorException.class);
    if (validatorEx == null) {
      Assert.fail(caseName + " ==> exception should be caused by CertPathValidatorException,"
          + " but no CertPathValidatorException is present in the getCause chain");
    }
    // getReason is Java 1.7+
    //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
    Assert.assertEquals(caseName + " ==> CertPathValidatorException.getReason",
        "NO_TRUST_ANCHOR", validatorEx.getReason().toString());
    //#endif
    return true;
  }

  /**
   * Checks hostname validation error.
   *
   * @param e connection exception or null if no exception
   * @return true when validation pass, false when the case is not applicable
   * @throws AssertionError when exception does not match expectations
   */
  private boolean assertServerHostname(SQLException e) {
    if (sslmode != SslMode.VERIFY_FULL || host != Hostname.BAD) {
      return false;
    }

    String caseName = "VERIFY_FULL + hostname that does not match server certificate";
    if (e == null) {
      Assert.fail(caseName + " ==> CONNECTION_FAILURE expected");
    }
    Assert.assertEquals(caseName + " ==> CONNECTION_FAILURE is expected",
        PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState());
    if (!e.getMessage().contains("PgjdbcHostnameVerifier")) {
      Assert.fail(caseName + " ==> message should contain"
          + " 'PgjdbcHostnameVerifier'. Actual message is " + e.getMessage());
    }
    return true;
  }

  /**
   * Checks client certificate validation error.
   *
   * @param e connection exception or null if no exception
   * @return true when validation pass, false when the case is not applicable
   * @throws AssertionError when exception does not match expectations
   */
  private boolean assertClientCertificate(SQLException e) {
    if (db.requiresClientCert() && clientCertificate == ClientCertificate.EMPTY) {
      String caseName =
          "client certificate was not sent and database " + db + " requires client certificate";
      assertClientCertRequired(e, caseName);
      return true;
    }

    if (clientCertificate != ClientCertificate.BAD) {
      return false;
    }
    // Server verifies certificate no matter how it is configured, so sending BAD one
    // is doomed to fail
    String caseName = "BAD client certificate, and database " + db + " requires one";
    if (e == null) {
      Assert.fail(caseName + " should result in failure of client validation");
    }
    Assert.assertEquals(caseName + " ==> CONNECTION_FAILURE is expected",
        PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState());

    // Two exceptions are possible
    // SSLHandshakeException: Received fatal alert: unknown_ca
    // SocketException: broken pipe (write failed)

    SocketException brokenPipe = findCause(e, SocketException.class);
    SSLHandshakeException handshakeException = findCause(e, SSLHandshakeException.class);

    if (brokenPipe == null && handshakeException == null) {
      Assert.fail(caseName + " ==> exception should be caused by SocketException(broken pipe)"
          + " or SSLHandshakeException. No exceptions of such kind are present in the getCause chain");
    }
    if (brokenPipe != null && !brokenPipe.getMessage().contains("Broken pipe")) {
      Assert.fail(
          caseName + " ==> server should have terminated the connection (broken pipe expected)"
              + ", actual exception was " + brokenPipe.getMessage());
    }
    if (handshakeException != null && !handshakeException.getMessage().contains("unknown_ca")) {
      Assert.fail(
          caseName + " ==> server should have terminated the connection (expected 'unknown_ca')"
              + ", actual exception was " + handshakeException.getMessage());
    }
    return true;
  }

  private static <T extends Throwable> T findCause(Throwable t, Class<T> cause) {
    while (t != null) {
      if (cause.isInstance(t)) {
        return (T) t;
      }
      t = t.getCause();
    }
    return null;
  }


  @Test
  public void run() throws SQLException {
    if (con == null) {
      // e.g. expected failure to connect
      return;
    }
    ResultSet rs = con.createStatement().executeQuery("select ssl_is_used()");
    Assert.assertTrue("select ssl_is_used() should return a row", rs.next());
    boolean sslUsed = rs.getBoolean(1);
    if (sslmode == SslMode.ALLOW) {
      Assert.assertEquals("ssl_is_used: ",
          db.requiresSsl(),
          sslUsed);
    } else {
      Assert.assertEquals("ssl_is_used: ",
          sslmode != SslMode.DISABLE && !db.rejectsSsl(),
          sslUsed);
    }
    TestUtil.closeQuietly(rs);
  }

}
