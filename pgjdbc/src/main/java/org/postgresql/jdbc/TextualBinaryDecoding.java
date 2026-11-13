/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Encoding;
import org.postgresql.core.Oid;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;

/**
 * Decodes the binary representation of types whose binary format is their text representation in
 * {@code client_encoding}, possibly prefixed by a format version.
 */
final class TextualBinaryDecoding {

  /**
   * The only binary format version of {@code jsonb} that PostgreSQL has defined so far.
   */
  private static final int JSONB_VERSION = 1;

  private TextualBinaryDecoding() {
  }

  /**
   * Decodes the binary representation of a {@code json}, {@code jsonb}, or {@code xml} value.
   *
   * @param oid the type OID
   * @param bytes the value in binary format
   * @param encoding the connection encoding
   * @return the text representation of the value, or {@code null} if the type is not supported
   * @throws PSQLException if the value can't be decoded
   */
  static @Nullable String decode(int oid, byte[] bytes, Encoding encoding) throws PSQLException {
    int offset;

    switch (oid) {
      case Oid.JSON:
      case Oid.XML:
        offset = 0;
        break;
      case Oid.JSONB:
        // jsonb_send() writes a version byte, followed by the text
        if (bytes.length == 0 || bytes[0] != JSONB_VERSION) {
          throw new PSQLException(
              GT.tr("Unsupported binary format version {0} of type {1}",
                  bytes.length == 0 ? "<none>" : bytes[0], "jsonb"),
              PSQLState.DATA_ERROR);
        }
        offset = 1;
        break;
      default:
        return null;
    }

    try {
      return encoding.decode(bytes, offset, bytes.length - offset);
    } catch (IOException ioe) {
      throw new PSQLException(
          GT.tr("Invalid character data was found.  This is most likely caused by stored data containing characters that are invalid for the character set the database was created in.  The most common example of this is storing 8bit data in a SQL_ASCII database."),
          PSQLState.DATA_ERROR, ioe);
    }
  }
}
