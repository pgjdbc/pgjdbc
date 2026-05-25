/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.util.URLCoder;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.net.URL;
import java.util.Properties;

/**
 * Tests jdbc URL parser.
 *
 * @author Marek Läll
 */
public class JdbcUrlResolverTest {

  private static final String PGPASS_FILE_NAME = "/pgpass/.pgpass";

  @BeforeAll
  public static void setUp() throws Exception {
    // fix pgpass file permissions
    Utils.setPgpassFilePermissions(PGPASS_FILE_NAME);
    //
    // Verify that related properties and environment variables are not set. Fail, if any is set.
    // If they are set then test cases will fail.
    // Alternative would be clearing environment before tests, but it needs ugly hacking
    // because System.getenv().remove() reports UnsupportedOperationException.
    // Reflection or other method should be used.
    for (PGEnvironment env : PGEnvironment.values()) {
      String value = env.readStringValue();
      assertNull(value, String.format(
          "Environment variable [%s] has value [%s] but for successful unit testing it must be null.",
          env.getName(), value
      ));
    }
  }

  @Test
  public void testCaseSet1() {
    // invalid syntax
    failUrl("db", "url must start with 'jdbc:postgresql:[//]'");
    failUrl("jdbc:postgresql", "url must start with 'jdbc:postgresql:[//]'");
    failUrl("jdbc:postgresqL:", "url must start with 'jdbc:postgresql:[//]'");
    failUrl("jdbc:postgres:test", "url must start with 'jdbc:postgresql:[//]'");
    // jdbc exception. this syntax not supported by libpq
    verifyUrl("jdbc:postgresql:/", "localhost", "5432", "/", "osUser", null);
    verifyUrl("jdbc:postgresql:/astring", "localhost", "5432", "/astring", "osUser", null);
    verifyUrl("jdbc:postgresql:urlDb", "localhost", "5432", "urlDb", "osUser", null);
    verifyUrl("jdbc:postgresql:%2F", "localhost", "5432", "/", "osUser", null);
    verifyUrl("jdbc:postgresql:%2F/", "localhost", "5432", "//", "osUser", null);
    verifyUrl("jdbc:postgresql:%2F/local:2222/?user=urlUser", "localhost", "5432", "//local:2222/?user=urlUser", "osUser", null);
    // errorMessage argument is null because error message depends on java version
    // min - 1.8 : "url [te%1st] parsing failed [URLDecoder: Illegal hex characters in escape (%) pattern - For input string: \"1s\"]"
    //   9 -  24 : "url [te%1st] parsing failed [URLDecoder: Illegal hex characters in escape (%) pattern - Error at index 1 in: \"1s\"]"
    //  25 - max : "url [te%1st] parsing failed [URLDecoder: Illegal hex characters in escape (%) pattern - not a hexadecimal digit: "s" = 115]"
    failUrl("jdbc:postgresql:te%1st", null);
    // full syntax
    verifyUrl("jdbc:postgresql://:@/?&", "localhost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://:@/urlDb?", "localhost", "5432", "urlDb", "osUser", null);
    verifyUrl("jdbc:postgresql://:urlPass@/urlDb?", "localhost", "5432", "urlDb", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://:urlPass@urlHost/urlDb?", "urlHost", "5432", "urlDb", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://:urlPass@urlHost,127.0.0.1/urlDb?", "urlHost,127.0.0.1", "5432,5432", "urlDb", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://:urlPass@urlHost,127.0.0.1,[2001:4860:4860::8888]/urlDb?", "urlHost,127.0.0.1,[2001:4860:4860::8888]", "5432,5432,5432", "urlDb", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://urlUser:urlPass@urlHost/urlDb?", "urlHost", "5432", "urlDb", "urlUser", "urlPass");
    verifyUrl("jdbc:postgresql://urlUser:urlPass@urlHost:2222/urlDb?", "urlHost", "2222", "urlDb", "urlUser", "urlPass");
    verifyUrl("jdbc:postgresql://urlUser:urlPass@urlHost:2222/urlDb?password=argPass", "urlHost", "2222", "urlDb", "urlUser", "argPass");
    // user
    verifyUrl("jdbc:postgresql://urlUser@", "localhost", "5432", "urlUser", "urlUser", null);
    verifyUrl("jdbc:postgresql:// urlUser@", "localhost", "5432", " urlUser", " urlUser", null);
    verifyUrl("jdbc:postgresql://urlUser @", "localhost", "5432", "urlUser ", "urlUser ", null);
    verifyUrl("jdbc:postgresql:// @", "localhost", "5432", " ", " ", null);
    verifyUrl("jdbc:postgresql://urlUser@/", "localhost", "5432", "urlUser", "urlUser", null);
    verifyUrl("jdbc:postgresql://urlUser@/?", "localhost", "5432", "urlUser", "urlUser", null);
    verifyUrl("jdbc:postgresql://urlUser@?", "localhost", "5432", "urlUser", "urlUser", null);
    verifyUrl("jdbc:postgresql://first.last%40company.org@?", "localhost", "5432", "first.last@company.org", "first.last@company.org", null);
    verifyUrl("jdbc:postgresql://first.last%40company.org@?user=arg@usr.org", "localhost", "5432", "arg@usr.org", "arg@usr.org", null);
    // errorMessage argument is null because error message depends on java version
    failUrl("jdbc:postgresql://url%1User@", null);
    // password
    verifyUrl("jdbc:postgresql://:urlPass@", "localhost", "5432", "osUser", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://: urlPass@", "localhost", "5432", "osUser", "osUser", " urlPass");
    verifyUrl("jdbc:postgresql://:urlPass @", "localhost", "5432", "osUser", "osUser", "urlPass ");
    verifyUrl("jdbc:postgresql://: @", "localhost", "5432", "osUser", "osUser", " ");
    verifyUrl("jdbc:postgresql://:url%2FPass@", "localhost", "5432", "osUser", "osUser", "url/Pass");
    verifyUrl("jdbc:postgresql://:urlPass@/", "localhost", "5432", "osUser", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://:urlPass@?", "localhost", "5432", "osUser", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://:urlPass@/?", "localhost", "5432", "osUser", "osUser", "urlPass");
    verifyUrl("jdbc:postgresql://:urlPass@?password=argPass", "localhost", "5432", "osUser", "osUser", "argPass");
    verifyUrl("jdbc:postgresql://:urlPass@?password= argPass", "localhost", "5432", "osUser", "osUser", " argPass");
    verifyUrl("jdbc:postgresql://:urlPass@?password=argPass ", "localhost", "5432", "osUser", "osUser", "argPass ");
    verifyUrl("jdbc:postgresql://:urlPass@?password=arg%2FPass", "localhost", "5432", "osUser", "osUser", "arg/Pass");
    // errorMessage argument is null because error message depends on java version
    failUrl("jdbc:postgresql://:url%1Pass@", null);
    // host
    verifyUrl("jdbc:postgresql://urlHost", "urlHost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql:// urlHost", " urlHost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost ", "urlHost ", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql:// ", " ", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql:// , ", " , ", "5432,5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost.org", "urlHost.org", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://127.0.0.1", "127.0.0.1", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://[2001:4860:4860::8888]", "[2001:4860:4860::8888]", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://räksmörgås.josefsson.org/", "räksmörgås.josefsson.org", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost/", "urlHost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost/?", "urlHost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost?", "urlHost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://url%2DHost.org", "url-Host.org", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://host1 , host2/", "host1 , host2", "5432,5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost.org,127.0.0.1,[2001:4860:4860::8888]/", "urlHost.org,127.0.0.1,[2001:4860:4860::8888]", "5432,5432,5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost?host=argHost", "argHost", "5432", "osUser", "osUser", null);
    // errorMessage argument is null because error message depends on java version
    failUrl("jdbc:postgresql://url%1Host", null);
    // port
    verifyUrl("jdbc:postgresql://:3333", "localhost", "3333", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://: 1", "localhost", "1", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://:65535 ", "localhost", "65535", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://:003333", "localhost", "003333", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://: ", "localhost", "5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost:3333", "urlHost", "3333", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost:3333/", "urlHost", "3333", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost:3333?", "urlHost", "3333", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost1:3333,urlHost2", "urlHost1,urlHost2", "3333,5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://[::1]:6666,urlHost1:3333,127.0.0.1", "[::1],urlHost1,127.0.0.1", "6666,3333,5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://urlHost1:3333,urlHost2?port=4444", "urlHost1,urlHost2", "4444,4444", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://:3333,urlHost2", "localhost,urlHost2", "3333,5432", "osUser", "osUser", null);
    verifyUrl("jdbc:postgresql://: 3333,:3333 ,: 3333 /", "localhost,localhost,localhost", "3333,3333,3333", "osUser", "osUser", null);
    failUrl("jdbc:postgresql://:-1", "invalid port number: [-1]");
    failUrl("jdbc:postgresql://:0", "invalid port number: [0]");
    failUrl("jdbc:postgresql://:65536", "invalid port number: [65536]");
    failUrl("jdbc:postgresql://:2222a", "invalid integer value [2222a] for connection option 'port'");
    failUrl("jdbc:postgresql://:22 22", "invalid integer value [22 22] for connection option 'port'");
    failUrl("jdbc:postgresql://:5%1", "url [5%1] parsing failed [URLDecoder: Incomplete trailing escape (%) pattern]");
    // db
    verifyUrl("jdbc:postgresql:///urlDb", "localhost", "5432", "urlDb", "osUser", null);
    verifyUrl("jdbc:postgresql:/// urlDb", "localhost", "5432", " urlDb", "osUser", null);
    verifyUrl("jdbc:postgresql:///urlDb ", "localhost", "5432", "urlDb ", "osUser", null);
    verifyUrl("jdbc:postgresql:/// ", "localhost", "5432", " ", "osUser", null);
    verifyUrl("jdbc:postgresql:///urlDb?", "localhost", "5432", "urlDb", "osUser", null);
    verifyUrl("jdbc:postgresql:////urlDb", "localhost", "5432", "/urlDb", "osUser", null);
    verifyUrl("jdbc:postgresql:///urlDb/", "localhost", "5432", "urlDb/", "osUser", null);
    verifyUrl("jdbc:postgresql:///urlDb/?", "localhost", "5432", "urlDb/", "osUser", null);
    verifyUrl("jdbc:postgresql:///url%2FDb", "localhost", "5432", "url/Db", "osUser", null);
    verifyUrl("jdbc:postgresql:///urlDb?dbname=argDb", "localhost", "5432", "argDb", "osUser", null);
    // errorMessage argument is null because error message depends on java version
    failUrl("jdbc:postgresql:///ur%1lDb", null);
    // args
    verifyUrl("jdbc:postgresql://?user=argUser", "localhost", "5432", "argUser", "argUser", null);
    verifyUrl("jdbc:postgresql://?user= argUser", "localhost", "5432", " argUser", " argUser", null);
    verifyUrl("jdbc:postgresql://?user=argUser ", "localhost", "5432", "argUser ", "argUser ", null);
    verifyUrl("jdbc:postgresql://?user= ", "localhost", "5432", " ", " ", null);
    verifyUrl("jdbc:postgresql://?host=argsHost&user=argUser", "argsHost", "5432", "argUser", "argUser", null);
    verifyUrl("jdbc:postgresql://?host=argsHost&port=2222&user=argUser", "argsHost", "2222", "argUser", "argUser", null);
    verifyUrl("jdbc:postgresql://?host=argsHost&port=2222&dbname=argDb&user=argUser", "argsHost", "2222", "argDb", "argUser", null);
    verifyUrl("jdbc:postgresql://?host=argsHost&port=2222&dbname=argDb&user=argUser&password=argPass", "argsHost", "2222", "argDb", "argUser", "argPass");
    verifyUrl("jdbc:postgresql://?host=args%2Dhost&user=arg%23%24User&password=%21%22%23%24%25%26%27%28%29", "args-host", "5432", "arg#$User", "arg#$User", "!\"#$%&'()");
    verifyUrl("jdbc:postgresql://?currentSchema=OFF", "localhost", "5432", "osUser", "osUser", null, "currentSchema", "OFF");
    verifyUrl("jdbc:postgresql://?currentSchema", "localhost", "5432", "osUser", "osUser", null, "currentSchema", "");
    verifyUrl("jdbc:postgresql://?currentSchema=", "localhost", "5432", "osUser", "osUser", null, "currentSchema", "");
    verifyUrl("jdbc:postgresql://?currentSchema= ", "localhost", "5432", "osUser", "osUser", null, "currentSchema", " ");
    verifyUrl("jdbc:postgresql://?currentSchema=OFF&binaryTransfer=false", "localhost", "5432", "osUser", "osUser", null, "currentSchema", "OFF", "binaryTransfer", "false");
    verifyUrl("jdbc:postgresql://?currentSchema&binaryTransfer=false", "localhost", "5432", "osUser", "osUser", null, "currentSchema", "", "binaryTransfer", "false");
    verifyUrl("jdbc:postgresql://?currentSchema= &binaryTransfer=false", "localhost", "5432", "osUser", "osUser", null, "currentSchema", " ", "binaryTransfer", "false");
    failUrl("jdbc:postgresql://?database=urlDb", "Unsupported property name: [database]");
    failUrl("jdbc:postgresql://? currentSchema", "Unsupported property name: [ currentSchema]");
    failUrl("jdbc:postgresql://?currentSchema ", "Unsupported property name: [currentSchema ]");
    // errorMessage argument is null because error message depends on java version
    failUrl("jdbc:postgresql://?logger%1Level=OFF", null);
    failUrl("jdbc:postgresql://?currentSchema=%1", "url [%1] parsing failed [URLDecoder: Incomplete trailing escape (%) pattern]");
    failUrl("jdbc:postgresql://? ", "Unsupported property name: [ ]");
    failUrl("jdbc:postgresql:///? ", "Unsupported property name: [ ]");
    failUrl("jdbc:postgresql://?& ", "Unsupported property name: [ ]");
    failUrl("jdbc:postgresql://? &", "Unsupported property name: [ ]");
    failUrl("jdbc:postgresql://?& &", "Unsupported property name: [ ]");
    failUrl("jdbc:postgresql://?&& ", "Unsupported property name: [ ]");
    // args override
    verifyUrl("jdbc:postgresql://?user=argUser1&user=argUser2", "localhost", "5432", "argUser2", "argUser2", null);
    verifyUrl("jdbc:postgresql://?currentSchema=OFF&currentSchema=FINE", "localhost", "5432", "osUser", "osUser", null, "currentSchema", "FINE");
    verifyUrl("jdbc:postgresql://urlUser@?user=argUser1&user=argUser2", "localhost", "5432", "argUser2", "argUser2", null);
    // combined tests
    verifyUrl("jdbc:postgresql:// : @ : / ?loggerFile= ", " ", "5432", " ", " ", " ", "loggerFile", " ");
    verifyUrl("jdbc:postgresql://localhost/test", "localhost", "5432", "test", "osUser", null);
    verifyUrl("jdbc:postgresql://localhost,locahost2/test", "localhost,locahost2", "5432,5432", "test", "osUser", null);
    verifyUrl("jdbc:postgresql://localhost:5433,locahost2:5434/test", "localhost,locahost2", "5433,5434", "test", "osUser", null);
    verifyUrl("jdbc:postgresql://[::1]:5433,:5434,[::1]/test", "[::1],localhost,[::1]", "5433,5434,5432", "test", "osUser", null);
    verifyUrl("jdbc:postgresql://localhost/test?port=8888", "localhost", "8888", "test", "osUser", null);
    verifyUrl("jdbc:postgresql://localhost:5432/test", "localhost", "5432", "test", "osUser", null);
    verifyUrl("jdbc:postgresql://localhost:5432/test?dbname=test2", "localhost", "5432", "test2", "osUser", null);
    verifyUrl("jdbc:postgresql://127.0.0.1/anydbname", "127.0.0.1", "5432", "anydbname", "osUser", null);
    verifyUrl("jdbc:postgresql://127.0.0.1:5433/hidden", "127.0.0.1", "5433", "hidden", "osUser", null);
    verifyUrl("jdbc:postgresql://127.0.0.1:5433/hidden?port=7777", "127.0.0.1", "7777", "hidden", "osUser", null);
    verifyUrl("jdbc:postgresql://[::1]:5740/db", "[::1]", "5740", "db", "osUser", null);
    verifyUrl("jdbc:postgresql://[::1]:5740/my%20data%23base%251?loggerFile=C%3A%5Cdir%5Cfile.log", "[::1]", "5740", "my data#base%1", "osUser", null, "loggerFile", "C:\\dir\\file.log");
    // syntax not supported by libpq (because of @ inside username)
    verifyUrl("jdbc:postgresql://first.last@company.org@", "localhost", "5432", "first.last@company.org", "first.last@company.org", null);
    verifyUrl("jdbc:postgresql://first@last@company.org@", "localhost", "5432", "first@last@company.org", "first@last@company.org", null);
    verifyUrl("jdbc:postgresql://first@last@company.org@@", "localhost", "5432", "first@last@company.org@", "first@last@company.org@", null);
    verifyUrl("jdbc:postgresql://first@last@company.org:passw@rd@", "localhost", "5432", "first@last@company.org", "first@last@company.org", "passw@rd");
    verifyUrl("jdbc:postgresql://first@last@company.org:passw@rd@urlHost", "urlHost", "5432", "first@last@company.org", "first@last@company.org", "passw@rd");
    verifyUrl("jdbc:postgresql://first@last@company.org:passw@rd@urlHost/db@dot.org", "urlHost", "5432", "db@dot.org", "first@last@company.org", "passw@rd");
    verifyUrl("jdbc:postgresql://first@last@company.org:passw@rd@urlHost.org/db@dot.org?user=urlUser@url.org", "urlHost.org", "5432", "db@dot.org", "urlUser@url.org", "passw@rd");
    verifyUrl("jdbc:postgresql://first.last@company.org::passw@rd@/urlDb", "localhost", "5432", "urlDb", "first.last@company.org", ":passw@rd");
    verifyUrl("jdbc:postgresql://first.last@company.org::passw%3Frd@/urlDb", "localhost", "5432", "urlDb", "first.last@company.org", ":passw?rd");
  }

  @Test
  public void testCaseSet2() throws Exception {
    //
    // tests for service syntax
    //
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql://?service=driverTestService1", "test-host1", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://?service=driverTestService1&host=other-host", "other-host", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql:///?service=driverTestService1", "test-host1", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql:///?service=driverTestService1&port=3333&dbname=other-db", "test-host1", "3333", "other-db", "osUser", null);
      verifyUrl("jdbc:postgresql://localhost:5432/test?service=driverTestService1", "localhost", "5432", "test", "osUser", null);
      verifyUrl("jdbc:postgresql://localhost:5432/test?port=7777&dbname=other-db&service=driverTestService1", "localhost", "7777", "other-db", "osUser", null);
      verifyUrl("jdbc:postgresql://[::1]:5740/?service=driverTestService1", "[::1]", "5740", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://:5740/?service=driverTestService1", "test-host1", "5740", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://[::1]/?service=driverTestService1", "[::1]", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://:5789/?service=driverTestService2", "test-host1,[::1],test-host2", "5789,5789,5789", "testdb1", "osUser", null);
      //
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://urlUser@?service=driverTestService3", "serv-host3", "3555", "serv_db3", "urlUser", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://:urlPass@?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "urlPass", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://urlUser:urlPass@?service=driverTestService3", "serv-host3", "3555", "serv_db3", "urlUser", "urlPass", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://localhost1?service=driverTestService3", "localhost1", "3555", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://:/?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://localhost1/?service=driverTestService3", "localhost1", "3555", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://:5789/?service=driverTestService3", "serv-host3", "5789", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://localhost1:5789/?service=driverTestService3", "localhost1", "5789", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://localhost1,/?service=driverTestService3", "localhost1,localhost", "5432,5432", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://localhost1,:5888/?service=driverTestService3", "localhost1,localhost", "5432,5888", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://,/?service=driverTestService3", "localhost,localhost", "5432,5432", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      verifyUrl("jdbc:postgresql://,:5888/?service=driverTestService3", "localhost,localhost", "5432,5888", "serv_db3", "usr3@comp.org", "serv_pass3", "currentSchema", "OFF", "defaultRowFetchSize", "300");
      //
      verifyUrl("jdbc:postgresql://?service=driverTestService4", "serv1-host4,serv2-host4", "4555,4556", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      verifyUrl("jdbc:postgresql://:?service=driverTestService4", "serv1-host4,serv2-host4", "4555,4556", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      verifyUrl("jdbc:postgresql://,?service=driverTestService4", "localhost,localhost", "5432,5432", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      verifyUrl("jdbc:postgresql://,?service=driverTestService4", "localhost,localhost", "5432,5432", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      failUrl("jdbc:postgresql://localhost1?service=driverTestService4", "could not match [2] port numbers to [1] hosts");
      verifyUrl("jdbc:postgresql://localhost1:3333?service=driverTestService4", "localhost1", "3333", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      verifyUrl("jdbc:postgresql://:3333?service=driverTestService4", "serv1-host4,serv2-host4", "3333,3333", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      verifyUrl("jdbc:postgresql://localhost1,[::1]:3333?service=driverTestService4", "localhost1,[::1]", "5432,3333", "serv_db4", "usr4@comp.org", "serv_pass4", "currentSchema", "FINE", "defaultRowFetchSize", "400");
      // fail cases
      failUrl("jdbc:postgresql://?service=driverTestService2", "could not match [2] port numbers to [3] hosts");
      failUrl("jdbc:postgresql://localhost/?service=driverTestService2", "could not match [2] port numbers to [1] hosts");
      failUrl("jdbc:postgresql://?service=driverTestService5", "key 'service' is not allowed: line number [50], value [service=next-service]");
      failUrl("jdbc:postgresql://?service=driverTestService6", "Got invalid key: line number [53], value [invalid-key=value]");
      // space
      verifyUrl("jdbc:postgresql://?service= ", " spacehost", "556", " spacedb", " spaceuser", " spacepass", "currentSchema", " SPACE");
    });
    //
    // tests for service syntax (service name comes from property)
    //
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICE.getName(), "driverTestService1", PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql:", "test-host1", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://", "test-host1", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    //
    // tests for service syntax (service name comes from environment)
    //
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGSERVICE.getName(), "driverTestService1"),
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql://", "test-host1", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    //
    // tests for service syntax (service name comes from property and environment)
    //
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICE.getName(), "driverTestService1", PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile()),
        new EnvironmentVariables(PGEnvironment.PGSERVICE.getName(), "non-existing-service")
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql://", "test-host1", "5444", "testdb1", "osUser", null);
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    //
    // tests for service syntax (service name comes from property)
    //
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICE.getName(), " ", PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql://", " spacehost", "556", " spacedb", " spaceuser", " spacepass", "currentSchema", " SPACE");
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    //
    // tests for service syntax (service name comes from environment)
    //
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile()),
        new EnvironmentVariables(PGEnvironment.PGSERVICE.getName(), " ")
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql://", " spacehost", "556", " spacedb", " spaceuser", " spacepass", "currentSchema", " SPACE");
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    //
    // tests for service syntax (service name comes from property and environment)
    //
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICE.getName(), " ", PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile()),
        new EnvironmentVariables(PGEnvironment.PGSERVICE.getName(), "non-existing-service")
    ).execute(() -> {
      // correct cases
      verifyUrl("jdbc:postgresql://", " spacehost", "556", " spacedb", " spaceuser", " spacepass", "currentSchema", " SPACE");
      verifyUrl("jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
  }

  @Test
  public void testCaseSet3() throws Exception {
    // level 0: PREPARE
    Properties defaults = new Properties();
    PGProperty.USER.set(defaults, "default_user1");
    Properties props = new Properties(defaults);
    // level 0: global default wins
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "default_user1", "default_user1", null);
    // level 1 (service= ): PREPARE
    PGProperty.SERVICE.set(props, "driverTestService3");
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // level 1 (service= from properties): local default wins over level 0
      verifyUrlGeneral(props, "jdbc:postgresql://", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    props.clear();
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile()),
        new EnvironmentVariables(PGEnvironment.PGSERVICE.getName(), "driverTestService3")
    ).execute(() -> {
      // level 1 (service= from os env): local default wins over level 0
      verifyUrlGeneral(props, "jdbc:postgresql://", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile(),
            PGEnvironment.ORG_POSTGRESQL_PGSERVICE.getName(), "driverTestService3")
    ).execute(() -> {
      // level 1 (service= from java env): local default wins over level 0
      verifyUrlGeneral(props, "jdbc:postgresql://", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    // level 1 (common): PREPARE
    PGProperty.HOST.set(defaults, "default-host1");
    PGProperty.PORT.set(defaults, "3668");
    PGProperty.DBNAME.set(defaults, "default_db1");
    PGProperty.PASSWORD.set(defaults, "default_pass1");
    // level 1 (common): local default wins over level 0
    verifyUrlGeneral(props, "jdbc:postgresql://", "default-host1", "3668", "default_db1", "default_user1", "default_pass1");
    // level 2: PREPARE
    EnvironmentVariables environmentVariables = new EnvironmentVariables(
        PGEnvironment.PGHOST.getName(), "osenv-host1",
        PGEnvironment.PGPORT.getName(), "3669",
        PGEnvironment.PGDATABASE.getName(), "osenv_db1",
        PGEnvironment.PGUSER.getName(), "osenv_user1",
        PGEnvironment.PGPASSWORD.getName(), "osenv_pass1"
    );
    Resources.with(
        environmentVariables
    ).execute(() -> {
      // level 2: os environment wins over level 1
      verifyUrlGeneral(props, "jdbc:postgresql://", "osenv-host1", "3669", "osenv_db1", "osenv_user1", "osenv_pass1");
    });
    // level 3: PREPARE
    SystemProperties systemProperties = new SystemProperties(
        PGEnvironment.ORG_POSTGRESQL_PGHOST.getName(), "javaenv-host1",
        PGEnvironment.ORG_POSTGRESQL_PGPORT.getName(), "3670",
        PGEnvironment.ORG_POSTGRESQL_PGDATABASE.getName(), "javaenv_db1",
        PGEnvironment.ORG_POSTGRESQL_PGUSER.getName(), "javaenv_user1",
        PGEnvironment.ORG_POSTGRESQL_PGPASSWORD.getName(), "javaenv_pass1"
    );
    Resources.with(
        environmentVariables, systemProperties
    ).execute(() -> {
      // level 3: java environment wins over level 2
      verifyUrlGeneral(props, "jdbc:postgresql://", "javaenv-host1", "3670", "javaenv_db1", "javaenv_user1", "javaenv_pass1");
    });
    // level 4: PREPARE
    systemProperties.set(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile());
    Resources.with(
        environmentVariables, systemProperties
    ).execute(() -> {
      // level 4: service= wins over level 3
      verifyUrlGeneral(props, "jdbc:postgresql://?service=driverTestService3", "serv-host3", "3555", "serv_db3", "usr3@comp.org", "serv_pass3");
    });
    // level 5: PREPARE
    PGProperty.PORT.set(props, "3667");
    PGProperty.HOST.set(props, "props-host1");
    PGProperty.DBNAME.set(props, "props_db1");
    PGProperty.USER.set(props, "props_user1");
    PGProperty.PASSWORD.set(props, "props_pass1");
    Resources.with(
        environmentVariables, systemProperties
    ).execute(() -> {
      // level 5: property wins over level 4
      verifyUrlGeneral(props, "jdbc:postgresql://", "props-host1", "3667", "props_db1", "props_user1", "props_pass1");
    });
    Resources.with(
        environmentVariables, systemProperties
    ).execute(() -> {
      // level 6: url property wins over level 5
      verifyUrlGeneral(props, "jdbc:postgresql://url-host1", "url-host1", "3667", "props_db1", "props_user1", "props_pass1");
    });
    Resources.with(
        environmentVariables, systemProperties
    ).execute(() -> {
      // level 7: url argument wins over level 6
      verifyUrlGeneral(props, "jdbc:postgresql://url-host1?host=arg-host1", "arg-host1", "3667", "props_db1", "props_user1", "props_pass1");
    });
  }

  @Test
  public void testCaseSet4() throws Exception {
    Properties defaults = new Properties();
    PGProperty.USER.set(defaults, "osUser");
    Properties props = new Properties(defaults);
    //
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      // fail cases
      PGProperty.PORT.set(props, "3666,3666");
      failUrlGeneral(props, "jdbc:postgresql://?service=driverTestService3", "could not match [2] port numbers to [1] hosts");
      PGProperty.HOST.set(props, "host1,host2,host3");
      failUrlGeneral(props, "jdbc:postgresql://?service=driverTestService3", "could not match [2] port numbers to [3] hosts");
      PGProperty.PORT.set(props, "7889");
      verifyUrlGeneral(props, "jdbc:postgresql://?service=driverTestService3", "host1,host2,host3", "7889,7889,7889", "serv_db3", "usr3@comp.org", "serv_pass3", "defaultRowFetchSize", "300");
    });
  }

  @Test
  public void testCaseSet5() {
    Properties defaults = new Properties();
    PGProperty.USER.set(defaults, "osUser");
    Properties props = new Properties(defaults);
    // valid properties
    verifyUrlGeneral(props, "jdbc:postgresql:", "localhost", "5432", "osUser", "osUser", null);
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "osUser", "osUser", null);
    PGProperty.USER.set(props, "myUser");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myUser", "myUser", null);
    PGProperty.DBNAME.set(props, "myDatabase");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myDatabase", "myUser", null);
    PGProperty.PASSWORD.set(props, "myPassword");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myDatabase", "myUser", "myPassword");
    PGProperty.HOST.set(props, "myHost");
    verifyUrlGeneral(props, "jdbc:postgresql://", "myHost", "5432", "myDatabase", "myUser", "myPassword");
    PGProperty.PORT.set(props, "9876");
    verifyUrlGeneral(props, "jdbc:postgresql://", "myHost", "9876", "myDatabase", "myUser", "myPassword");
    // invalid properties
    props.setProperty("pghost", "myHost");
    failUrlGeneral(props, "jdbc:postgresql://", "Unsupported property name: [pghost]");
  }

  @Test
  public void testCaseSet6() throws Exception {
    URL pgpass = getClass().getResource(PGPASS_FILE_NAME);
    assertNotNull(pgpass);
    Properties props = new Properties();
    // passfile as URL Argument
    verifyUrlGeneral(props, "jdbc:postgresql://postgres@localhost:5432?passfile=" + URLCoder.encode(pgpass.getFile()), "localhost", "5432", "postgres", "postgres", "postgres1");
    // passfile as Environment variable
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName(), pgpass.getFile())
    ).execute(() -> {
      verifyUrlGeneral(props, "jdbc:postgresql://postgres@localhost:5432", "localhost", "5432", "postgres", "postgres", "postgres1");
    });
    // passfile as System Property
    Resources.with(
        new EnvironmentVariables(PGEnvironment.PGPASSFILE.getName(), pgpass.getFile())
    ).execute(() -> {
      verifyUrlGeneral(props, "jdbc:postgresql://postgres@localhost:5432", "localhost", "5432", "postgres", "postgres", "postgres1");
    });
  }

  @Test
  public void testCaseSet7() throws Exception {
    // 1. URL arguments (values after ? mark) - no way to specify PGDBNAME, PGHOST, PGPORT
    // no test cases

    // 2. URL values (values before ? mark) - does not support arguments PGDBNAME, PGHOST, PGPORT
    Properties defaults = new Properties();
    PGProperty.USER.set(defaults, "osUser");
    final Properties props = new Properties(defaults);
    verifyUrlGeneral(props, "jdbc:postgresql:///?PGHOST=myHost", "myHost", "5432", "osUser", "osUser", null);
    verifyUrlGeneral(props, "jdbc:postgresql:///?host=myHost", "myHost", "5432", "osUser", "osUser", null);
    verifyUrlGeneral(props, "jdbc:postgresql:///?PGDBNAME=myDbname", "localhost", "5432", "myDbname", "osUser", null);
    verifyUrlGeneral(props, "jdbc:postgresql:///?dbname=myDbname", "localhost", "5432", "myDbname", "osUser", null);
    verifyUrlGeneral(props, "jdbc:postgresql:///?PGPORT=4444", "localhost", "4444", "osUser", "osUser", null);
    verifyUrlGeneral(props, "jdbc:postgresql:///?port=4444", "localhost", "4444", "osUser", "osUser", null);
    failUrlGeneral(props, "jdbc:postgresql:///?invalid-key-1=value", "Unsupported property name: [invalid-key-1]");

    // 3. Properties given to DriverManager.getConnection() - supports arguments PGDBNAME, PGHOST, PGPORT
    // PG_DBNAME
    props.clear();
    PGProperty.PG_DBNAME.set(props, "myDbDeprecated");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myDbDeprecated", "osUser", null);
    PGProperty.DBNAME.set(props, "myDb");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myDb", "osUser", null);
    // PG_HOST
    props.clear();
    PGProperty.PG_HOST.set(props, "myHostDeprecated");
    verifyUrlGeneral(props, "jdbc:postgresql://", "myHostDeprecated", "5432", "osUser", "osUser", null);
    PGProperty.HOST.set(props, "myHost");
    verifyUrlGeneral(props, "jdbc:postgresql://", "myHost", "5432", "osUser", "osUser", null);
    // PG_PORT
    props.clear();
    PGProperty.PG_PORT.set(props, "54321");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "54321", "osUser", "osUser", null);
    PGProperty.PORT.set(props, "54322");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "54322", "osUser", "osUser", null);
    props.setProperty("invalid-key-2", "value");
    failUrlGeneral(props, "jdbc:postgresql://", "Unsupported property name: [invalid-key-2]");

    // 4. values provided by service (from resource .pg_service.conf) - does not support arguments PGDBNAME, PGHOST, PGPORT
    props.clear();
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    assertNotNull(urlFileProps);
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      failUrlGeneral(props, "jdbc:postgresql:///?service=myServiceDeprecated1", "Unsupported property name: [PGDBNAME]");
      failUrlGeneral(props, "jdbc:postgresql:///?service=myServiceDeprecated2", "Unsupported property name: [PGHOST]");
      failUrlGeneral(props, "jdbc:postgresql:///?service=myServiceDeprecated3", "Unsupported property name: [PGPORT]");
      failUrlGeneral(props, "jdbc:postgresql:///?service=driverTestService6", "Got invalid key: line number [53], value [invalid-key=value]");
    });

    // 5. values in Java System Properties - no way to specify PGDBNAME, PGHOST, PGPORT
    // no test cases

    // 6. values in Operating System environment - no way to specify PGDBNAME, PGHOST, PGPORT
    // no test cases

    // 7. values from driverconfig file(s) (org/postgresql/driverconfig.properties) - supports arguments PGDBNAME, PGHOST, PGPORT
    // PGDBNAME
    props.clear();
    defaults.clear();
    PGProperty.USER.set(defaults, "osUser");
    PGProperty.PG_DBNAME.set(defaults, "myDbDeprecated");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myDbDeprecated", "osUser", null);
    PGProperty.DBNAME.set(defaults, "myDb");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "5432", "myDb", "osUser", null);
    // PGHOST
    defaults.clear();
    PGProperty.USER.set(defaults, "osUser");
    PGProperty.PG_HOST.set(defaults, "myHostDeprecated");
    verifyUrlGeneral(props, "jdbc:postgresql://", "myHostDeprecated", "5432", "osUser", "osUser", null);
    PGProperty.HOST.set(defaults, "myHost");
    verifyUrlGeneral(props, "jdbc:postgresql://", "myHost", "5432", "osUser", "osUser", null);
    // PGPORT
    defaults.clear();
    PGProperty.USER.set(defaults, "osUser");
    PGProperty.PG_PORT.set(defaults, "34567");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "34567", "osUser", "osUser", null);
    PGProperty.PORT.set(defaults, "34568");
    verifyUrlGeneral(props, "jdbc:postgresql://", "localhost", "34568", "osUser", "osUser", null);
    defaults.setProperty("invalid-key-3", "value");
    failUrlGeneral(props, "jdbc:postgresql://", "Unsupported property name: [invalid-key-3]");

    // 8. global defaults (dbname, host, pgpass, port, user) - no way to specify PGDBNAME, PGHOST, PGPORT
    // no test cases
  }

  private void verifyUrl(String url, String host, String port, String db, String user, @Nullable String pass, String... args) {
    Properties defaults = new Properties();
    PGProperty.USER.set(defaults, "osUser");
    Properties props = new Properties(defaults);
    //
    verifyUrlGeneral(props, url, host, port, db, user, pass, args);
  }

  private void verifyUrlGeneral(Properties defaults, String url, String host, String port, String db, String user, @Nullable String pass, String... args) {
    JdbcUrlResolver resolver = new JdbcUrlResolver(url, defaults);
    Properties p = resolver.getResult();
    assertNotNull(p);
    assertEquals(host, PGProperty.HOST.getOrNull(p), url);
    assertEquals(port, PGProperty.PORT.getOrNull(p), url);
    assertEquals(db, PGProperty.DBNAME.getOrNull(p), url);
    assertEquals(user, PGProperty.USER.getOrNull(p), url);
    assertEquals(pass, PGProperty.PASSWORD.getOrNull(p), url);
    // args
    for (int i = 0; i < args.length; i++) {
      String key = args[i++];
      String value = args[i];
      assertEquals(value, p.getProperty(key), url);
    }
  }

  private void failUrl(String url, String errorMessage) {
    Properties defaults = new Properties();
    PGProperty.USER.set(defaults, "osUser");
    Properties props = new Properties(defaults);
    //
    failUrlGeneral(props, url, errorMessage);
  }

  private void failUrlGeneral(Properties defaults, String url, String errorMessage) {
    JdbcUrlResolver resolver = new JdbcUrlResolver(url, defaults);
    Properties p = resolver.getResult();
    assertNull(p);
    JdbcUrlResolverFatalException e = resolver.getFailException();
    assertNotNull(e);
    if (errorMessage != null) {
      assertEquals(errorMessage, e.getMessage());
    }
  }

}
