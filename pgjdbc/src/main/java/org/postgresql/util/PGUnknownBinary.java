/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Represents an unknown PostgreSQL type received in binary format.
 *
 * <p>This class is used when the driver receives binary data for a type
 * that has no registered codec. Unlike {@link PGobject} which stores text
 * representations, this class preserves the raw binary data.</p>
 *
 * <p>This allows round-tripping unknown binary types back to the server
 * without data loss from text conversion.</p>
 *
 * @since 42.8.0
 */
public class PGUnknownBinary implements Serializable, Cloneable {

  private static final long serialVersionUID = 1L;

  private @Nullable String type;
  private byte @Nullable [] bytes;

  public PGUnknownBinary() {
  }

  public PGUnknownBinary(String type, byte[] bytes) {
    this.type = type;
    this.bytes = bytes != null ? bytes.clone() : null;
  }

  public @Nullable String getType() {
    return type;
  }

  public void setType(@Nullable String type) {
    this.type = type;
  }

  public byte @Nullable [] getBytes() {
    return bytes != null ? bytes.clone() : null;
  }

  public void setBytes(byte @Nullable [] bytes) {
    this.bytes = bytes != null ? bytes.clone() : null;
  }

  @Override
  public String toString() {
    if (bytes == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\\x");
    for (byte b : bytes) {
      sb.append(String.format("%02x", b & 0xff));
    }
    return sb.toString();
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof PGUnknownBinary)) {
      return false;
    }
    PGUnknownBinary other = (PGUnknownBinary) obj;
    if (type == null ? other.type != null : !type.equals(other.type)) {
      return false;
    }
    return Arrays.equals(bytes, other.bytes);
  }

  @Override
  public int hashCode() {
    int result = type != null ? type.hashCode() : 0;
    result = 31 * result + Arrays.hashCode(bytes);
    return result;
  }

  @Override
  public PGUnknownBinary clone() throws CloneNotSupportedException {
    PGUnknownBinary clone = (PGUnknownBinary) super.clone();
    if (bytes != null) {
      clone.bytes = bytes.clone();
    }
    return clone;
  }
}
