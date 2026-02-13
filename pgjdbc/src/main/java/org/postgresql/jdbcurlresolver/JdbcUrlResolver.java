/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import static org.postgresql.jdbcurlresolver.JdbcUrlParser.saveProperty;
import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.util.OSUtil;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * parser for jdbc URL.
 * https://www.postgresql.org/docs/current/libpq-connect.html#LIBPQ-CONNSTRING
 */
public class JdbcUrlResolver {
  private static final Logger LOGGER = Logger.getLogger(JdbcUrlResolver.class.getName());

  // input
  private final String url;
  private final Properties defaults;

  // to support unit tests
  private @Nullable JdbcUrlResolverFatalException failException;

  // to support unit tests
  @Nullable JdbcUrlResolverFatalException getFailException() {
    return failException;
  }

  /**
   * @param url      jdbc url
   * @param defaults defaults
   */
  public JdbcUrlResolver(String url, @Nullable Properties defaults) {
    this.url = url;
    this.defaults = defaults == null ? new Properties() : defaults;
  }

  /**
   * Returns Properties
   *
   * @return result of parsing URL
   */
  public @Nullable Properties getResult() {
    Properties result = new Properties();
    try {
      // override hierarchy
      Properties p8GlobalDefaults = new Properties(); // global defaults
      Properties p7DriverconfigProperties = new Properties(p8GlobalDefaults); // org/postgresql/driverconfig.properties by classloader
      Properties p6OsEnvironment = new Properties(p7DriverconfigProperties); // os env
      Properties p5JavaEnvironment = new Properties(p6OsEnvironment); // java env
      Properties p4ServiceResource = new Properties(p5JavaEnvironment); // service
      Properties p3GetConnetionProperties = new Properties(p4ServiceResource); // Properties given to getConnection()
      Properties p2UrlValues = new Properties(p3GetConnetionProperties); // URL values
      Properties p1UrlArguments = new Properties(p2UrlValues); // URL arguments
      Properties p0CalculatedOverrides = new Properties(p1UrlArguments); // Calculated overrides
      // priority 1: URL arguments
      // priority 2: URL values
      JdbcUrlParser parser = new JdbcUrlParser(url);
      parser.parse(p1UrlArguments, p2UrlValues);
      // priority 3: Properties given as argument to DriverManager.getConnection()
      // argument "defaults" EXCLUDING defaults
      parse3GetConnectionProperties(p3GetConnetionProperties);
      // priority 5: Java PGEnvironment values
      parse5JavaEnvironment(p5JavaEnvironment);
      // priority 6: OS PGEnvironment values
      parse6OsEnvironment(p6OsEnvironment);
      // priority 7: default values of Properties given as argument to DriverManager.getConnection()
      //             source: org/postgresql/driverconfig.properties by classloader
      // argument "defaults" ONLY defaults
      parse7DriverconfigProperties(p7DriverconfigProperties);
      // load service properties (file .pg_service.conf) if requested
      parse4ServiceResource(p4ServiceResource, p0CalculatedOverrides);
      // priority 8: PGProperty defaults for PGHOST, PGPORT, user, PGDBNAME
      parse8GlobalDefaults(p8GlobalDefaults, p0CalculatedOverrides);
      // post-processing: fill in blanks with defaults
      adjust91HostPort(p0CalculatedOverrides);
      // post-processing: adjust hosts and ports to match (or fail)
      adjust92HostPort(p0CalculatedOverrides);
      // post-processing: adjust ports
      adjust93Port(p0CalculatedOverrides);
      // verify: check that port numbers are in correct range
      verify94Port(p0CalculatedOverrides);
      // post-processing: find passwords (load .pgpass)
      parse9Pgpass(p0CalculatedOverrides);
      // copy result
      copyProperties(p0CalculatedOverrides, result);
      // debug log
      if (LOGGER.isLoggable(Level.FINEST)) {
        dumpStructures(result, p8GlobalDefaults, p7DriverconfigProperties, p6OsEnvironment, p5JavaEnvironment, p4ServiceResource, p3GetConnetionProperties, p2UrlValues, p1UrlArguments, p0CalculatedOverrides);
      }
    } catch (JdbcUrlResolverFatalException e) {
      failException = e;
      LOGGER.warning(e.getMessage());
      return null;
    }
    return result;
  }

  private void parse3GetConnectionProperties(Properties result) throws JdbcUrlResolverFatalException {
    // add default properties if missing (NB! "defaults.keySet()" returns all entries EXCEPT
    // defaults)
    for (Object oKey : defaults.keySet()) {
      String key = String.valueOf(oKey);
      String value = defaults.getProperty(key);
      saveProperty(result, key, value);
    }
  }

  private static void parse5JavaEnvironment(Properties result) {
    // scan & translate map
    Map<PGEnvironment, PGProperty> map = new TreeMap<>();
    map.put(PGEnvironment.ORG_POSTGRESQL_PGDATABASE, PGProperty.DBNAME);
    map.put(PGEnvironment.ORG_POSTGRESQL_PGHOST, PGProperty.HOST);
    map.put(PGEnvironment.ORG_POSTGRESQL_PGPASSFILE, PGProperty.PASSFILE);
    map.put(PGEnvironment.ORG_POSTGRESQL_PGPASSWORD, PGProperty.PASSWORD);
    map.put(PGEnvironment.ORG_POSTGRESQL_PGPORT, PGProperty.PORT);
    map.put(PGEnvironment.ORG_POSTGRESQL_PGSERVICE, PGProperty.SERVICE);
    map.put(PGEnvironment.ORG_POSTGRESQL_PGUSER, PGProperty.USER);
    //
    parseEnvironment(map, result);
  }

  private static void parse6OsEnvironment(Properties result) {
    // scan & translate map
    Map<PGEnvironment, PGProperty> map = new TreeMap<>();
    map.put(PGEnvironment.PGDATABASE, PGProperty.DBNAME);
    map.put(PGEnvironment.PGHOST, PGProperty.HOST);
    map.put(PGEnvironment.PGPASSFILE, PGProperty.PASSFILE);
    map.put(PGEnvironment.PGPASSWORD, PGProperty.PASSWORD);
    map.put(PGEnvironment.PGPORT, PGProperty.PORT);
    map.put(PGEnvironment.PGSERVICE, PGProperty.SERVICE);
    map.put(PGEnvironment.PGUSER, PGProperty.USER);
    //
    parseEnvironment(map, result);
  }

  private static void parseEnvironment(Map<PGEnvironment, PGProperty> scanMap, Properties result) {
    //
    for (Map.Entry<PGEnvironment, PGProperty> entry : scanMap.entrySet()) {
      PGEnvironment environment = entry.getKey();
      String value = environment.readStringValue();
      if (value != null) {
        PGProperty property = entry.getValue();
        property.set(result, value);
      }
    }
  }

  private void parse7DriverconfigProperties(Properties result) throws JdbcUrlResolverFatalException {
    // clone and clear. goal is to get only defaults
    Properties clone = (Properties) defaults.clone();
    clone.clear();
    for (String key : clone.stringPropertyNames()) {
      String value = clone.getProperty(key);
      saveProperty(result, key, value);
    }
  }

  private static void parse4ServiceResource(Properties p4, Properties p0) throws JdbcUrlResolverFatalException {
    String service = PGProperty.SERVICE.getOrNull(p0);
    if (service != null) {
      // read service properties
      Properties serviceProperties = PgServiceConfParser.getServiceProperties(service);
      // copy
      copyProperties(serviceProperties, p4);
    }
  }

  private static void parse8GlobalDefaults(Properties p8, Properties p0) {
    // add global defaults
    PGProperty.HOST.set(p8, PGProperty.HOST.getDefaultValue());
    PGProperty.PORT.set(p8, PGProperty.PORT.getDefaultValue());
    PGProperty.USER.set(p8, PGProperty.USER.getDefaultValue());
    PGProperty.PASSFILE.set(p8, OSUtil.getDefaultPgPassFilename());
    // The default name of the database is the same as that of the user
    if (PGProperty.USER.getOrNull(p0) != null) {
      PGProperty.DBNAME.set(p8, castNonNull(PGProperty.USER.getOrNull(p0)));
    }
  }

  // Goal: replace empty values with real default values
  // Examples:
  //   jdbc:postgresql://,/            -> localhost:5432,localhost:5432
  //   jdbc:postgresql://:22,:33/      -> localhost:22,localhost:33
  //   jdbc:postgresql://:22,host2:33/ -> localhost:22,host2:33
  //   jdbc:postgresql://host1,host2/  -> host1:5432,host2:5432
  private static void adjust91HostPort(Properties result) {
    String hosts = PGProperty.HOST.getOrNull(result);
    String ports = PGProperty.PORT.getOrNull(result);
    //
    String[] hostArray = hosts == null ? new String[]{""} : hosts.split(",", -1);
    String[] portArray = ports == null ? new String[]{""} : ports.split(",", -1);
    // if any host/port value is still empty string then fill them using default
    String hostsNew = Arrays.stream(hostArray).map(s -> s.isEmpty() ? castNonNull(PGProperty.HOST.getDefaultValue()) : s).collect(Collectors.joining(","));
    String portsNew = Arrays.stream(portArray).map(s -> s.isEmpty() ? castNonNull(PGProperty.PORT.getDefaultValue()) : s).collect(Collectors.joining(","));
    //
    if (!hostsNew.equals(hosts)) {
      PGProperty.HOST.set(result, hostsNew);
    }
    if (!portsNew.equals(ports)) {
      PGProperty.PORT.set(result, portsNew);
    }
  }

  // Goal: to support multiple hosts and one port option
  // Examples:
  //   jdbc:postgresql://host1,host2,host3?port=2222  -> host1:2222,host2:2222,host3:2222
  private static void adjust92HostPort(Properties result) throws JdbcUrlResolverFatalException {
    String hosts = PGProperty.HOST.getOrNull(result);
    String ports = PGProperty.PORT.getOrNull(result);
    // to fix checker error
    if (hosts == null || ports == null) {
      throw new RuntimeException(String.format("bug in code, hosts [%s] ports [%s]", hosts, ports));
    }
    int hostCount = hosts.split(",", -1).length;
    int portCount = ports.split(",", -1).length;
    // if there are many hosts and one port then apply same port to all hosts
    if (hostCount > 1 && portCount == 1) {
      String newPorts = String.join(",", Collections.nCopies(hostCount, ports));
      PGProperty.PORT.set(result, newPorts);
    } else if (hostCount != portCount) {
      // if host count and port count does not match then throw
      throw new JdbcUrlResolverFatalException(String.format("could not match [%s] port numbers to [%s] hosts", portCount, hostCount));
    }
  }

  // Goal: remove spaces around port numbers
  // Examples:
  //   jdbc:postgresql://host1:  2222  ,host2:  3333  /  -> host1:2222,host2:3333
  private static void adjust93Port(Properties result) {
    // remove space around port values
    String portString = PGProperty.PORT.getOrNull(result);
    // to fix checker error
    if (portString == null) {
      throw new RuntimeException("bug in code, portString is null");
    }
    String portStringNew = portString.trim();
    if (!portString.equals(portStringNew)) {
      PGProperty.PORT.set(result, portStringNew);
    }
  }

  private static void verify94Port(Properties result) throws JdbcUrlResolverFatalException {
    String portString = PGProperty.PORT.getOrNull(result);
    // to fix checker error
    if (portString == null) {
      throw new RuntimeException("bug in code, portString is null");
    }
    String[] ports = portString.split(",", -1);
    // verify that port values are numbers in a given range
    for (String port : ports) {
      try {
        int intPort = Integer.parseInt(port);
        if (intPort < 1 || intPort > 65535) {
          throw new JdbcUrlResolverFatalException(String.format("invalid port number: [%s]", port));
        }

      } catch (NumberFormatException e) {
        throw new JdbcUrlResolverFatalException(String.format("invalid integer value [%s] for connection option 'port'", port));
      }
    }
  }

  private static void parse9Pgpass(Properties p0) {
    String pgpassFile = PGProperty.PASSFILE.getOrNull(p0);
    // to fix checker error
    if (pgpassFile == null) {
      throw new RuntimeException("bug in code, pgpassFile is null");
    }
    // look for password in .pgpass if password is missing
    if (PGProperty.PASSWORD.getOrNull(p0) == null) {
      String password = PgPassParser.getPassword(
          pgpassFile, PGProperty.HOST.getOrNull(p0), PGProperty.PORT.getOrNull(p0), PGProperty.DBNAME.getOrNull(p0), PGProperty.USER.getOrNull(p0)
      );
      if (password != null && !password.isEmpty()) {
        PGProperty.PASSWORD.set(p0, password);
      }
    }
  }

  private static void dumpStructures(Properties result, Properties p8, Properties p7, Properties p6, Properties p5, Properties p4, Properties p3, Properties p2, Properties p1, Properties p0) {
    dumpStructure(result, "Final result");
    dumpStructure(p0, "Level p0");
    dumpStructure(p1, "Level p1");
    dumpStructure(p2, "Level p2");
    dumpStructure(p3, "Level p3");
    dumpStructure(p4, "Level p4");
    dumpStructure(p5, "Level p5");
    dumpStructure(p6, "Level p6");
    dumpStructure(p7, "Level p7");
    dumpStructure(p8, "Level p8");
  }

  private static void dumpStructure(Properties props, String message) {
    LOGGER.log(Level.FINE, "--- {0} ---", new String[]{message});
    for (Object oKey : props.keySet()) {
      String key = String.valueOf(oKey);
      String value = props.getProperty(key);
      LOGGER.log(Level.FINE, "[{0}={1}]", new String[]{key, value});
    }
  }

  private static void copyProperties(Properties source, Properties target) throws JdbcUrlResolverFatalException {
    for (String key : source.stringPropertyNames()) {
      String value = source.getProperty(key);
      if (value != null) {
        JdbcUrlParser.saveProperty(target, key, value, true);
      }
    }
  }

}
