/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.postgresql.core.Oid;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@link PgTypeDescriptor} registry: every descriptor, a lookup by OID, and a traversal of all.
 * Three static-init guards keep the registry consistent; all fail the class initialiser rather than
 * let a mismatch slip through to a fuzz run. Two are scoped to {@link ScalarDescriptor}: they check
 * coercion axes ({@code typedWriter}/{@code typedReader}, read rows), which only scalars carry. The
 * third checks the structural descriptors resolve their element and field references.
 *
 * <ul>
 *   <li><b>G3 (types):</b> every write-populated OID of {@link WriteCoercions} has a descriptor, and
 *       every scalar descriptor OID is populated in {@link ReadCoercions}. A scalar descriptor without
 *       a read row could never be read back, so it is an error.</li>
 *   <li><b>G5 (identity):</b> every scalar descriptor forms an identity path -- either a typed pair
 *       ({@code typedWriter} and {@code typedReader} both present) or the object axis through
 *       {@code naturalClass} (both absent). A half-typed state (one present, one absent) is an
 *       error.</li>
 *   <li><b>Structural resolve:</b> every {@link ArrayDescriptor}'s element and every
 *       {@link CompositeDescriptor}'s field OID resolves to a registered scalar descriptor. A dangling
 *       reference would fail only mid-fuzz, so the guard raises it at class init.</li>
 * </ul>
 *
 * <p>This is the C3 subset: the ten coercion scalars ({@code int4}, {@code int8}, {@code numeric},
 * {@code text}, {@code bool}, {@code date}, {@code time}, {@code timetz}, {@code timestamp},
 * {@code timestamptz}), the four codec-only scalars ({@code int2}, {@code float4}, {@code float8},
 * {@code bytea}), the two arrays ({@code int4[]}, {@code text[]}) over their scalar elements, and the
 * {@code point} composite ({@code x int4, y int4, label text}). The codec scalars are read-populated
 * but not write-populated, so they carry a descriptor yet stay out of the write-populated coercion
 * round-trip; the arrays and composite are populated in neither dictionary, so the coercion guards do
 * not apply to them.
 */
public final class PgTypeDescriptors {

  private static final Map<Integer, PgTypeDescriptor> BY_OID = buildRegistry();

  /** The {@code point} composite's OID, previously the codec fuzz support's {@code POINT_OID}. */
  public static final int POINT_OID = 90_001;

  static {
    guardTypes();
    guardIdentity();
    guardStructural();
  }

  private PgTypeDescriptors() {
  }

  private static Map<Integer, PgTypeDescriptor> buildRegistry() {
    Map<Integer, PgTypeDescriptor> map = new LinkedHashMap<>();
    // The ten coercion scalars (write-populated): they drive the coercion round-trip identity pairs.
    // int4 and text are bound to locals, because the two array descriptors reference them as elements.
    ScalarDescriptor int4 = new ScalarDescriptor(Oid.INT4, "int4", 'N', JDBCType.INTEGER,
        Integer.class, WriteCoercions.Method.WRITE_INT, ReadCoercions.Accessor.READ_INT,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON);
    add(map, int4);
    add(map, new ScalarDescriptor(Oid.INT8, "int8", 'N', JDBCType.BIGINT, Long.class,
        WriteCoercions.Method.WRITE_LONG, ReadCoercions.Accessor.READ_LONG,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.NUMERIC, "numeric", 'N', JDBCType.NUMERIC, BigDecimal.class,
        WriteCoercions.Method.WRITE_BIG_DECIMAL, ReadCoercions.Accessor.READ_BIG_DECIMAL,
        Fidelity.NUMERIC_EQUAL, ScalarDescriptor.NON_FINITE_NUMERIC));
    ScalarDescriptor text = new ScalarDescriptor(Oid.TEXT, "text", 'S', JDBCType.VARCHAR,
        String.class, WriteCoercions.Method.WRITE_STRING, ReadCoercions.Accessor.READ_STRING,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON);
    add(map, text);
    add(map, new ScalarDescriptor(Oid.BOOL, "bool", 'B', JDBCType.BOOLEAN, Boolean.class,
        WriteCoercions.Method.WRITE_BOOLEAN, ReadCoercions.Accessor.READ_BOOLEAN,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.DATE, "date", 'D', JDBCType.DATE, Date.class,
        WriteCoercions.Method.WRITE_DATE, ReadCoercions.Accessor.READ_DATE,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.TIME, "time", 'D', JDBCType.TIME, Time.class,
        WriteCoercions.Method.WRITE_TIME, ReadCoercions.Accessor.READ_TIME,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    // timetz has no typed Offset writer/reader, so it reaches identity through the object axis:
    // writeObject(OffsetTime) / readObject(OffsetTime.class). naturalClass differs from the default
    // getObject class (java.sql.Time), which is why it must be stored, not derived.
    add(map, new ScalarDescriptor(Oid.TIMETZ, "timetz", 'D', JDBCType.TIME_WITH_TIMEZONE,
        OffsetTime.class, null, null, Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.TIMESTAMP, "timestamp", 'D', JDBCType.TIMESTAMP, Timestamp.class,
        WriteCoercions.Method.WRITE_TIMESTAMP, ReadCoercions.Accessor.READ_TIMESTAMP,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    // timestamptz is an instant read back in the session zone, so its identity holds only up to the
    // moment (SAME_INSTANT); like timetz it uses the object axis with OffsetDateTime.
    add(map, new ScalarDescriptor(Oid.TIMESTAMPTZ, "timestamptz", 'D',
        JDBCType.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.class, null, null, Fidelity.SAME_INSTANT,
        ScalarDescriptor.NO_POISON));

    // The four codec-only scalars (read-populated, not write-populated): the codec round-trip fuzzers
    // need their PgType and natural class, but they carry no WriteCoercions row, so they stay out of the
    // coercion round-trip's identity pairs (which build only from write-populated types). int2's natural
    // class is Short (its WRITE_SHORT/READ_SHORT typed identity); its default getObject class stays
    // Integer, delegated to the dictionary (pgjdbc's documented smallint backward-compat).
    add(map, new ScalarDescriptor(Oid.INT2, "int2", 'N', JDBCType.SMALLINT, Short.class,
        WriteCoercions.Method.WRITE_SHORT, ReadCoercions.Accessor.READ_SHORT,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.FLOAT4, "float4", 'N', JDBCType.REAL, Float.class,
        WriteCoercions.Method.WRITE_FLOAT, ReadCoercions.Accessor.READ_FLOAT,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.FLOAT8, "float8", 'N', JDBCType.DOUBLE, Double.class,
        WriteCoercions.Method.WRITE_DOUBLE, ReadCoercions.Accessor.READ_DOUBLE,
        Fidelity.EQUALS, ScalarDescriptor.NO_POISON));
    add(map, new ScalarDescriptor(Oid.BYTEA, "bytea", 'U', JDBCType.BINARY, byte[].class,
        WriteCoercions.Method.WRITE_BYTES, ReadCoercions.Accessor.READ_BYTES,
        Fidelity.BYTES_EQUAL, ScalarDescriptor.NO_POISON));

    // The two codec arrays, over their scalar elements. One descriptor per element covers every
    // dimension {1, 2, 3}; int4 has a primitive leaf (int), text does not, so int4[] fuzzes both
    // Integer[n] and int[n] while text[] fuzzes String[n] only. Both carry DEEP_EQUALS.
    add(map, new ArrayDescriptor(Oid.INT4_ARRAY, "_int4", "int4[]", int4, 1, 2, 3));
    add(map, new ArrayDescriptor(Oid.TEXT_ARRAY, "_text", "text[]", text, 1, 2, 3));

    // The point composite (x int4, y int4, label text) -- the "regular" named-struct shape, previously
    // the codec fuzz support's pointType().
    add(map, new CompositeDescriptor(POINT_OID, "public", "fuzz_point", "public.fuzz_point",
        Arrays.asList(new CompositeDescriptor.Field("x", Oid.INT4),
            new CompositeDescriptor.Field("y", Oid.INT4),
            new CompositeDescriptor.Field("label", Oid.TEXT))));
    return Collections.unmodifiableMap(map);
  }

  private static void add(Map<Integer, PgTypeDescriptor> map, PgTypeDescriptor descriptor) {
    map.put(descriptor.oid(), descriptor);
  }

  /**
   * The descriptor for an OID.
   *
   * @param oid the PostgreSQL type OID
   * @return the descriptor
   * @throws IllegalArgumentException if no descriptor is registered for the OID
   */
  public static PgTypeDescriptor of(int oid) {
    PgTypeDescriptor descriptor = BY_OID.get(oid);
    if (descriptor == null) {
      throw new IllegalArgumentException("No PgTypeDescriptor for OID " + oid);
    }
    return descriptor;
  }

  /**
   * The scalar descriptor for an OID.
   *
   * @param oid the PostgreSQL type OID
   * @return the scalar descriptor
   * @throws IllegalArgumentException if no descriptor is registered for the OID, or it is not a scalar
   */
  public static ScalarDescriptor scalar(int oid) {
    PgTypeDescriptor descriptor = of(oid);
    if (!(descriptor instanceof ScalarDescriptor)) {
      throw new IllegalArgumentException("PgTypeDescriptor for OID " + oid + " is not a scalar");
    }
    return (ScalarDescriptor) descriptor;
  }

  /**
   * The array descriptor for an OID.
   *
   * @param oid the PostgreSQL array-type OID
   * @return the array descriptor
   * @throws IllegalArgumentException if no descriptor is registered for the OID, or it is not an array
   */
  public static ArrayDescriptor array(int oid) {
    PgTypeDescriptor descriptor = of(oid);
    if (!(descriptor instanceof ArrayDescriptor)) {
      throw new IllegalArgumentException("PgTypeDescriptor for OID " + oid + " is not an array");
    }
    return (ArrayDescriptor) descriptor;
  }

  /**
   * The composite descriptor for an OID.
   *
   * @param oid the PostgreSQL composite-type OID
   * @return the composite descriptor
   * @throws IllegalArgumentException if no descriptor is registered for the OID, or it is not a
   *     composite
   */
  public static CompositeDescriptor composite(int oid) {
    PgTypeDescriptor descriptor = of(oid);
    if (!(descriptor instanceof CompositeDescriptor)) {
      throw new IllegalArgumentException("PgTypeDescriptor for OID " + oid + " is not a composite");
    }
    return (CompositeDescriptor) descriptor;
  }

  /**
   * The descriptor for an OID, or {@code null} if none is registered.
   *
   * @param oid the PostgreSQL type OID
   * @return the descriptor, or {@code null}
   */
  public static @Nullable PgTypeDescriptor find(int oid) {
    return BY_OID.get(oid);
  }

  /** All registered descriptors, in registration order. */
  public static Collection<PgTypeDescriptor> all() {
    return BY_OID.values();
  }

  /** All registered scalar descriptors, in registration order. */
  public static Collection<ScalarDescriptor> scalars() {
    List<ScalarDescriptor> scalars = new ArrayList<>();
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (descriptor instanceof ScalarDescriptor) {
        scalars.add((ScalarDescriptor) descriptor);
      }
    }
    return Collections.unmodifiableList(scalars);
  }

  /** All registered array descriptors, in registration order. */
  public static Collection<ArrayDescriptor> arrays() {
    List<ArrayDescriptor> arrays = new ArrayList<>();
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (descriptor instanceof ArrayDescriptor) {
        arrays.add((ArrayDescriptor) descriptor);
      }
    }
    return Collections.unmodifiableList(arrays);
  }

  /** All registered composite descriptors, in registration order. */
  public static Collection<CompositeDescriptor> composites() {
    List<CompositeDescriptor> composites = new ArrayList<>();
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (descriptor instanceof CompositeDescriptor) {
        composites.add((CompositeDescriptor) descriptor);
      }
    }
    return Collections.unmodifiableList(composites);
  }

  /**
   * The write-populated scalar descriptors, in registration order -- the coercion scalars that carry a
   * {@link WriteCoercions} row and so take part in the coercion write/round-trip matrix. This excludes
   * the codec-only scalars ({@code int2}, {@code float4}, {@code float8}, {@code bytea}), which are
   * read-populated but not write-populated, keeping the coercion matrix at exactly the ten types it
   * covered before the codec scalars joined the registry.
   *
   * @return the write-populated scalar descriptors
   */
  public static Collection<ScalarDescriptor> coercionScalars() {
    List<ScalarDescriptor> scalars = new ArrayList<>();
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (descriptor instanceof ScalarDescriptor
          && WriteCoercions.populatedOids().contains(descriptor.oid())) {
        scalars.add((ScalarDescriptor) descriptor);
      }
    }
    return Collections.unmodifiableList(scalars);
  }

  /** The OIDs every registered descriptor covers. */
  public static Set<Integer> oids() {
    return Collections.unmodifiableSet(BY_OID.keySet());
  }

  private static void guardTypes() {
    // G3: every write-populated OID must have a descriptor, and every scalar descriptor OID must be
    // populated on the read side (a scalar descriptor without a read row could never be read back).
    Set<Integer> descriptorOids = BY_OID.keySet();
    Set<Integer> writeMissing = new TreeSet<>(WriteCoercions.populatedOids());
    writeMissing.removeAll(descriptorOids);
    if (!writeMissing.isEmpty()) {
      throw new ExceptionInInitializerError(
          "PgTypeDescriptors is missing descriptors for write-populated OIDs: " + writeMissing);
    }
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (descriptor instanceof ScalarDescriptor && !ReadCoercions.isPopulated(descriptor.oid())) {
        throw new ExceptionInInitializerError(
            "ScalarDescriptor OID " + descriptor.oid() + " has no ReadCoercions row");
      }
    }
  }

  private static void guardIdentity() {
    // G5: a scalar descriptor's identity path is either a typed pair (writer and reader both present)
    // or the object axis through naturalClass (both absent). One present and one absent is a half-typed
    // error. Only scalars carry typed writers/readers, so the guard is scoped to ScalarDescriptor.
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (!(descriptor instanceof ScalarDescriptor)) {
        continue;
      }
      ScalarDescriptor scalar = (ScalarDescriptor) descriptor;
      boolean hasWriter = scalar.typedWriter() != null;
      boolean hasReader = scalar.typedReader() != null;
      if (hasWriter != hasReader) {
        throw new ExceptionInInitializerError("ScalarDescriptor OID " + scalar.oid()
            + " has a half-typed identity path: typedWriter=" + scalar.typedWriter()
            + ", typedReader=" + scalar.typedReader());
      }
    }
  }

  private static void guardStructural() {
    // Structural resolve: an array's element and a composite's field OIDs must each name a registered
    // scalar descriptor. A dangling reference would surface only mid-fuzz, so raise it at class init.
    for (PgTypeDescriptor descriptor : BY_OID.values()) {
      if (descriptor instanceof ArrayDescriptor) {
        ArrayDescriptor array = (ArrayDescriptor) descriptor;
        requireScalar(array.element().oid(),
            "ArrayDescriptor OID " + array.oid() + " element");
      } else if (descriptor instanceof CompositeDescriptor) {
        CompositeDescriptor composite = (CompositeDescriptor) descriptor;
        for (CompositeDescriptor.Field field : composite.fields()) {
          requireScalar(field.oid(),
              "CompositeDescriptor OID " + composite.oid() + " field '" + field.name() + "'");
        }
      }
    }
  }

  private static void requireScalar(int oid, String context) {
    PgTypeDescriptor referenced = BY_OID.get(oid);
    if (!(referenced instanceof ScalarDescriptor)) {
      throw new ExceptionInInitializerError(
          context + " references OID " + oid + ", which is not a registered scalar descriptor");
    }
  }
}
