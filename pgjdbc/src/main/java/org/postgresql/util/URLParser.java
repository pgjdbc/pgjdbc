/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
public class URLParser {
  private static final Logger LOGGER = Logger.getLogger(URLParser.class.getName());

  // input
  private final String url;
  private final Properties defaults;

  // position options
  private enum SECTION {
    USER, PASSWORD, HOST, DATABASE, ARGUMENT
  }

  // work state
  @Nullable
  private SECTION currentSection;
  @Nullable
  private String urlTemp;

  // results
  @Nullable
  private String urlUser;
  @Nullable
  private String urlPass;
  @Nullable
  private String urlHosts;
  @Nullable
  private HostPort urlHostPort;
  @Nullable
  private String urlDatabase;
  @Nullable
  private String urlArguments;
  @Nullable
  private String urlService;
  @Nullable
  private Properties urlServiceProperties;

  // to support unit tests
  @Nullable
  private URLParserFatalException failException;

  // to support unit tests
  @Nullable URLParserFatalException getFailException() {
    return failException;
  }

  /**
   *
   * @param url jdbc url
   * @param defaults defaults
   */
  public URLParser(String url, @Nullable Properties defaults) {
    this.url = url;
    this.defaults = defaults == null ? new Properties() : defaults;
  }

  /**
   * Returns Properties
   * @return result of parsing URL
   */
  @Nullable
  public Properties getResult() {
    Properties result = new Properties();
    try {
      // split url into sections: user, password, hosts, database, arguments
      parse10UrlPrefix();
      parse20User();
      parse30Password();
      parse40Host();
      parse50Database();
      parse60Argument();
      // split host section (in case of multiple hosts)
      parse70HostPort();
      // result - priority 1: URL arguments
      result10Argument(result);
      // find service name if specified
      parse80Service();
      // load service properties (file .pg_service.conf) if requested
      parse81Service();
      // result - priority 2: URL values
      result20Url(result);
      // result - priority 3: Properties given as argument to DriverManager.getConnection()
      // argument "defaults" EXCLUDING defaults (forEach() returns all entries EXCEPT defaults)
      result30DefaultExcluding(result);
      // result - priority 4: Properties by "service"
      result40Service(result);
      // result - priority 5: PGEnvironment values
      result50PGEnvironmentJava(result);
      // result - priority 6: PGEnvironment values
      result60PGEnvironmentOs(result);
      // result - priority 7: Properties given as argument to DriverManager.getConnection()
      // argument "defaults" INCLUDING defaults (stringPropertyNames() returns all entries INCLUDING defaults)
      result70DefaultIncluding(result);
      // result - priority 8: PGProperty defaults for PGHOST, PGPORT, PGDBNAME
      result80PGPropertyDefault(result);
      // fill in blanks with defaults
      result85AdjustHostPort(result);
      // adjust hosts and ports to match (or fail)
      result86AdjustHostPort(result);
      // verify ports numbers
      verify10Port(result);
      // find passwords (load .pgpass)
      result90Pgpass(result);
    } catch (URLParserFatalException e) {
      failException = e;
      LOGGER.warning(e.getMessage());
      return null;
    }
    return result;
  }

  private void parse10UrlPrefix() throws URLParserFatalException {
    // verify prefix
    if (url.startsWith("jdbc:postgresql://")) {
      currentSection = SECTION.USER;
      urlTemp = url.substring("jdbc:postgresql://".length());
      return;
    } else if (url.startsWith("jdbc:postgresql:")) {
      // not supported syntax by libpq.
      // if "//" is missing then everything after ":" is taken as database name
      currentSection = SECTION.DATABASE;
      saveSection(url.substring("jdbc:postgresql:".length()));
      return;
    }
    throw new URLParserFatalException("url must start with 'jdbc:postgresql:[//]'");
  }

  private void parse20User() {
    if (urlTemp == null) {
      return;
    }
    // find end of scan. "jdbc:postgresql://user[:password]@/?" section can not go beyond "/" or "?", whichever comes first
    int indexTo1 = urlTemp.indexOf('/');
    int indexTo2 = urlTemp.indexOf('?');
    if (indexTo1 <= 0) {
      indexTo1 = urlTemp.length();
    }
    if (indexTo2 <= 0) {
      indexTo2 = urlTemp.length();
    }
    int indexTo = Math.min(indexTo1, indexTo2);
    // find last "@" in region (NB! libpq is searching for first "@")
    // Nowadays, email address is used widely as username.
    // the following syntax should be valid: "jdbc:postgresql://first.last@company.org@server"
    int charPos = urlTemp.substring(0, indexTo).lastIndexOf('@');
    if (charPos == 0) {
      urlTemp = urlTemp.substring(charPos + 1);
    } else if (charPos > 0) {
      saveSection(urlTemp.substring(0, charPos));
      urlTemp = urlTemp.substring(charPos + 1);
    }
    currentSection = SECTION.HOST;
  }

  private void parse30Password() {
    if (urlUser != null) {
      // extract password if exist
      int pos = urlUser.indexOf(':');
      if (pos >= 0) {
        urlPass = urlUser.substring(pos + 1);
        urlUser = urlUser.substring(0, pos);
        // clear zero length
        if (urlPass.isEmpty()) {
          urlPass = null;
        }
      }
      // clear zero length
      if (urlUser.isEmpty()) {
        urlUser = null;
      }
    }
  }

  private void parse40Host() {
    if (urlTemp == null) {
      return;
    }
    // find end of scan
    int charPos = urlTemp.indexOf('/');
    if (charPos == 0) {
      urlTemp = urlTemp.substring(charPos + 1);
      currentSection = SECTION.DATABASE;
    } else if (charPos > 0) {
      saveSection(urlTemp.substring(0, charPos));
      urlTemp = urlTemp.substring(charPos + 1);
      currentSection = SECTION.DATABASE;
    }
  }

  private void parse50Database() {
    if (urlTemp == null) {
      return;
    }
    // find end of scan
    int charPos = urlTemp.indexOf('?');
    if (charPos == 0) {
      urlTemp = urlTemp.substring(charPos + 1);
      currentSection = SECTION.ARGUMENT;
    } else if (charPos > 0) {
      saveSection(urlTemp.substring(0, charPos));
      urlTemp = urlTemp.substring(charPos + 1);
      currentSection = SECTION.ARGUMENT;
    }
  }

  private void parse60Argument() {
    if (urlTemp != null) {
      // the rest are arguments
      if (urlTemp.length() > 0) {
        saveSection(urlTemp);
      }
      urlTemp = null;
    }
    currentSection = null;
  }

  private void parse70HostPort() throws URLParserFatalException {
    if (urlHosts == null) {
      return;
    }
    final List<String> hostList = new ArrayList<>();
    final List<String> portList = new ArrayList<>();
    // split multiple endpoints (host and port pairs)
    for (String hostPort : urlHosts.split(",", -1)) {
      // host:port separator is ":" (colon)
      // ipv6 address contains ":" (colons) and is surrounded by "[]"
      // looking for last ":" after "]" (if exist)
      int bracketPos = hostPort.lastIndexOf("]");
      int colonPos = hostPort.lastIndexOf(":");
      String host;
      String port = "";
      if (bracketPos < colonPos) {
        host = hostPort.substring(0, colonPos);
        port = hostPort.substring(colonPos + 1);
      } else {
        host = hostPort;
      }
      //
      hostList.add(urlDecode(host));
      portList.add(urlDecode(port.trim()));
    }
    // save for later use
    urlHostPort = new HostPort(hostList, portList);
  }

  private void result10Argument(Properties result) throws URLParserFatalException {
    if (urlArguments == null) {
      return;
    }
    // add url arguments to result
    for (String token : urlArguments.split("&", -1)) {
      if (token.isEmpty()) {
        continue;
      }
      // split token
      String key = token;
      String value = "";
      int pos = token.indexOf('=');
      if (pos >= 0) {
        key = token.substring(0, pos);
        value = token.substring(pos + 1);
      }
      // apply url decode
      key = urlDecode(key);
      value = urlDecode(value);
      // detect special key "service"
      if (PGProperty.SERVICE.getName().equals(key)) {
        urlService = value;
        continue;
      }
      // translate key
      key = PGPropertyUtil.translatePGServiceToPGProperty(key);
      // is it valid key?
      PGProperty pgProperty = PGProperty.forName(key);
      if (pgProperty == null) {
        throw new URLParserFatalException(String.format("Unsupported url argument: [%s]", key));
      }
      // accept
      pgProperty.set(result, value);
    }
  }

  private void parse80Service() throws URLParserFatalException {
    // 1st priority: service= as url argument
    if (urlService != null) {
      LOGGER.log(Level.FINE, "Got service name [{0}] from url", new Object[]{urlService});
      return;
    }
    // 2nd priority: properties property
    String propertyName = PGProperty.SERVICE.getName();
    urlService = defaults.getProperty(propertyName);
    if (urlService != null) {
      LOGGER.log(Level.FINE, "Got service name [{0}] from Properties property [{1}]", new Object[]{urlService, propertyName});
      return;
    }
    // 3rd priority: property
    PGEnvironment pgEnvironment = PGEnvironment.ORG_POSTGRESQL_PGSERVICE;
    urlService = pgEnvironment.readStringValue();
    if (urlService != null) {
      LOGGER.log(Level.FINE, "Got service name [{0}] from property [{1}]", new Object[]{urlService, pgEnvironment.getName()});
      return;
    }
    // 4th priority: environment
    pgEnvironment = PGEnvironment.PGSERVICE;
    urlService = pgEnvironment.readStringValue();
    if (urlService != null) {
      LOGGER.log(Level.FINE, "Got service name [{0}] from environment variable [{1}]", new Object[]{urlService, pgEnvironment.getName()});
      return;
    }
    LOGGER.log(Level.FINE, "service name not identified");
  }

  private void parse81Service() throws URLParserFatalException {
    if (urlService != null) {
      // read service properties
      urlServiceProperties = PGPropertyServiceParser.getServiceProperties(urlService);
      if (urlServiceProperties == null) {
        throw new URLParserFatalException(String.format("Definition of service [%s] not found", urlService));
      }
    }
  }

  private void result20Url(Properties result) throws URLParserFatalException {
    // add url properties if missing (putIfAbsent())
    if (urlDatabase != null) {
      urlDatabase = urlDecode(urlDatabase);
      PGProperty.PG_DBNAME.putIfAbsent(result, urlDatabase);
    }
    if (urlUser != null) {
      urlUser = urlDecode(urlUser);
      PGProperty.USER.putIfAbsent(result, urlUser);
    }
    if (urlPass != null) {
      urlPass = urlDecode(urlPass);
      PGProperty.PASSWORD.putIfAbsent(result, urlPass);
    }
    // merge url hosts/ports
    if (urlHostPort != null) {
      urlHostPort.mergeInto(result);
    }
  }

  private void result30DefaultExcluding(Properties result) {
    // add default properties if missing (NB! "defaults.entrySet()" returns all entries EXCEPT defaults)
    Map<String, String> map = new TreeMap<>();
    for (Map.Entry<Object, Object> entry : defaults.entrySet()) {
      String key = String.valueOf(entry.getKey());
      String value = String.valueOf(entry.getValue());
      map.put(key, value);
    }
    applySourceToResultIfAbsent(result, map);
  }

  private void result40Service(Properties result) {
    // add service properties if missing (putIfAbsent())
    if (urlServiceProperties == null) {
      return;
    }
    applySourceToResultIfAbsent(result, urlServiceProperties);
  }

  private void result50PGEnvironmentJava(Properties result) {
    PGEnvironment [] javaEnvs = {
        PGEnvironment.ORG_POSTGRESQL_PGHOST,
        PGEnvironment.ORG_POSTGRESQL_PGPORT,
        PGEnvironment.ORG_POSTGRESQL_PGDATABASE,
        PGEnvironment.ORG_POSTGRESQL_PGUSER,
        PGEnvironment.ORG_POSTGRESQL_PGPASSWORD
    };
    Properties props = new Properties();
    Arrays.stream(javaEnvs)
        .map(s -> new AbstractMap.SimpleEntry<>(s.getName(), s.readStringValue()))
        .filter(s -> s.getValue() != null)
        .forEach(s -> props.setProperty(PGEnvironmentUtil.translateToPGProperty(s.getKey()), s.getValue()));
    applySourceToResultIfAbsent(result, props);
  }

  private void result60PGEnvironmentOs(Properties result) {
    PGEnvironment [] osEnvs = {
        PGEnvironment.PGHOST,
        PGEnvironment.PGPORT,
        PGEnvironment.PGDATABASE,
        PGEnvironment.PGUSER,
        PGEnvironment.PGPASSWORD
    };
    Properties props = new Properties();
    Arrays.stream(osEnvs)
        .map(s -> new AbstractMap.SimpleEntry<>(s.getName(), s.readStringValue()))
        .filter(s -> s.getValue() != null)
        .forEach(s -> props.setProperty(PGEnvironmentUtil.translateToPGProperty(s.getKey()), s.getValue()));
    applySourceToResultIfAbsent(result, props);
  }

  private void result70DefaultIncluding(Properties result) {
    // add default properties if missing (NB! "defaults.stringPropertyNames()" returns all entries INCLUDING defaults)
    applySourceToResultIfAbsent(result, defaults);
  }

  private void result80PGPropertyDefault(Properties result) {
    // add PGProperty properties if missing
    HostPort hostPort = new HostPort(PGProperty.PG_HOST.getDefaultValue(), PGProperty.PG_PORT.getDefaultValue());
    hostPort.mergeInto(result);
    // The default name of the database is the same as that of the user
    if (PGProperty.USER.get(result) != null) {
      result.putIfAbsent(PGProperty.PG_DBNAME.getName(), castNonNull(PGProperty.USER.get(result)));
    }
  }

  private void result85AdjustHostPort(Properties result) throws URLParserFatalException {
    String hosts = result.getProperty(PGProperty.PG_HOST.getName());
    String ports = result.getProperty(PGProperty.PG_PORT.getName());
    //
    String[] hostArray = hosts == null ? new String[] {""} : hosts.split(",", -1);
    String[] portArray = ports == null ? new String[] {""} : ports.split(",", -1);
    // if any host/port value is still empty string then fill them using default
    String hostsNew = Arrays.stream(hostArray).map(s -> s.isEmpty() ? PGProperty.PG_HOST.getDefaultValue() : s).collect(Collectors.joining(","));
    String portsNew = Arrays.stream(portArray).map(s -> s.isEmpty() ? PGProperty.PG_PORT.getDefaultValue() : s).collect(Collectors.joining(","));
    //
    if (!hostsNew.equals(hosts)) {
      result.put(PGProperty.PG_HOST.getName(), hostsNew);
    }
    if (!portsNew.equals(ports)) {
      result.put(PGProperty.PG_PORT.getName(), portsNew);
    }
  }

  private void result86AdjustHostPort(Properties result) throws URLParserFatalException {
    String hosts = result.getProperty(PGProperty.PG_HOST.getName());
    String ports = result.getProperty(PGProperty.PG_PORT.getName());
    int hostCount = hosts.split(",", -1).length;
    int portCount = ports.split(",", -1).length;
    // if there are many hosts and one port then apply same port to all hosts
    if (hostCount > 1 && portCount == 1) {
      String newPorts = String.join(",", Collections.nCopies(hostCount, ports));
      result.put(PGProperty.PG_PORT.getName(), newPorts);
    } else if (hostCount != portCount) {
      // if host count and port count does not match then throw
      throw new URLParserFatalException(String.format("could not match [%s] port numbers to [%s] hosts", portCount, hostCount));
    }
    // remove space around port values
    String portString = result.getProperty(PGProperty.PG_PORT.getName());
    String portStringNew = portString.replaceAll(" ", "");
    if (!portString.equals(portStringNew)) {
      result.put(PGProperty.PG_PORT.getName(), portStringNew);
    }
  }

  private void verify10Port(Properties result) throws URLParserFatalException {
    String[] ports = result.getProperty(PGProperty.PG_PORT.getName()).split(",", -1);
    // verify that port values are numbers in a given range
    for (String port : ports) {
      try {
        int intPort = Integer.parseInt(port);
        if (intPort < 1 || intPort > 65535) {
          throw new URLParserFatalException(String.format("invalid port number: [%s]", port));
        }

      } catch (NumberFormatException e) {
        throw new URLParserFatalException(String.format("invalid integer value [%s] for connection option 'port'", port));
      }
    }
  }

  private void result90Pgpass(Properties result) throws URLParserFatalException {
    // look for password in .pgpass if password is missing
    if (PGProperty.PASSWORD.get(result) == null) {
      String password = PGPropertyPasswordParser.getPassword(
          PGProperty.PG_HOST.get(result), PGProperty.PG_PORT.get(result), PGProperty.PG_DBNAME.get(result), PGProperty.USER.get(result)
      );
      if (password != null && !password.isEmpty()) {
        PGProperty.PASSWORD.set(result, password);
      }
    }
  }

  private void applySourceToResultIfAbsent(Properties result, Map<String, String> source) {
    Properties props = new Properties();
    // convert Map to Properties
    for (Map.Entry<String, String> entry : source.entrySet()) {
      props.setProperty(entry.getKey(), entry.getValue());
    }
    applySourceToResultIfAbsent(result, props);
  }

  private void applySourceToResultIfAbsent(Properties result, Properties source) {
    String hosts = null;
    String ports = null;
    for (String key : source.stringPropertyNames()) {
      String value = source.getProperty(key);
      // collect host/port separately
      if (PGProperty.PG_HOST.getName().equals(key)) {
        hosts = value;
      } else if (PGProperty.PG_PORT.getName().equals(key)) {
        ports = value;
      } else {
        // apply if missing
        result.putIfAbsent(key, value);
      }
    }
    // merge host/port
    HostPort hostPort = new HostPort(hosts, ports);
    hostPort.mergeInto(result);
  }

  private void saveSection(String value) {
    if (currentSection == null) {
      throw new RuntimeException(String.format("bug in code, unhandled currentSection [%s]", currentSection));
    }
    switch (currentSection) {
      case USER:
        urlUser = value;
        break;
      case HOST:
        urlHosts = value;
        break;
      case DATABASE:
        urlDatabase = value;
        break;
      case ARGUMENT:
        urlArguments = value;
        break;
      default:
        throw new RuntimeException(String.format("bug in code, unhandled currentSection [%s]", currentSection.name()));
    }
  }

  // wrapper for url decode, converts to URLParserFatalException() on failure
  private static String urlDecode(String url) throws URLParserFatalException {
    try {
      return URLCoder.decode(url);
    } catch (IllegalArgumentException e) {
      throw new URLParserFatalException(String.format("url [%s] parsing failed [%s]", url, e.getMessage()));
    }
  }

  // exception for failure handling
  static class URLParserFatalException extends Exception {
    private URLParserFatalException(String message) {
      super(message);
    }
  }

  // to handle host/port non-trivial override behaviour
  static class HostPort {

    private final List<String> hostList = new ArrayList<>();
    private final List<String> portList = new ArrayList<>();

    HostPort(List<String> hostList, List<String> portList) {
      this.hostList.addAll(hostList);
      this.portList.addAll(portList);
    }

    HostPort(@Nullable String hosts, @Nullable String ports) {
      if (hosts != null) {
        hostList.addAll(Arrays.asList(hosts.split(",", -1)));
      }
      if (ports != null) {
        portList.addAll(Arrays.asList(ports.split(",", -1)));
      }
    }

    HostPort(@Nullable Object hosts, @Nullable Object ports) {
      this((String)hosts, (String)ports);
    }

    void mergeInto(Properties result) {
      // get current values
      String hosts = result.getProperty(PGProperty.PG_HOST.getName());
      String ports = result.getProperty(PGProperty.PG_PORT.getName());
      // get count
      int hostCount = 0;
      if (hosts != null) {
        hostCount = hosts.split(",", -1).length;
      }
      int portCount = 0;
      if (ports != null) {
        portCount = ports.split(",", -1).length;
      }
      // if any is higher than one then no merge
      if (hostCount > 1 || portCount > 1) {
        return;
      }
      //
      if ((hosts == null || hosts.isEmpty()) && hostList.size() > 0) {
        result.put(PGProperty.PG_HOST.getName(), String.join(",", hostList));
      }
      if ((ports == null || ports.isEmpty()) && portList.size() > 0) {
        result.put(PGProperty.PG_PORT.getName(), String.join(",", portList));
      }
    }
  }
}
