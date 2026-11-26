/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.GSSEncMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;

/**
 * The driver authenticates over GSSAPI as the Kerberos principal {@code gss_test1@EXAMPLE.COM},
 * and encrypts the session when {@code gssEncMode} requires it.
 *
 * <p>The tests need the server and the KDC that {@code docker/postgres-server} starts with
 * {@code GSS=yes} and the {@code gss} profile. That configuration adds one {@code pg_hba.conf} rule
 * per database, so each test picks its scenario by the database it connects to. {@code jaas.conf},
 * next to this class in the test resources, fixes the principal, and the connection password is
 * that principal's Kerberos password; the {@code user} property is only the database user.
 *
 * <p>The module runs only with {@code -PgssTests}. The rules use {@code hostgssenc}, which
 * PostgreSQL 12 introduced, so the tests are skipped on an older server.
 */
@EnabledForServerVersionRange(gte = "12")
class GssEncryptionTest {
  private static final String PRINCIPAL_PASSWORD = "secret1";

  /**
   * Points the JDK's Kerberos client and JAAS at {@code krb5.conf} and {@code jaas.conf} next to
   * this class, so the tests need no JVM options and run from an IDE as they do from Gradle.
   */
  @BeforeAll
  static void configureKerberosClient() throws URISyntaxException {
    System.setProperty("java.security.krb5.conf",
        Paths.get(resource("krb5.conf").toURI()).toString());
    System.setProperty("java.security.auth.login.config", resource("jaas.conf").toExternalForm());
  }

  private static URL resource(String name) {
    return Objects.requireNonNull(GssEncryptionTest.class.getResource(name),
        () -> name + " is missing from the test resources of " + GssEncryptionTest.class.getPackage());
  }

  @Test
  void requiredEncryptionGivesAnEncryptedSession() throws SQLException {
    assertEquals(
        "gss_authenticated=t encrypted=t principal=gss_test1@EXAMPLE.COM current_user=gss_test1",
        gssSession("gss_enc", "gss_test1", GSSEncMode.REQUIRE));
  }

  @Test
  void disabledEncryptionGivesAnUnencryptedGssAuthenticatedSession() throws SQLException {
    assertEquals(
        "gss_authenticated=t encrypted=f principal=gss_test1@EXAMPLE.COM current_user=gss_test1",
        gssSession("gss_auth", "gss_test1", GSSEncMode.DISABLE));
  }

  /** The {@code gss_mapped} database maps the principal to {@code gss_test2} in {@code pg_ident.conf}. */
  @Test
  void anIdentMapLetsThePrincipalLogInAsAnotherUser() throws SQLException {
    assertEquals(
        "gss_authenticated=t encrypted=t principal=gss_test1@EXAMPLE.COM current_user=gss_test2",
        gssSession("gss_mapped", "gss_test2", GSSEncMode.REQUIRE));
  }

  /**
   * The {@code gss_unmapped} database differs from {@code gss_mapped} only by having no ident map. The
   * server reports a missing {@code pg_hba.conf} rule with the same SQLState, so the test checks
   * the message too.
   */
  @Test
  void withoutAnIdentMapThePrincipalCannotLogInAsAnotherUser() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> gssSession("gss_unmapped", "gss_test2", GSSEncMode.REQUIRE));
    ServerErrorMessage serverError = e.getServerErrorMessage();
    try {
      assertAll(
          () -> assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), e.getSQLState()),
          () -> assertEquals("GSSAPI authentication failed for user \"gss_test2\"",
              serverError == null ? null : serverError.getMessage()));
    } catch (AssertionError failure) {
      // A refusal for another reason, such as a failed Kerberos login, is explained by e's cause
      failure.addSuppressed(e);
      throw failure;
    }
  }

  /**
   * Connects to {@code database} as {@code user} and returns the GSSAPI state of the session as
   * {@code pg_stat_gssapi} reports it, with the database user the server assigned.
   */
  private static String gssSession(String database, String user, GSSEncMode gssEncMode)
      throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, user);
    PGProperty.PASSWORD.set(props, PRINCIPAL_PASSWORD);
    PGProperty.GSS_ENC_MODE.set(props, gssEncMode.value);
    try (Connection connection = DriverManager.getConnection(url(database), props);
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(
             "select format('gss_authenticated=%s encrypted=%s principal=%s current_user=%s',"
                 + " gss_authenticated, encrypted, principal, current_user)"
                 + " from pg_stat_gssapi where pid = pg_backend_pid()")) {
      assertTrue(resultSet.next(), "pg_stat_gssapi has a row for pg_backend_pid()");
      return resultSet.getString(1);
    }
  }

  /**
   * Returns the JDBC URL of {@code database} on the test server. The host is {@code localhost}
   * because the driver builds the service principal from it, and the keytab holds
   * {@code postgres/localhost}.
   */
  private static String url(String database) {
    return "jdbc:postgresql://localhost:" + TestUtil.getPort() + "/" + database;
  }
}
