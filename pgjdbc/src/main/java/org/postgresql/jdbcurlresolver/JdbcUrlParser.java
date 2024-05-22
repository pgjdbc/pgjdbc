/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import org.postgresql.PGProperty;
import org.postgresql.util.URLCoder;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

class JdbcUrlParser {

  // position options
  private enum SECTION {
    USER, HOST, DATABASE, ARGUMENT
  }

  // input
  private final String url;

  // work state
  private @Nullable SECTION currentSection;
  private String urlTemp = "";
  private @Nullable String urlHostPort;

  // results
  private @Nullable String urlUser;
  private @Nullable String urlPass;
  private @Nullable String urlHost;
  private @Nullable String urlPort;
  private @Nullable String urlDatabase;
  private @Nullable String urlArguments;

  //
  JdbcUrlParser(String url) {
    this.url = url;
  }

  void parse(Properties arguments, Properties values) throws JdbcUrlResolverFatalException {
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
    result10Argument(arguments);
    // result - priority 2: URL values
    result20Url(values);
  }

  private void saveSection(String value) {
    if (currentSection == null) {
      throw new RuntimeException(String.format("bug in code, unhandled currentSection [%s]", currentSection));
    }
    if (value.isEmpty()) {
      return;
    }
    switch (currentSection) {
      case USER:
        urlUser = value;
        break;
      case HOST:
        urlHostPort = value;
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

  private void parse10UrlPrefix() throws JdbcUrlResolverFatalException {
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
    throw new JdbcUrlResolverFatalException("url must start with 'jdbc:postgresql:[//]'");
  }

  private void parse20User() {
    if (urlTemp.isEmpty()) {
      return;
    }
    // find end of scan. "jdbc:postgresql://user[:password]@/?" section can not go beyond "/" or
    // "?", whichever comes first
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
    if (urlTemp.isEmpty()) {
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
    if (urlTemp.isEmpty()) {
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
    if (!urlTemp.isEmpty()) {
      // the rest are arguments
      saveSection(urlTemp);
      urlTemp = "";
    }
    currentSection = null;
  }

  private void parse70HostPort() {
    if (urlHostPort == null) {
      return;
    }
    final List<String> hostList = new ArrayList<>();
    final List<String> portList = new ArrayList<>();
    // split multiple endpoints (host and port pairs)
    for (String hostPort : urlHostPort.split(",", -1)) {
      // host:port separator is ":" (colon)
      // ipv6 address contains ":" (colons) and is surrounded by "[]"
      // looking for last ":" after "]" (if exist)
      int bracketPos = hostPort.lastIndexOf("]");
      int colonPos = hostPort.lastIndexOf(":");
      String host;
      String port = "";
      if (bracketPos < colonPos) {
        host = hostPort.substring(0, colonPos);
        port = hostPort.substring(colonPos + 1).trim();
      } else {
        host = hostPort;
      }
      //
      hostList.add(host);
      portList.add(port);
    }
    // save for later use
    urlHost = String.join(",", hostList);
    urlPort = String.join(",", portList);
    // clean port if empty
    if (urlHost.isEmpty()) {
      urlHost = null;
    }
    if (urlPort.isEmpty()) {
      urlPort = null;
    }
  }

  private void result10Argument(Properties result) throws JdbcUrlResolverFatalException {
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
      // may throw
      saveProperty(result, key, value);
    }
  }

  private void result20Url(Properties result) throws JdbcUrlResolverFatalException {
    // add url values
    if (urlDatabase != null) {
      PGProperty.DBNAME.set(result, urlDecode(urlDatabase));
    }
    if (urlUser != null) {
      PGProperty.USER.set(result, urlDecode(urlUser));
    }
    if (urlPass != null) {
      PGProperty.PASSWORD.set(result, urlDecode(urlPass));
    }
    if (urlHost != null) {
      PGProperty.HOST.set(result, urlDecode(urlHost));
    }
    if (urlPort != null) {
      PGProperty.PORT.set(result, urlDecode(urlPort));
    }
  }

  // wrapper for url decode, converts to URLParserFatalException() on failure
  private static String urlDecode(String url) throws JdbcUrlResolverFatalException {
    try {
      return URLCoder.decode(url);
    } catch (IllegalArgumentException e) {
      throw new JdbcUrlResolverFatalException(String.format("url [%s] parsing failed [%s]", url, e.getMessage()));
    }
  }

  static void saveProperty(Properties result, String key, @Nullable String value) throws JdbcUrlResolverFatalException {
    // is it valid key?
    PGProperty pgProperty = PGProperty.forName(key);
    if (pgProperty == null) {
      throw new JdbcUrlResolverFatalException(String.format("Unsupported property name: [%s]", key));
    } else if (value != null) {
      result.setProperty(key, value);
    }
  }

}
