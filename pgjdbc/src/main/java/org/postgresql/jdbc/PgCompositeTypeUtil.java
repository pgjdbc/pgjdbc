/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Tuple;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper class to extract columns' data from string / binary value of a composite type
 */
public final class PgCompositeTypeUtil {

  private static final Logger LOGGER = Logger.getLogger(PgCompositeTypeUtil.class.getName());

  private PgCompositeTypeUtil() {
  }

  public static Tuple fromString(String value, int nColumns) throws PSQLException {
    CharacterIterator it = new StringCharacterIterator(value);
    // skip whitespace
    while (Character.isWhitespace(it.current())) {
      it.next();
    }

    if (it.current() != '(') {
      throw invalidTextRepresentationException(value, "Missing left parenthesis");
    }
    it.next();

    byte[][] columnValues = new byte[nColumns][];
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    boolean needComma = false;

    for (int i = 0; i < nColumns; i++) {

      if (needComma) {
        if (it.current() == ',') {
          it.next();
        } else {
          // current char must be ')'
          throw invalidTextRepresentationException(value, "Too few columns");
        }
      }

      if (it.current() == ',' || it.current() == ')') {
        // empty elements are interpreted as null
        columnValues[i] = null;
      } else {
        boolean inQuote = false;

        while (inQuote || !(it.current() == ',' || it.current() == ')')) {
          if (it.current() == CharacterIterator.DONE) {
            throw invalidTextRepresentationException(value, "Unexpected end of input");
          }

          char ch = it.current();
          it.next();

          if (ch == '\\') {
            if (it.current() == CharacterIterator.DONE) {
              throw invalidTextRepresentationException(value, "Unexpected end of input");
            }

            buffer.write(it.current());
            it.next();
          } else if (ch == '"') {
            if (!inQuote) {
              inQuote = true;
            } else if (it.current() == '"') {
              buffer.write(it.current());
              it.next();
            } else {
              inQuote = false;
            }
          } else {
            buffer.write(ch);
          }
        }

        columnValues[i] = buffer.toByteArray();
        buffer.reset();

        needComma = true;
      }
    }
    if (it.current() != ')') {
      throw invalidTextRepresentationException(value, "Too many columns");
    }

    do {
      it.next();
    } while (Character.isWhitespace(it.current()));

    if (it.current() != CharacterIterator.DONE) {
      throw invalidTextRepresentationException(value, "Junk after right parenthesis");
    }

    return new Tuple(columnValues);
  }

  public static Tuple fromBytes(byte[] value, int nColumns) throws PSQLException {
    ByteBuffer buffer = ByteBuffer.wrap(value);
    ensureRemainingBuffer(buffer, 4, "Failed to read the number of columns");
    int numberOfColumns = buffer.getInt();

    if (nColumns != numberOfColumns) {
      throw invalidBinaryRepresentationException(
          String.format("Expected to read %d columns, found %d", nColumns, numberOfColumns)
      );
    }

    byte[][] data = new byte[numberOfColumns][];
    int i;
    for (i = 0; i < nColumns && buffer.hasRemaining(); i++) {
      ensureRemainingBuffer(buffer, 4, String.format("Failed to read OID for column at position %d", i));
      int oid = buffer.getInt(); // ignore type
      ensureRemainingBuffer(buffer, 4, String.format("Failed to read length for column at position %d", i));
      int len = buffer.getInt();
      if (len == -1) {
        data[i] = null;
        continue;
      }
      data[i] = new byte[len];
      ensureRemainingBuffer(buffer, len, String.format("Failed to read data for column at position %d", i));
      buffer.get(data[i]);
    }

    if (i < nColumns) {
      throw invalidBinaryRepresentationException("Too few columns");
    } else if (buffer.hasRemaining()) {
      throw invalidBinaryRepresentationException("Too many columns");
    }

    return new Tuple(data);
  }

  private static PSQLException invalidTextRepresentationException(String text, String reason) {
    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "Cannot parse composite type \"{0}\": {1}", new String[] { text, reason });
    }

    return new PSQLException(GT.tr("Cannot parse composite type \"{0}\": {1}", text, reason),
        PSQLState.INVALID_TEXT_REPRESENTATION);
  }

  private static void ensureRemainingBuffer(ByteBuffer buffer, int n, String msg) throws PSQLException {
    if (buffer.remaining() < n) {
      throw invalidBinaryRepresentationException(msg);
    }
  }

  private static PSQLException invalidBinaryRepresentationException(String reason) {
    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "Cannot parse composite type: {0}", reason);
    }

    return new PSQLException(GT.tr("Cannot parse composite type: {0}", reason),
        PSQLState.INVALID_BINARY_REPRESENTATION);
  }
}
