/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Leaf-level strategy for PostgreSQL array codecs.
 *
 * <p>The shared multi-dimensional array helpers own PostgreSQL array shape,
 * headers, rectangular validation, and target-array allocation. Implementations
 * own the hot leaf loops for a concrete element type, including primitive-array
 * support.</p>
 */
interface ArrayLeafCodec extends MultiDimArrayBinary.LeafBinaryWriter,
    MultiDimArrayBinary.LeafBinaryReader, MultiDimArrayText.LeafTextWriter,
    MultiDimArrayText.LeafTextReader {

  int getElementOid();

  Class<?> getPrimitiveComponentType();

  Class<?> getBoxedComponentType();

  default boolean supportsTargetComponent(Class<?> componentType) {
    return componentType == getPrimitiveComponentType()
        || componentType == getBoxedComponentType();
  }

  default PSQLException unsupportedLeaf(Object leaf, CodecContext ctx) {
    return new PSQLException(
        GT.tr("Unsupported leaf array class for {0}: {1}", getArrayTypeDescription(ctx),
            leaf.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  default String getArrayTypeDescription(CodecContext ctx) {
    try {
      // Transitional downcast (slice 2c): reach the internal TypeInfo through the implementation
      // until child-type resolution moves onto the CodecContext interface.
      TypeInfo typeInfo = ((PgCodecContext) ctx).getTypeInfo();
      PgType elementType = typeInfo.getPgTypeByOid(getElementOid());
      return elementType.getFullName() + "[]";
    } catch (RuntimeException | SQLException e) {
      // Fall through to built-in Oid names when context has no TypeInfo,
      // for instance in unit tests that pass a test CodecContext.
    }
    String elementName = Oid.toString(getElementOid());
    if (elementName.startsWith("<unknown:")) {
      return GT.tr("array with element oid {0}", getElementOid());
    }
    return "_" + elementName.toLowerCase(Locale.ROOT);
  }

  @Override
  boolean writeLeaf(Object leaf, BackpatchingBinarySink out, byte[] scratch, CodecContext ctx)
      throws IOException, SQLException;

  @Override
  void readLeaf(byte[] data, int[] cursor, Object leaf, CodecContext ctx)
      throws SQLException;

  @Override
  void appendLeaf(Appendable out, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException, IOException;

  @Override
  void readLeafText(LiteralCursor cur, Object leaf, char delimiter, CodecContext ctx)
      throws SQLException;
}
