/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.FieldMetadata;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.sql.SQLException;
import java.util.Locale;

public class Field {
  // The V3 protocol defines two constants for the format of data
  public static final int TEXT_FORMAT = 0;
  public static final int BINARY_FORMAT = 1;

  private final short length; // Internal Length of this field
  private final int oid; // OID of the type
  private final int mod; // type modifier of this field
  private String columnLabel; // Column label

  private int format = TEXT_FORMAT; // In the V3 protocol each field has a format
  // 0 = text, 1 = binary
  // In the V2 protocol all fields in a
  // binary cursor are binary and all
  // others are text

  private final int tableOid; // OID of table ( zero if no table )
  private final int positionInTable;

  // Cache fields filled in by AbstractJdbc2ResultSetMetaData.fetchFieldMetaData.
  // Don't use unless that has been called.
  private @Nullable FieldMetadata metadata;

  private @Nullable PgType pgType;
  private @Nullable Codec codec;

  /**
   * Construct a field based on the information fed to it.
   *
   * @param name the name (column name and label) of the field
   * @param oid the OID of the field
   * @param length the length of the field
   * @param mod modifier
   */
  public Field(String name, int oid, short length, int mod) {
    this(name, oid, length, mod, 0, 0);
  }

  /**
   * Constructor without mod parameter.
   *
   * @param name the name (column name and label) of the field
   * @param oid the OID of the field
   */
  public Field(String name, int oid) {
    this(name, oid, (short) 0, -1);
  }

  /**
   * Construct a field based on the information fed to it.
   * @param columnLabel the column label of the field
   * @param oid the OID of the field
   * @param length the length of the field
   * @param mod modifier
   * @param tableOid the OID of the columns' table
   * @param positionInTable the position of column in the table (first column is 1, second column is 2, etc...)
   */
  public Field(String columnLabel, int oid, short length, int mod, int tableOid,
      int positionInTable) {
    this.columnLabel = columnLabel;
    this.oid = oid;
    this.length = length;
    this.mod = mod;
    this.tableOid = tableOid;
    this.positionInTable = positionInTable;
    this.metadata = tableOid == 0 ? new FieldMetadata(columnLabel) : null;
  }

  /**
   * Returns the oid of this Field's data type.
   * @return the oid of this Field's data type
   */
  @Pure
  public int getOID() {
    return oid;
  }

  /**
   * Returns the mod of this Field's data type
   * @return the mod of this Field's data type
   */
  public int getMod() {
    return mod;
  }

  /**
   * Returns the column label of this Field's data type.
   * @return the column label of this Field's data type
   */
  public String getColumnLabel() {
    return columnLabel;
  }

  /**
   * Returns the length of this Field's data type.
   * @return the length of this Field's data type
   */
  public short getLength() {
    return length;
  }

  /**
   * Returns the format of this Field's data (text=0, binary=1).
   * @return the format of this Field's data (text=0, binary=1)
   */
  public int getFormat() {
    return format;
  }

  /**
   * Sets the format of this Field's data (text=0, binary=1).
   * @param format the format of this Field's data (text=0, binary=1)
   */
  public void setFormat(int format) {
    this.format = format;
  }

  /**
   * Returns the columns' table oid, zero if no oid available.
   * @return the columns' table oid, zero if no oid available
   */
  public int getTableOid() {
    return tableOid;
  }

  public int getPositionInTable() {
    return positionInTable;
  }

  public @Nullable FieldMetadata getMetadata() {
    return metadata;
  }

  public void setMetadata(FieldMetadata metadata) {
    this.metadata = metadata;
  }

  @Override
  public String toString() {
    return "Field(" + (columnLabel != null ? columnLabel : "")
        + "," + Oid.toString(oid)
        + "," + length
        + "," + (format == TEXT_FORMAT ? 'T' : 'B')
        + ")";
  }

  public PgType getPgType() {
    return castNonNull(pgType);
  }

  public int getSQLType() {
    return getPgType().getSqlType();
  }

  public String getPGType() {
    return getPgType().getFullName();
  }

  public void initializePgType(TypeInfo typeInfo) throws SQLException {
    if (pgType != null) {
      return;
    }
    PgType resolved = typeInfo.getPgTypeByOid(oid);
    pgType = resolved;
    // Warm the binary-receive capability memos at this safe point (a result set is
    // materializing, no protocol message is being composed), so a later bind can
    // read them via TypeInfo.shouldReceiveBinary() without a catalog query.
    typeInfo.backendCanSendBinary(resolved);
    typeInfo.driverCanReceiveBinary(resolved);
    typeInfo.isBinaryReceiveDisabled(resolved);
  }

  /**
   * Initializes the codec for this field.
   *
   * <p>This should be called after {@link #initializePgType(TypeInfo)} to ensure
   * the PgType is available for codec resolution.</p>
   *
   * @param codecRegistry the codec registry to use for lookup
   */
  public void initializeCodec(CodecRegistry codecRegistry) {
    if (codec != null) {
      return;
    }
    codec = codecRegistry.getByOid(oid, pgType);
  }

  /**
   * Returns the cached codec for this field.
   *
   * <p>Requires {@link #initializeCodec(CodecRegistry)} to have been called first.</p>
   *
   * @return the codec for this field
   */
  public Codec getCodec() {
    return castNonNull(codec);
  }

  /**
   * Returns the binary codec for this field, or null if binary encoding is not supported.
   *
   * @return the binary codec, or null
   */
  public @Nullable BinaryCodec getBinaryCodec() {
    Codec c = castNonNull(codec);
    return c instanceof BinaryCodec ? (BinaryCodec) c : null;
  }

  /**
   * Returns the text codec for this field, or null if text encoding is not supported.
   *
   * @return the text codec, or null
   */
  public @Nullable TextCodec getTextCodec() {
    Codec c = castNonNull(codec);
    return c instanceof TextCodec ? (TextCodec) c : null;
  }

  public void upperCaseLabel() {
    columnLabel = columnLabel.toUpperCase(Locale.ROOT);
  }
}
