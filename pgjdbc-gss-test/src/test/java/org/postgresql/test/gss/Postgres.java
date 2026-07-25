/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * Drives a throwaway PostgreSQL server for the GSS test: initialises a data directory, starts the
 * server and rewrites pg_hba.conf / pg_ident.conf / postgresql.conf to toggle GSS behaviour.
 */
class Postgres {
  private final String binPath;
  private final String dataPath;
  private final String hostName = "127.0.0.1";
  private int port;

  Postgres() throws IOException, InterruptedException {
    this("/usr/local/pgsql/16/bin/", "/tmp/pgdata16");
  }

  Postgres(String binDir, String dataDir) throws IOException, InterruptedException {
    this.binPath = binDir;
    this.dataPath = dataDir;
    initDb();
  }

  private void initDb() throws IOException, InterruptedException {
    if (!new File(dataPath).exists()) {
      System.err.println("Initializing db at " + dataPath);
      GssTestUtil.runAndWait(
          Arrays.asList(binPath + "/initdb", "--auth=trust", "-D", dataPath), null);
    }
  }

  int getPort() {
    return port;
  }

  /**
   * Picks a free port and starts the server, passing the Kerberos environment so the backend can
   * locate its configuration and keytab.
   */
  Process startPostgres(Map<String, String> krb5Env) throws IOException {
    port = GssTestUtil.findFreePort();
    // -i enables TCP connections (JDBC needs them); -k /tmp keeps the unix socket out of the data dir
    System.err.println(
        "executing postgres datapath: " + dataPath + ", host: " + hostName + ", port: " + port);
    return GssTestUtil.start(
        Arrays.asList(binPath + "/postgres", "-h", hostName, "-k", "/tmp",
            "-p", Integer.toString(port), "-i", "-D", dataPath),
        krb5Env);
  }

  void reload() throws IOException, InterruptedException {
    GssTestUtil.runAndWait(
        Arrays.asList(binPath + "/pg_ctl", "-D", dataPath, "reload"), null);
  }

  boolean waitForHba(int milliseconds) {
    long deadline = System.nanoTime() + (long) (milliseconds * 1E6);
    while (System.nanoTime() < deadline) {
      if (new File(dataPath, "pg_hba.conf").exists()) {
        return true;
      }
    }
    return false;
  }

  void writePgHba(String text) throws IOException {
    GssTestUtil.writeText(dataPath + "/pg_hba.conf", text, true);
  }

  String readPgHba() throws IOException {
    return GssTestUtil.readText(dataPath + "/pg_hba.conf");
  }

  private void writePgIdent(String text) throws IOException {
    GssTestUtil.writeText(dataPath + "/pg_ident.conf", text, true);
  }

  private void writePgConf(String text) throws IOException {
    GssTestUtil.writeText(dataPath + "/postgresql.conf", text, false);
  }

  void setKeyTabLocation(String location) throws IOException {
    writePgConf("krb_server_keyfile = '" + location + "'");
  }

  void enableGss(String hostAddress, String mode) throws IOException {
    writePgHba(mode + " all all " + hostAddress + "/32 gss map=mymap");
  }

  void enableMyMap(String realm) throws IOException {
    writePgIdent("mymap  /^(.*)@" + realm + "$  \\1");
  }

  /** Maps the Kerberos principal to a database user that differs from the Kerberos login user. */
  void enableOwnerMap(String principal, String realm, String user) throws IOException {
    writePgIdent("mymap " + principal + "@" + realm + "  " + user);
  }
}
