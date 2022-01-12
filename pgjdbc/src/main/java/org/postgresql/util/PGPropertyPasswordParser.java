/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.PGEnvironment;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * helps to read Password File.
 * https://www.postgresql.org/docs/current/libpq-pgpass.html
 */
public class PGPropertyPasswordParser {

  private static final Logger LOGGER = Logger.getLogger(PGPropertyPasswordParser.class.getName());
  private static final char SEPARATOR = ':';
  //
  private final String hostname;
  private final String port;
  private final String database;
  private final String user;

  //
  private PGPropertyPasswordParser(String hostname, String port, String database, String user) {
    this.hostname = hostname;
    this.port = port;
    this.database = database;
    this.user = user;
  }

  /**
   * Read .pgpass resource
   *
   * @param hostname hostname or *
   * @param port     port or *
   * @param database database or *
   * @param user     username or *
   * @return password or null
   */
  public static @Nullable String getPassword(@Nullable String hostname, @Nullable String port, @Nullable String database, @Nullable String user) {
    if (hostname == null || hostname.isEmpty()) {
      return null;
    }
    if (port == null || port.isEmpty()) {
      return null;
    }
    if (database == null || database.isEmpty()) {
      return null;
    }
    if (user == null || user.isEmpty()) {
      return null;
    }
    PGPropertyPasswordParser pgPropertyPasswordParser = new PGPropertyPasswordParser(hostname, port, database, user);
    return pgPropertyPasswordParser.findPassword();
  }

  private @Nullable String findPassword() {
    String resourceName = findPgPasswordResourceName();
    if (resourceName == null) {
      return null;
    }
    //
    String result = null;
    try (InputStream inputStream = openInputStream(resourceName)) {
      result = parseInputStream(inputStream);
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "Failed to handle resource [{0}] with error [{1}]", new Object[]{resourceName, e.getMessage()});
    }
    //
    return result;
  }

  // open URL or File
  private InputStream openInputStream(String resourceName) throws IOException {

    try {
      URL url = new URL(resourceName);
      return url.openStream();
    } catch ( MalformedURLException ex ) {
      // try file
      File file = new File(resourceName);
      return new FileInputStream(file);
    }
  }

  // choose resource where to search for service description
  private @Nullable String findPgPasswordResourceName() {
    // default file name
    String pgPassFileDefaultName = PGEnvironment.PGPASSFILE.getDefaultValue();

    // if there is value, use it - 1st priority
    {
      String propertyName = PGEnvironment.ORG_POSTGRESQL_PGPASSFILE.getName();
      String resourceName = System.getProperty(propertyName);
      if (resourceName != null && !resourceName.trim().isEmpty()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected from property [{1}]", new Object[]{resourceName, propertyName});
        return resourceName;
      }
    }

    // if there is value, use it - 2nd priority
    {
      String envVariableName = PGEnvironment.PGPASSFILE.getName();
      String resourceName = System.getenv().get(envVariableName);
      if (resourceName != null && !resourceName.trim().isEmpty()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected from environment variable [{1}]", new Object[]{resourceName, envVariableName});
        return resourceName;
      }
    }

    // if file in user home is readable, use it, otherwise continue - 3rd priority
    {
      String resourceName = "";
      if ( !OSUtil.isWindows() ) {
        resourceName += ".";
      }
      resourceName += pgPassFileDefaultName;
      if (OSUtil.isWindows()) {
        resourceName += ".conf";
      }
      File resourceFile = new File(OSUtil.getUserConfigRootDirectory(), resourceName);
      if (resourceFile.canRead()) {
        LOGGER.log(Level.FINE, "Value [{0}] selected because file exist in user home directory", new Object[]{resourceFile.getAbsolutePath()});
        return resourceFile.getAbsolutePath();
      }
    }

    // otherwise null
    LOGGER.log(Level.FINE, "Value for resource [{0}] not found", pgPassFileDefaultName);
    return null;
  }

  //
  private @Nullable String parseInputStream(InputStream inputStream) throws IOException {
    //
    String result = null;
    try (
        Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(reader)) {
      //
      String line;
      int currentLine = 0;
      while ((line = br.readLine()) != null) {
        currentLine++;
        if (line.trim().isEmpty()) {
          // skip empty lines
          continue;
        } else if (line.startsWith("#")) {
          // skip lines with comments
          continue;
        }
        // analyze line, accept first matching line
        result = evaluateLine(line, currentLine);
        if (result != null) {
          break;
        }
      }
    }
    //
    return result;
  }

  //
  private @Nullable String evaluateLine(String fullLine, int currentLine) {
    String line = fullLine;
    String result = null;
    // check match
    if ((line = checkForPattern(line, hostname)) != null
        && (line = checkForPattern(line, port)) != null
        && (line = checkForPattern(line, database)) != null
        && (line = checkForPattern(line, user)) != null) {
      // use remaining line to get password
      result = extractPassword(line);
      String lineWithoutPassword = fullLine.substring(0, fullLine.length() - line.length());
      LOGGER.log(Level.FINE, "Matching line number [{0}] with value prefix [{1}] found for input [{2}:{3}:{4}:{5}]",
          new Object[]{currentLine, lineWithoutPassword, hostname, port, database, user});
    }
    //
    return result;
  }

  //
  private String extractPassword(String line) {
    StringBuilder sb = new StringBuilder();
    // take all characters up to separator (which is colon)
    // remove escaping colon and backslash ("\\ -> \" ; "\: -> :")
    // single backslash is not considered as error ("\a -> \a")
    for (int i = 0; i < line.length(); i++) {
      char chr = line.charAt(i);
      if (chr == '\\' && (i + 1) < line.length()) {
        char nextChr = line.charAt(i + 1);
        if (nextChr == '\\' || nextChr == SEPARATOR) {
          chr = nextChr;
          i++;
        }
      } else if (chr == SEPARATOR) {
        break;
      }
      sb.append(chr);
    }
    return sb.toString();
  }

  //
  private @Nullable String checkForPattern(String line, String value) {
    String result = null;
    if (line.startsWith("*:")) {
      // any value match
      result = line.substring(2);
    } else {
      int lPos = 0;
      // Why not to split by separator (:) and compare by elements?
      // Ipv6 makes in tricky. ipv6 may contain different number of colons. Also, to maintain compatibility with libpq.
      // Compare beginning of line and value char by char.
      // line may have escaped values, value does not have escaping
      // line escaping is not mandatory. These are considered equal: "ab\cd:ef" == "ab\\cd\:ef" == "ab\cd\:ef" == "ab\\cd:ef"
      for (int vPos = 0; vPos < value.length(); vPos++) {
        if (lPos >= line.length()) {
          return null;
        }
        char l = line.charAt(lPos);
        if (l == '\\') {
          if ((lPos + 1) >= line.length()) {
            return null;
          }
          char next = line.charAt(lPos + 1);
          if (next == '\\' || next == SEPARATOR) {
            l = next;
            lPos++;
          }
        }
        lPos++;
        char v = value.charAt(vPos);
        if (l != v) {
          return null;
        }
      }
      if (line.charAt(lPos) == SEPARATOR) {
        result = line.substring(lPos + 1);
      }
    }
    return result;
  }

}
