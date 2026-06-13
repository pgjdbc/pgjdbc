/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.jdbc.CodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Shared multi-dimensional binary array encoder/decoder.
 *
 * <p>Owns the PostgreSQL array binary wire format:</p>
 * <ul>
 *   <li>{@code int4} dimensions</li>
 *   <li>{@code int4} hasNulls flag</li>
 *   <li>{@code int4} element OID</li>
 *   <li>For each dimension: {@code int4} length + {@code int4} lower bound</li>
 *   <li>Flat depth-first element body: per element {@code int4} length
 *       (or {@code -1} for NULL) followed by the bytes</li>
 * </ul>
 *
 * <p>Outer dimensions are walked via {@link java.lang.reflect.Array}
 * ({@code get}/{@code getLength}/{@code newInstance}) — cost is bounded by the
 * outer-dimension product, not by element count. The leaf level is delegated
 * to caller-provided {@link LeafBinaryWriter}/{@link LeafBinaryReader}
 * strategies that operate on a typed Java leaf array (e.g. {@code int[]},
 * {@code Integer[]}, {@code Object[]}), so the hot per-element loop does
 * direct typed access without reflection.</p>
 */
public final class MultiDimArrayBinary {

  private MultiDimArrayBinary() {
    // Utility class
  }

  /**
   * Strategy for emitting one leaf-level 1-D array (the innermost slice of a
   * multi-dim Java array) as PostgreSQL array body bytes.
   *
   * <p>Single-abstract-method interface so callers can supply a static method
   * reference. The return value reports whether the leaf contained any NULLs,
   * which lets the array header be back-patched after a single encode pass.
   * Implementations MUST return {@code true} if they encoded any {@code -1}
   * element-length marker for a null element.</p>
   *
   * <p>The {@code out} parameter is a {@link BackpatchingBinarySink} so leaf
   * writers that dispatch through a per-element
   * {@link org.postgresql.api.codec.StreamingBinaryCodec} can reserve a
   * length placeholder, stream the element body in-place, and back-patch
   * the actual length without per-element {@code byte[]} allocations.</p>
   */
  @FunctionalInterface
  public interface LeafBinaryWriter {
    /**
     * Writes the leaf array's elements as per-element {@code int4} length
     * (or {@code -1} for null) followed by the encoded bytes.
     *
     * @param leaf the leaf-level Java array (e.g. {@code int[]}, {@code Object[]})
     * @param out the output sink
     * @param buf a reusable 4-byte scratch buffer
     */
    boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] buf, CodecContext ctx)
        throws IOException, SQLException;
  }

  /**
   * Strategy for populating one leaf-level 1-D array from the array body
   * bytes.
   */
  @FunctionalInterface
  public interface LeafBinaryReader {
    /**
     * Reads {@code leaf.length} elements from {@code data} starting at
     * {@code cursor[0]} into the provided leaf array; advances
     * {@code cursor[0]} past the consumed bytes.
     */
    void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx)
        throws SQLException;
  }

  // ---------------------------- encode ----------------------------

  public static byte[] encode(Object javaArray, int elementOid, CodecContext ctx, LeafBinaryWriter leaf) throws SQLException {
    BackpatchByteArrayOutputStream baos =
        new BackpatchByteArrayOutputStream(estimateInitialCapacityFor(javaArray, leaf));
    try {
      encode(javaArray, elementOid, leaf, baos, ctx);
    } catch (IOException e) {
      // BackpatchByteArrayOutputStream never throws.
      throw new AssertionError(e);
    }
    return baos.toByteArray();
  }

  public static byte[] encode(Object javaArray, CodecContext ctx, ArrayLeafCodec leaf)
      throws SQLException {
    return encode(javaArray, leaf.getElementOid(), ctx, leaf);
  }

  /**
   * Streaming variant: writes the encoded array into the provided sink.
   * Useful when a parent codec already owns a buffer (e.g. an outer array
   * encoding a composite element) and wants to avoid the intermediate
   * {@code byte[]} copy.
   */
  public static void encode(Object javaArray, int elementOid,
      LeafBinaryWriter leaf, BackpatchingBinarySink out, CodecContext ctx)
      throws SQLException, IOException {
    int dimensions = MultiDimArraySupport.computeDimensions(javaArray, leafElementClassOf(leaf));
    if (dimensions == 0) {
      throw new PSQLException(
          GT.tr("MultiDimArrayBinary.encode requires a Java array, got {0}",
              javaArray.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    int[] dimLengths = MultiDimArraySupport.computeDimensionLengths(javaArray, dimensions);

    byte[] scratch = new byte[4];
    out.writeInt32(dimensions);
    int hasNullsSlot = out.reserveInt32();
    out.writeInt32(elementOid);
    for (int d = 0; d < dimensions; d++) {
      out.writeInt32(dimLengths[d]);
      out.writeInt32(1); // lower bound
    }
    boolean hasNulls = walkAndEncode(javaArray, dimensions, out, scratch, ctx, leaf);
    out.setInt32At(hasNullsSlot, hasNulls ? 1 : 0);
  }

  public static void encode(Object javaArray, BackpatchingBinarySink out, CodecContext ctx,
      ArrayLeafCodec leaf) throws SQLException, IOException {
    encode(javaArray, leaf.getElementOid(), leaf, out, ctx);
  }

  private static boolean walkAndEncode(Object array, int depth, BackpatchingBinarySink out,
      byte[] buf, CodecContext ctx, LeafBinaryWriter leaf) throws IOException, SQLException {
    if (depth == 1) {
      return leaf.writeLeaf(array, out, buf, ctx);
    }
    int length = java.lang.reflect.Array.getLength(array);
    boolean hasNulls = false;
    for (int i = 0; i < length; i++) {
      hasNulls |= walkAndEncode(java.lang.reflect.Array.get(array, i), depth - 1, out, buf, ctx, leaf);
    }
    return hasNulls;
  }

  private static int estimateInitialCapacityFor(Object javaArray, LeafBinaryWriter leaf) {
    int dims = MultiDimArraySupport.computeDimensions(javaArray, leafElementClassOf(leaf));
    if (dims == 0) {
      return 64;
    }
    try {
      return estimateInitialCapacity(MultiDimArraySupport.computeDimensionLengths(javaArray, dims));
    } catch (SQLException e) {
      return 64;
    }
  }

  /**
   * The leaf's element Java class when it is an {@link ArrayLeafCodec} (so a
   * {@code byte[]} element of {@code bytea} is counted as a leaf, not a
   * dimension), otherwise {@code null} for a plain {@link LeafBinaryWriter}.
   */
  private static @Nullable Class<?> leafElementClassOf(LeafBinaryWriter leaf) {
    return leaf instanceof ArrayLeafCodec ? ((ArrayLeafCodec) leaf).getBoxedComponentType() : null;
  }

  // ---------------------------- decode ----------------------------

  /**
   * Decodes the binary representation into a Java multi-dim array of shape
   * driven by the wire dimensions, with leaf component type
   * {@code leafComponentType} (e.g. {@code int.class}, {@code Integer.class},
   * {@code Object.class}).
   */
  public static Object decode(byte[] data, Class<?> leafComponentType, LeafBinaryReader leaf,
      CodecContext ctx) throws SQLException {
    int[] cursor = {0};
    int dimensions = readInt4(data, cursor);
    boolean hasNulls = readInt4(data, cursor) != 0;
    readInt4(data, cursor); // element OID — caller already knows it

    if (dimensions == 0) {
      return java.lang.reflect.Array.newInstance(leafComponentType, 0);
    }
    if (hasNulls && leafComponentType.isPrimitive()) {
      throw new PSQLException(
          GT.tr("Cannot decode array containing NULL into a primitive {0}[] leaf",
              leafComponentType.getName()),
          PSQLState.DATA_ERROR);
    }
    int[] dimLengths = new int[dimensions];
    for (int d = 0; d < dimensions; d++) {
      dimLengths[d] = readInt4(data, cursor);
      readInt4(data, cursor); // lower bound
    }
    Object result = java.lang.reflect.Array.newInstance(leafComponentType, dimLengths);
    walkAndDecode(data, cursor, result, dimensions, ctx, leaf);
    return result;
  }

  public static Object decode(byte[] data, Class<?> leafComponentType, CodecContext ctx, ArrayLeafCodec leaf) throws SQLException {
    if (!leaf.supportsTargetComponent(leafComponentType)) {
      throw new PSQLException(
          GT.tr("Array leaf codec for oid {0} does not support {1}",
              leaf.getElementOid(), leafComponentType.getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    return decode(data, leafComponentType, (LeafBinaryReader) leaf, ctx);
  }

  private static void walkAndDecode(byte[] data, int[] cursor, Object container, int depth,
      CodecContext ctx, LeafBinaryReader leaf) throws SQLException {
    if (depth == 1) {
      leaf.readLeaf(data, cursor, container, ctx);
      return;
    }
    int length = java.lang.reflect.Array.getLength(container);
    for (int i = 0; i < length; i++) {
      walkAndDecode(data, cursor, java.lang.reflect.Array.get(container, i),
          depth - 1, ctx, leaf);
    }
  }

  // ---------------------------- helpers ----------------------------

  private static int estimateInitialCapacity(int[] dimLengths) {
    long elements = 1;
    for (int len : dimLengths) {
      elements *= Math.max(1, len);
    }
    // Header + (avg 8 bytes per element) — small fixed bound to keep memory predictable.
    long est = 20L + (8L * elements);
    return est > 1 << 20 ? 1 << 20 : (int) Math.max(64, est);
  }

  private static int readInt4(byte[] data, int[] cursor) {
    int v = ByteConverter.int4(data, cursor[0]);
    cursor[0] += 4;
    return v;
  }
}
