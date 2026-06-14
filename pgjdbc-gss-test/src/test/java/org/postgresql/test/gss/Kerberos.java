/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sets up a throwaway Kerberos KDC for the GSS test: it writes krb5.conf / kdc.conf, creates the
 * realm database, the {@code test1} principal and the {@code postgres/<host>} service principal,
 * starts the KDC and obtains an initial ticket via {@code kinit}.
 */
class Kerberos {
  private static final String HOST = "auth-test-localhost.postgresql.example.com";
  private static final String HOST_ADDR = "127.0.0.1";
  private static final String REALM = "EXAMPLE.COM";

  private String krb5BinDir;
  private String krb5SbinDir;
  private String kinit = "kinit";
  private String kdb5Util = "kdb5_util";
  private String kadminLocal = "kadmin.local";
  private String krb5kdc = "krb5kdc";

  private String krb5Conf;
  private String kdcConf;
  private String kdcCache;
  private String krb5Log;
  private String kdcLog;
  private int kdcPort;
  private String kdcDataDir;
  private String kdcPidfile;
  private String keytab;

  private final Map<String, String> env = new LinkedHashMap<>();
  private Process krb5Process;

  String getKeytab() {
    return keytab;
  }

  Map<String, String> getEnvironment() {
    return env;
  }

  private void getBinDir() {
    String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    if (osName.contains("mac")) {
      krb5BinDir = "/usr/local/opt/krb5/bin";
      krb5SbinDir = "/usr/local/opt/krb5/sbin";
    } else if (osName.contains("freebsd")) {
      krb5BinDir = "/usr/local/bin";
      krb5SbinDir = "/usr/local/sbin";
    } else if (osName.contains("linux")) {
      krb5BinDir = "/usr/bin";
      krb5SbinDir = "/usr/sbin";
    }
  }

  private void setupKerberos(String testLib) throws IOException {
    if (krb5BinDir != null && new File(krb5BinDir).exists()) {
      kinit = krb5BinDir + "/" + kinit;
    }
    if (krb5SbinDir != null && new File(krb5SbinDir).exists()) {
      kdb5Util = krb5SbinDir + "/" + kdb5Util;
      kadminLocal = krb5SbinDir + "/" + kadminLocal;
      krb5kdc = krb5SbinDir + "/" + krb5kdc;
    }

    String tmpCheck = testLib + "/tmp_check";
    krb5Conf = tmpCheck + "/krb5.conf";
    kdcConf = tmpCheck + "/kdc.conf";
    kdcCache = tmpCheck + "/krb5cc";
    krb5Log = tmpCheck + "/log/krb5libs.log";
    kdcLog = tmpCheck + "/log/krb5kdc.log";
    kdcPort = GssTestUtil.findFreePort();
    kdcDataDir = tmpCheck + "/krb5kdc";
    kdcPidfile = tmpCheck + "/krb5kdc.pid";
    keytab = tmpCheck + "/krb5.keytab";

    System.err.println("setting up Kerberos");

    new File(tmpCheck).mkdirs();
    new File(tmpCheck, "log").mkdirs();

    Files.deleteIfExists(Paths.get(krb5Conf));
    Files.deleteIfExists(Paths.get(kdcConf));

    GssTestUtil.writeText(krb5Conf,
        "[logging]\n"
            + "default = FILE:" + krb5Log + "\n"
            + "kdc = FILE:" + kdcLog + "\n"
            + "\n"
            + "[libdefaults]\n"
            + "default_realm = " + REALM + "\n"
            + "canonicalize = true\n"
            + "\n"
            + "[realms]\n"
            + REALM + " = {\n"
            + "    kdc = " + HOST_ADDR + ":" + kdcPort + "\n"
            + "}",
        true);

    // For new-enough versions of krb5 (1.15+) the *_listen settings let us bind to localhost only.
    GssTestUtil.writeText(kdcConf,
        "[kdcdefaults]\n"
            + "kdc_listen = " + HOST_ADDR + ":" + kdcPort + "\n"
            + "kdc_tcp_listen = " + HOST_ADDR + ":" + kdcPort + "\n"
            + "\n"
            + "[realms]\n"
            + REALM + " = {\n"
            + "    database_name = " + kdcDataDir + "/principal\n"
            + "    admin_keytab = FILE:" + kdcDataDir + "/kadm5.keytab\n"
            + "    acl_file = " + kdcDataDir + "/kadm5.acl\n"
            + "    key_stash_file = " + kdcDataDir + "/_k5." + REALM + "\n"
            + "}",
        true);
  }

  private void runKerberos() throws IOException, InterruptedException {
    mkdir(kdcDataDir);

    env.put("KRB5_CONFIG", krb5Conf);
    env.put("KRB5_KDC_PROFILE", kdcConf);
    env.put("KRB5CCNAME", kdcCache);

    String servicePrincipal = "postgres/" + HOST;
    String test1Password = "secret1";

    GssTestUtil.runAndWait(
        Arrays.asList(kdb5Util, "create", "-s", "-P", "secret0"), env);
    GssTestUtil.runAndWait(
        Arrays.asList(kadminLocal, "-q", "addprinc -pw " + test1Password + " test1"), env);
    GssTestUtil.runAndWait(
        Arrays.asList(kadminLocal, "-q", "addprinc -randkey " + servicePrincipal), env);
    GssTestUtil.runAndWait(
        Arrays.asList(kadminLocal, "-q", "ktadd -k " + keytab + " " + servicePrincipal), env);

    krb5Process = GssTestUtil.start(Arrays.asList(krb5kdc, "-P", kdcPidfile), env);
    // Give the KDC a moment to bind before requesting a ticket
    Thread.sleep(1000);

    Process kinitProcess = GssTestUtil.start(Arrays.asList(kinit, "test1"), env);
    GssTestUtil.writeStdin(kinitProcess, test1Password + "\n");
    kinitProcess.waitFor();
  }

  private void mkdir(String newDir) {
    File dir = new File(newDir);
    if (dir.exists()) {
      GssTestUtil.deleteRecursively(dir);
    }
    dir.mkdirs();
    dir.deleteOnExit();
  }

  void destroy() {
    try {
      String pid = GssTestUtil.readText(kdcPidfile).trim();
      GssTestUtil.runAndWait(Arrays.asList("kill", "-TERM", pid), null);
    } catch (IOException | InterruptedException ex) {
      System.err.println("Unable to stop the KDC: " + ex);
    }
    if (krb5Process != null) {
      krb5Process.destroy();
    }
    new File(keytab).delete();
  }

  void startKerberos() throws IOException, InterruptedException {
    getBinDir();
    String curDir = System.getProperty("user.dir");
    setupKerberos(curDir);
    // Tell the pure-Java Kerberos implementation where to look (native GSSAPI uses KRB5_CONFIG)
    System.setProperty("java.security.krb5.conf", krb5Conf);
    runKerberos();
  }
}
