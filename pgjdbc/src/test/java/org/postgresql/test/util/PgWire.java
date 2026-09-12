/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builders for backend messages in their wire form, for tests that feed a canned byte
 * script to the driver instead of talking to a server.
 *
 * <p>{@link #message(char, byte[]...)} computes the self-inclusive length prefix, which is
 * what a well-formed message carries. A test that needs a malformed one -- a length that
 * contradicts the body -- states the length itself, with {@link #messageWithLength} or with
 * {@link #bodyWithLength}.</p>
 */
public final class PgWire {

  private PgWire() {
  }

  /** A backend message: type byte, self-inclusive length, then the body. */
  public static byte[] message(char type, byte[]... body) {
    int bodyLength = 0;
    for (byte[] part : body) {
      bodyLength += part.length;
    }
    return messageWithLength(type, 4 + bodyLength, body);
  }

  /**
   * A backend message whose length prefix is stated rather than computed, so a test can
   * declare one length and send another.
   */
  public static byte[] messageWithLength(char type, int declaredLength, byte[]... body) {
    return concat(int1(type), bodyWithLength(declaredLength, body));
  }

  /**
   * The same as {@link #messageWithLength}, without the type byte, for a test that starts
   * the driver at the length prefix rather than at the message type.
   */
  public static byte[] bodyWithLength(int declaredLength, byte[]... body) {
    return concat(int4(declaredLength), concat(body));
  }

  /** A single byte, such as a message type or a ReadyForQuery status. */
  public static byte[] int1(int value) {
    return new byte[]{(byte) value};
  }

  public static byte[] int4(int value) {
    return new byte[]{
        (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value,
    };
  }

  public static byte[] int2(int value) {
    return new byte[]{(byte) (value >>> 8), (byte) value};
  }

  /** A string encoded as UTF-8, with nothing to terminate it. */
  public static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** A NUL-terminated string, encoded as UTF-8. */
  public static byte[] cstring(String value) {
    return concat(text(value), int1(0));
  }

  public static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  /** A byte array of {@code length} bytes with arbitrary but reproducible content. */
  public static byte[] filler(int length) {
    byte[] bytes = new byte[length];
    for (int i = 0; i < length; i++) {
      bytes[i] = (byte) i;
    }
    return bytes;
  }

  /**
   * Counts the frontend messages of the given type in what the driver wrote. The startup
   * packet carries no type byte, so it is skipped before the framed messages are walked.
   *
   * @param sent everything the driver wrote, starting with the startup packet
   * @param type the frontend message type to count, for example {@code p} for a password
   */
  public static int countFrontendMessages(byte[] sent, char type) {
    int pos = readInt4(sent, 0);
    int count = 0;
    while (pos + 5 <= sent.length) {
      if (sent[pos] == (byte) type) {
        count++;
      }
      pos += 1 + readInt4(sent, pos + 1);
    }
    return count;
  }

  private static int readInt4(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
        | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
  }

  public static byte[] authenticationOk() {
    return message('R', int4(0));
  }

  public static byte[] authenticationCleartextPassword() {
    return message('R', int4(3));
  }

  public static byte[] parameterStatus(String name, String value) {
    return message('S', cstring(name), cstring(value));
  }

  public static byte[] backendKeyData(int pid, byte[] cancelKey) {
    return message('K', int4(pid), cancelKey);
  }

  /** ReadyForQuery. {@code status} is {@code I}, {@code T} or {@code E}. */
  public static byte[] readyForQuery(char status) {
    return message('Z', int1(status));
  }

  /**
   * The startup dialogue of a server that accepts the connection without asking for
   * credentials. The reported version keeps the driver from running any setup query.
   */
  public static byte[] successfulStartup() {
    return concat(
        authenticationOk(),
        parameterStatus("server_version", "17.0"),
        parameterStatus("client_encoding", "UTF8"),
        parameterStatus("standard_conforming_strings", "on"),
        backendKeyData(4711, filler(4)),
        readyForQuery('I'));
  }
}
