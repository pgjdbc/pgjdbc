/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbcurlresolver;

import org.postgresql.util.internal.FileUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * helps to read Password File.
 * https://www.postgresql.org/docs/current/libpq-pgpass.html
 */
class PgPassParser {

  private static final Logger LOGGER = Logger.getLogger(PgPassParser.class.getName());
  private static final char SEPARATOR = ':';
  //
  private final String fileName;
  private final String hostname;
  private final String port;
  private final String database;
  private final String user;

  //
  private PgPassParser(String fileName, String hostname, String port, String database, String user) {
    this.fileName = fileName;
    this.hostname = hostname;
    this.port = port;
    this.database = database;
    this.user = user;
  }

  /**
   * Read .pgpass resource
   *
   * @param fileName fileName
   * @param hostname hostname
   * @param port     port
   * @param database database
   * @param user     username
   * @return password or null
   */
  static @Nullable String getPassword(String fileName, @Nullable String hostname, @Nullable String port, @Nullable String database, @Nullable String user) {
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
    PgPassParser pgPassParser = new PgPassParser(fileName, hostname, port, database, user);
    return pgPassParser.findPassword();
  }

  private @Nullable String findPassword() {
    String result = null;
    try (InputStream inputStream = openInputStream(fileName)) {
      if (inputStream != null) {
        LOGGER.log(Level.FINE, "Resource [{0}] is used for passwords (.pgpass)", new Object[]{fileName});
        result = parseInputStream(inputStream);
      }
    } catch (IOException e) {
      LOGGER.log(Level.FINE, "Failed to read resource [{0}] with error [{1}]", new Object[]{fileName, e.getMessage()});
    }
    //
    return result;
  }

  // open File
  private @Nullable InputStream openInputStream(String resourceName) throws IOException {
    Path path = new File(resourceName).toPath();
    return checkFilePermissions(path) ? FileUtils.newBufferedInputStream(resourceName) : null;
  }

  // in case there are permissions for "group" or "other" then return false
  private boolean checkFilePermissions(Path path) throws IOException {
    FileSystem fileSystem = path.getFileSystem();
    // check works for posix filesystems
    if (fileSystem.supportedFileAttributeViews().contains("posix")) {
      Set<PosixFilePermission> posixFilePermissions = Files.getPosixFilePermissions(path);
      for (PosixFilePermission filePermission : posixFilePermissions) {
        switch (filePermission) {
          case GROUP_READ:
          case GROUP_WRITE:
          case GROUP_EXECUTE:
          case OTHERS_READ:
          case OTHERS_WRITE:
          case OTHERS_EXECUTE:
            LOGGER.log(Level.WARNING, "password file [{0}] has group or world access [{1}]; permissions should be u=rw (0600) or less",
              new Object[]{fileName, PosixFilePermissions.toString(posixFilePermissions)});
            return false;
        }
      }
    }
    return true;
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
  private static String extractPassword(String line) {
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
  private static @Nullable String checkForPattern(String line, String value) {
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
