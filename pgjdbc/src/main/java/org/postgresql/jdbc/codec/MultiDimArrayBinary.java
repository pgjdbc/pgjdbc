/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.reflect.Array;
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
 * <p>Outer dimensions are walked via {@link Array}
 * ({@code get}/{@code getLength}/{@code newInstance}) — cost is bounded by the
 * outer-dimension product, not by element count. The leaf level is delegated
 * to caller-provided {@link LeafBinaryWriter}/{@link LeafBinaryReader}
 * strategies that operate on a typed Java leaf array (e.g. {@code int[]},
 * {@code Integer[]}, {@code Object[]}), so the hot per-element loop does
 * direct typed access without reflection.</p>
 */
public final class MultiDimArrayBinary {

  /**
   * Maximum number of array dimensions, matching the server's {@code MAXDIM} (see
   * {@code src/include/utils/array.h}). A binary array header carrying more dimensions than this is
   * corrupt or hostile wire, so reject it rather than allocate on the count.
   */
  private static final int MAX_DIMENSIONS = 6;

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
     * @param out  the output sink
     */
    boolean writeLeaf(Object leaf, BackpatchingBinarySink out, CodecContext ctx)
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

  public static byte[] encode(Object javaArray, CodecContext ctx, ArrayLeafCodec leaf)
      throws SQLException {
    int elementOid = leaf.getElementOid();
    BackpatchByteArrayOutputStream baos =
        new BackpatchByteArrayOutputStream(estimateInitialCapacityFor(javaArray, leaf));
    try {
      encode(javaArray, elementOid, (LeafBinaryWriter) leaf, baos, ctx);
    } catch (IOException e) {
      // BackpatchByteArrayOutputStream never throws.
      throw new AssertionError(e);
    }
    return baos.toByteArray();
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
    if (MultiDimArraySupport.isEmpty(dimLengths)) {
      // An empty array (a zero in any dimension) is the zero-dimension array on the wire, matching
      // what the server emits and accepts. A positive-dimension header would decode back to a
      // different shape than the text {} literal the sibling text encoder produces for the same
      // value (see MultiDimArrayText.encode), so both formats collapse to this one canonical shape.
      out.writeInt32(0); // dimensions
      out.writeInt32(0); // hasNulls
      out.writeInt32(elementOid);
      return;
    }

    out.writeInt32(dimensions);
    int hasNullsSlot = out.reserveInt32();
    out.writeInt32(elementOid);
    for (int d = 0; d < dimensions; d++) {
      out.writeInt32(dimLengths[d]);
      out.writeInt32(1); // lower bound
    }
    boolean hasNulls = walkAndEncode(javaArray, dimensions, out, ctx, leaf);
    out.setInt32At(hasNullsSlot, hasNulls ? 1 : 0);
  }

  public static void encode(Object javaArray, BackpatchingBinarySink out, CodecContext ctx,
      ArrayLeafCodec leaf) throws SQLException, IOException {
    encode(javaArray, leaf.getElementOid(), leaf, out, ctx);
  }

  private static boolean walkAndEncode(Object array, int depth, BackpatchingBinarySink out,
      CodecContext ctx, LeafBinaryWriter leaf) throws IOException, SQLException {
    if (depth == 1) {
      return leaf.writeLeaf(array, out, ctx);
    }
    int length = Array.getLength(array);
    boolean hasNulls = false;
    for (int i = 0; i < length; i++) {
      hasNulls |= walkAndEncode(Array.get(array, i), depth - 1, out, ctx, leaf);
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
   *
   * @param data the backing buffer; only the {@code [offset, offset + length)} slice belongs to
   *        this value — {@code data} may be a larger shared buffer (e.g. a composite field or a
   *        connection receive buffer), so decoding must never read outside that slice
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   */
  public static Object decode(byte[] data, int offset, int length, Class<?> leafComponentType,
      LeafBinaryReader leaf, CodecContext ctx) throws SQLException {
    int[] cursor = {offset};
    int dataEnd = offset + length;
    int dimensions = readInt4(data, cursor, dataEnd);
    boolean hasNulls = readInt4(data, cursor, dataEnd) != 0;
    readInt4(data, cursor, dataEnd); // element OID — caller already knows it

    if (dimensions == 0) {
      return Array.newInstance(leafComponentType, 0);
    }
    // dimensions is read straight from the wire: a negative count would throw
    // NegativeArraySizeException on the array allocations below, and an oversized one would drive an
    // OutOfMemoryError. The server never nests deeper than MAXDIM, so bound it before allocating.
    if (dimensions < 0 || dimensions > MAX_DIMENSIONS) {
      throw new PSQLException(
          GT.tr("Invalid binary array data: dimension count {0} out of range", dimensions),
          PSQLState.DATA_ERROR);
    }
    if (hasNulls && leafComponentType.isPrimitive()) {
      throw new PSQLException(
          GT.tr("Cannot decode array containing NULL into a primitive {0}[] leaf",
              leafComponentType.getName()),
          PSQLState.DATA_ERROR);
    }
    int[] dimLengths = new int[dimensions];
    for (int d = 0; d < dimensions; d++) {
      int dimLength = readInt4(data, cursor, dataEnd);
      readInt4(data, cursor, dataEnd); // lower bound
      if (dimLength < 0) {
        throw new PSQLException(
            GT.tr("Invalid binary array data: negative dimension length {0}", dimLength),
            PSQLState.DATA_ERROR);
      }
      dimLengths[d] = dimLength;
    }
    // Bound each partial dimension product against the bytes that remain after the header, not just the
    // final element count. Array.newInstance allocates the dimension spine eagerly -- dimLengths[0]
    // references, then that many arrays of dimLengths[1], and so on -- so a huge outer dimension
    // followed by a zero-length inner one would allocate a giant spine (an OutOfMemoryError) while the
    // full product collapses to zero and slips past a product-only bound. Every element the buffer can
    // describe needs at least its 4-byte length prefix, so no partial product can exceed
    // (remaining bytes / 4); a valid array has every dimension >= 1, so its partial products never
    // exceed the final element count and are never rejected here. Products accumulate in long to avoid
    // int overflow.
    long remainingBytes = (long) dataEnd - cursor[0];
    long maxElements = remainingBytes / 4;
    long partialProduct = 1;
    for (int d = 0; d < dimensions; d++) {
      partialProduct *= dimLengths[d];
      if (partialProduct > maxElements) {
        throw new PSQLException(
            GT.tr("Invalid binary array data: element count {0} exceeds remaining data", partialProduct),
            PSQLState.DATA_ERROR);
      }
    }
    Object result = Array.newInstance(leafComponentType, dimLengths);
    try {
      walkAndDecode(data, cursor, result, dimensions, ctx, leaf);
    } catch (IndexOutOfBoundsException e) {
      // The element-count cap above bounds the allocation, but a header can still promise more element
      // bytes than the body carries (a truncated element length or body). The per-element reads live in
      // the leaf codecs and index straight into data; a short body would AIOOBE out of them. Convert
      // that leak into a clean refusal at the container boundary — corrupt wire, never sent by a server.
      throw new PSQLException(
          GT.tr("Invalid binary array data: truncated element body"),
          PSQLState.DATA_ERROR, e);
    }
    if (cursor[0] > dataEnd) {
      // data is a slice of a possibly larger shared buffer, so a leaf reader that over-consumes past
      // this value's declared length would silently read bytes belonging to whatever follows instead
      // of tripping an AIOOBE — reject it explicitly instead of returning a container decoded from
      // borrowed bytes.
      throw new PSQLException(
          GT.tr("Invalid binary array data: element body extends past the declared length"),
          PSQLState.DATA_ERROR);
    }
    return result;
  }

  public static Object decode(byte[] data, int offset, int length, Class<?> leafComponentType,
      CodecContext ctx, ArrayLeafCodec leaf) throws SQLException {
    if (!leaf.supportsTargetComponent(leafComponentType)) {
      throw new PSQLException(
          GT.tr("Array leaf codec for oid {0} does not support {1}",
              leaf.getElementOid(), leafComponentType.getName()),
          PSQLState.DATA_TYPE_MISMATCH);
    }
    return decode(data, offset, length, leafComponentType, leaf, ctx);
  }

  private static void walkAndDecode(byte[] data, int[] cursor, Object container, int depth,
      CodecContext ctx, LeafBinaryReader leaf) throws SQLException {
    if (depth == 1) {
      leaf.readLeaf(data, cursor, container, ctx);
      return;
    }
    int length = Array.getLength(container);
    for (int i = 0; i < length; i++) {
      walkAndDecode(data, cursor, Array.get(container, i),
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

  private static int readInt4(byte[] data, int[] cursor, int dataEnd) throws SQLException {
    int pos = cursor[0];
    if (pos < 0 || pos > dataEnd - 4) {
      // A truncated header (fewer than 4 bytes left for this int4 within the value's slice) would
      // either AIOOBE out of ByteConverter.int4 or, if the backing buffer extends past dataEnd, read
      // past this value's declared bytes; refuse the corrupt wire with a checked failure instead.
      throw new PSQLException(
          GT.tr("Invalid binary array data: truncated header"),
          PSQLState.DATA_ERROR);
    }
    int v = ByteConverter.int4(data, pos);
    cursor[0] = pos + 4;
    return v;
  }
}
