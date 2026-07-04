/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgField;
import org.postgresql.jdbc.PgType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A composite {@link PgTypeDescriptor}: a named row type with an ordered list of {@link Field fields},
 * each a column name and the OID of a registered scalar descriptor. It replaces the hand-built
 * {@code point} composite the codec fuzz support carried inline -- {@code (x int4, y int4, label text)}.
 *
 * <p>Like {@link ArrayDescriptor}, a composite is not populated in the coercion dictionaries, so the
 * coercion guards G3 and G5 do not apply. The registry instead checks that every field OID resolves to
 * a registered scalar descriptor.
 */
public final class CompositeDescriptor extends PgTypeDescriptor {

  /** A composite field: a column name and the OID of the descriptor that types it. */
  public static final class Field {
    private final String name;
    private final int oid;

    /**
     * @param name the column name
     * @param oid the OID of the field's type descriptor
     */
    public Field(String name, int oid) {
      this.name = name;
      this.oid = oid;
    }

    /** The column name. */
    public String name() {
      return name;
    }

    /** The OID of the field's type descriptor. */
    public int oid() {
      return oid;
    }
  }

  private final String schema;
  private final String typeName;
  private final String fullName;
  private final List<Field> fields;

  /**
   * @param oid the composite type OID
   * @param schema the type's schema, such as {@code public}
   * @param typeName the type's local name, such as {@code fuzz_point}
   * @param fullName the type's display name, such as {@code public.fuzz_point}
   * @param fields the ordered composite fields
   */
  CompositeDescriptor(int oid, String schema, String typeName, String fullName, List<Field> fields) {
    super(oid);
    this.schema = schema;
    this.typeName = typeName;
    this.fullName = fullName;
    this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
  }

  /**
   * The offline composite {@link PgType}: a row type ({@code typtype='c'}, category {@code 'C'}) with a
   * {@link PgField} per declared field, numbered from 1 in order.
   */
  @Override
  public PgType pgType() {
    List<PgField> pgFields = new ArrayList<>(fields.size());
    for (int i = 0; i < fields.size(); i++) {
      Field field = fields.get(i);
      pgFields.add(new PgField(field.name(), field.oid(), i + 1, -1));
    }
    return new PgType(new ObjectName(schema, typeName), fullName, oid(), 'c', 'C', -1, 0, 0, 0, ',',
        pgFields);
  }

  /** The ordered composite fields. */
  public List<Field> fields() {
    return fields;
  }
}
