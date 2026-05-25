/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * Immutable value object representing a complete PostgreSQL protocol message.
 *
 * <p>A protocol message consists of a one-byte type identifier followed by a
 * four-byte length (including itself) and then the payload bytes.</p>
 */
public final class ProtocolMessage {
  private final int type;
  private final byte[] payload;

  /**
   * Creates a new protocol message.
   *
   * @param type the message type byte (e.g. {@link PgMessageType#DATA_ROW_RESPONSE})
   * @param payload the message payload (length field already consumed, not included)
   */
  public ProtocolMessage(int type, byte[] payload) {
    this.type = type;
    this.payload = payload;
  }

  /**
   * Returns the message type byte.
   *
   * @return message type as an unsigned byte value
   */
  public int getType() {
    return type;
  }

  /**
   * Returns the message payload (excludes the type byte and 4-byte length).
   *
   * @return payload bytes
   */
  public byte[] getPayload() {
    return payload;
  }

  /**
   * Returns the total wire size of this message (1 type + 4 length + payload).
   *
   * @return total wire size in bytes
   */
  public int getWireSize() {
    return 1 + 4 + payload.length;
  }
}
