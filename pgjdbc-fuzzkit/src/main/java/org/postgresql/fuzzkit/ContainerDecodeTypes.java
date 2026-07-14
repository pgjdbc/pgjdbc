/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;

import java.util.Arrays;
import java.util.List;

/**
 * The container types the decode-robustness fuzzers drive, in one place so every engine's binary and text
 * targets -- and the coverage guard -- resolve the same offline {@link PgType} and {@link CodecContext}.
 * Each container routes to its delegating codec by {@code typtype}, not by a pinned OID.
 *
 * <ul>
 *   <li>the {@code int4[]} and {@code text[]} arrays and the {@code point} composite come from the shared
 *       {@link PgTypeDescriptors} registry;</li>
 *   <li>{@code int4range} and {@code int4multirange} are built inline with synthetic OIDs clear of the
 *       built-in range OIDs, so they route to {@code RangeCodec}/{@code MultirangeCodec} by {@code typtype}
 *       (not by a name alias) and resolve their bound codec offline without a connection.</li>
 * </ul>
 */
public final class ContainerDecodeTypes {

  private ContainerDecodeTypes() {
  }

  /** A container type paired with the offline context that resolves its (and its element's) codec. */
  public static final class TypeInContext {
    private final String name;
    private final PgType type;
    private final CodecContext context;

    TypeInContext(String name, PgType type, CodecContext context) {
      this.name = name;
      this.type = type;
      this.context = context;
    }

    public String name() {
      return name;
    }

    public PgType type() {
      return type;
    }

    public CodecContext context() {
      return context;
    }
  }

  public static final PgType INT4_ARRAY = PgTypeDescriptors.array(Oid.INT4_ARRAY).pgType();
  public static final PgType TEXT_ARRAY = PgTypeDescriptors.array(Oid.TEXT_ARRAY).pgType();
  public static final PgType POINT = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();

  /** The registered point composite so its field framing and {@code (x,y,label)} literal resolve offline. */
  public static final CodecContext POINT_CONTEXT = OfflineCodecs.builder().type(POINT).build();

  // Synthetic OIDs -- ranges/multiranges have no pinned OID in the driver -- kept clear of the built-in
  // range OIDs so the types route to RangeCodec/MultirangeCodec by typtype rather than by a name alias.
  private static final int INT4RANGE_OID = 91_001;
  private static final int INT4MULTIRANGE_OID = 91_002;

  // An offline int4range: a range type (typtype='r') carrying its subtype OID directly, so RangeCodec
  // resolves the int4 bound codec without a connection (pg_range.rngsubtype is normally loaded lazily).
  public static final PgType INT4RANGE =
      new PgType(new ObjectName("pg_catalog", "int4range"), "pg_catalog.int4range",
          INT4RANGE_OID, 'r', 'R', -1, 0, 0, 0).withRangeSubtype(Oid.INT4);
  public static final CodecContext INT4RANGE_CONTEXT = OfflineCodecs.builder().type(INT4RANGE).build();

  // An offline int4multirange (typtype='m') over the int4range above; the context registers both so
  // MultirangeCodec resolves the element range codec, which in turn resolves the int4 bound codec.
  public static final PgType INT4MULTIRANGE =
      new PgType(new ObjectName("pg_catalog", "int4multirange"), "pg_catalog.int4multirange",
          INT4MULTIRANGE_OID, 'm', 'R', -1, 0, 0, 0).withMultirangeRange(INT4RANGE_OID);
  public static final CodecContext INT4MULTIRANGE_CONTEXT =
      OfflineCodecs.builder().type(INT4RANGE).type(INT4MULTIRANGE).build();

  /**
   * Every container the decode-robustness targets exercise, so the coverage guard can resolve the
   * delegating codec class behind each. A new delegating container decoder must be added here (with its
   * binary and text targets) or the guard's exclusion list must account for it.
   */
  public static List<TypeInContext> all() {
    return Arrays.asList(
        new TypeInContext("int4Array", INT4_ARRAY, CodecFuzzSupport.builtins()),
        new TypeInContext("textArray", TEXT_ARRAY, CodecFuzzSupport.builtins()),
        new TypeInContext("composite", POINT, POINT_CONTEXT),
        new TypeInContext("range", INT4RANGE, INT4RANGE_CONTEXT),
        new TypeInContext("multirange", INT4MULTIRANGE, INT4MULTIRANGE_CONTEXT));
  }
}
