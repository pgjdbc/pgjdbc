/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.GSSEncMode;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * End-to-end GSSAPI test: it boots a local Kerberos KDC and PostgreSQL server, then verifies that
 * pgjdbc can authenticate over GSSAPI both with and without connection encryption, including the
 * case where the Kerberos principal is mapped to a different database user.
 *
 * <p>The module is only built when {@code -PgssTests} is passed, and the test relies on a Kerberos
 * toolchain (krb5-kdc, kadmin, kinit) and a local PostgreSQL installation, so in practice it runs
 * in the dedicated CI "gss" job on Linux.
 */
class GssEncryptionTest {
  private static final String KERBEROS_HOST = "auth-test-localhost.postgresql.example.com";

  @Test
  void gssAuthenticationAndEncryption() throws Exception {
    String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    boolean isMac = osName.contains("mac");

    Kerberos kerberos = new Kerberos();
    kerberos.startKerberos();

    Process pg = null;
    try {
      Postgres postgres = isMac
          ? new Postgres()
          : new Postgres("/usr/lib/postgresql/16/bin/", "/tmp/pggss");

      // Make sure we can connect with a trusted superuser before tightening the rules
      postgres.writePgHba("host    all             all             127.0.0.1/32            trust");
      assertTrue(postgres.waitForHba(5000), "pg_hba.conf was not created");

      pg = postgres.startPostgres(kerberos.getEnvironment());
      Thread.sleep(2000);

      String superUser = System.getProperty("user.name");
      String superPass = "test";

      PgGssConnection client = new PgGssConnection("127.0.0.1", postgres.getPort());
      client.addProperty(PGProperty.GSS_ENC_MODE, GSSEncMode.DISABLE.value);
      client.createUser(superUser, superPass, "test1", "secret1");
      client.createUser(superUser, superPass, "test2", "secret2");
      client.createDatabase(superUser, superPass, "test1", "test");
      client.createDatabase(superUser, superPass, "test2", "test2");

      postgres.enableGss("127.0.0.1", "hostgssenc");
      postgres.enableMyMap("EXAMPLE.COM");
      postgres.setKeyTabLocation(kerberos.getKeytab());
      postgres.reload();

      client.addProperty(PGProperty.GSS_ENC_MODE, GSSEncMode.REQUIRE.value);
      client.addProperty(PGProperty.JAAS_LOGIN, true);
      client.addProperty(PGProperty.JAAS_APPLICATION_NAME, "pgjdbc");

      // The Kerberos principal test1 maps to the database user test1
      assertGssConnection(client, postgres, "test", "test1", "secret1",
          "SELECT gss_authenticated AND encrypted from pg_stat_gssapi where pid = pg_backend_pid()",
          "GSS authenticated and encrypted");

      // The Kerberos principal test1 now maps to a different database user, test2
      postgres.enableOwnerMap("test1", "EXAMPLE.COM", "test2");
      postgres.reload();
      assertGssConnection(client, postgres, "test2", "test2", "secret2",
          "SELECT gss_authenticated AND encrypted from pg_stat_gssapi where pid = pg_backend_pid()",
          "GSS authenticated and encrypted");

      // GSS authentication without connection encryption
      postgres.enableMyMap("EXAMPLE.COM");
      postgres.enableGss("127.0.0.1", "hostnogssenc");
      postgres.reload();
      client.addProperty(PGProperty.GSS_ENC_MODE, GSSEncMode.DISABLE.value);
      assertGssConnection(client, postgres, "test", "test1", "secret1",
          "SELECT gss_authenticated AND not encrypted from pg_stat_gssapi where pid = pg_backend_pid()",
          "GSS authenticated and not encrypted");
    } finally {
      if (pg != null) {
        pg.destroy();
      }
      kerberos.destroy();
    }
  }

  private static void assertGssConnection(PgGssConnection client, Postgres postgres, String database,
      String user, String password, String query, String description) throws Exception {
    try (Connection connection =
             client.tryConnect(database, KERBEROS_HOST, postgres.getPort(), user, password)) {
      assertTrue(client.select(connection, query),
          description + " connection failed; pg_hba.conf:\n" + postgres.readPgHba());
      System.err.println(description + " connection succeeded");
    } catch (SQLException ex) {
      System.err.println("pg_hba.conf:\n" + postgres.readPgHba());
      throw ex;
    }
  }
}
