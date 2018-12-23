/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.jdbc.PgResultSet;
import org.postgresql.udt.BaseValueAccess;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
import java.nio.charset.StandardCharsets;
//#endif
//#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
import java.nio.charset.UnsupportedCharsetException;
//#endif
import java.sql.SQLException;

/**
 * Implementation of ASCII stream conversion for both {@link PgResultSet} and
 * {@link BaseValueAccess}.
 */
public class AsciiStream {

  private AsciiStream() {
    // prevent instantiation of static helper class
  }

  // SQLException will no longer be necessary once all builds are JDBC >= 4.1
  public static InputStream getAsciiStream(String value) throws SQLException {
    Charset charset;
    //#if mvn.project.property.postgresql.jdbc.spec < "JDBC4.1"
    try {
      charset = Charset.forName("US-ASCII");
    } catch (UnsupportedCharsetException e) {
      throw new PSQLException(GT.tr("The JVM claims not to support the character set: {0}", "US-ASCII"),
          PSQLState.UNEXPECTED_ERROR, e);
    }
    //#else
    charset = StandardCharsets.US_ASCII;
    //#endif
    return new ByteArrayInputStream(value.getBytes(charset));
  }
}
