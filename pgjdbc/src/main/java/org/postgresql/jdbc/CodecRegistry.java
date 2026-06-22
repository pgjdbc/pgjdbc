/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.codec.ArrayCodec;
import org.postgresql.jdbc.codec.BitCodec;
import org.postgresql.jdbc.codec.BoolCodec;
import org.postgresql.jdbc.codec.BpcharCodec;
import org.postgresql.jdbc.codec.ByteaCodec;
import org.postgresql.jdbc.codec.CompositeCodec;
import org.postgresql.jdbc.codec.DateCodec;
import org.postgresql.jdbc.codec.DomainCodec;
import org.postgresql.jdbc.codec.EnumCodec;
import org.postgresql.jdbc.codec.FallbackCodec;
import org.postgresql.jdbc.codec.Float4Codec;
import org.postgresql.jdbc.codec.Float8Codec;
import org.postgresql.jdbc.codec.GeometricCodec;
import org.postgresql.jdbc.codec.HstoreCodec;
import org.postgresql.jdbc.codec.Int2Codec;
import org.postgresql.jdbc.codec.Int4Codec;
import org.postgresql.jdbc.codec.Int8Codec;
import org.postgresql.jdbc.codec.IntervalCodec;
import org.postgresql.jdbc.codec.JsonCodec;
import org.postgresql.jdbc.codec.JsonbCodec;
import org.postgresql.jdbc.codec.MoneyCodec;
import org.postgresql.jdbc.codec.NameCodec;
import org.postgresql.jdbc.codec.NumericCodec;
import org.postgresql.jdbc.codec.OidCodec;
import org.postgresql.jdbc.codec.RangeCodec;
import org.postgresql.jdbc.codec.TextCodecImpl;
import org.postgresql.jdbc.codec.TimeCodec;
import org.postgresql.jdbc.codec.TimestampCodec;
import org.postgresql.jdbc.codec.TimestamptzCodec;
import org.postgresql.jdbc.codec.TimetzCodec;
import org.postgresql.jdbc.codec.UuidCodec;
import org.postgresql.jdbc.codec.VarcharCodec;
import org.postgresql.jdbc.codec.XmlCodec;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for codec instances.
 *
 * <p>Manages codec lookup by OID, type name, and Java class. This registry is
 * connection-scoped to allow per-connection codec customization.</p>
 *
 * <h2>Codec Resolution Order</h2>
 * <ol>
 *   <li>Explicit OID registration (per-connection)</li>
 *   <li>Type name lookup (per-connection)</li>
 *   <li>SPI-loaded codecs (driver-scoped)</li>
 *   <li>Built-in codecs</li>
 *   <li>Fallback codec for unknown types</li>
 * </ol>
 *
 * <h2>Caching</h2>
 *
 * <p>OID → Codec lookups are cached using Caffeine for performance.
 * The cache is size-bounded (default 1000 entries) with LRU eviction.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public class CodecRegistry {

  /** Maximum number of OID → Codec mappings to cache. */
  private static final int OID_CACHE_SIZE = 1000;

  // Type name -> codec mapping (primary registry)
  private final Map<String, Codec> codecsByName = new ConcurrentHashMap<>();

  // Java class -> codec mapping (for encoding)
  private final Map<Class<?>, Codec> codecsByClass = new ConcurrentHashMap<>();

  // OID -> codec cache (Caffeine LRU cache)
  private final Cache<Integer, Codec> oidCache;

  // Explicit OID registrations (override cache)
  private final Map<Integer, Codec> explicitOidCodecs = new ConcurrentHashMap<>();

  // Track custom codec names for reset functionality
  private final Set<String> customCodecNames = ConcurrentHashMap.newKeySet();

  // SPI-loaded codecs (loaded once per driver)
  private static volatile boolean spiLoaded = false;
  private static final Map<String, Codec> spiCodecs = new ConcurrentHashMap<>();

  /**
   * Creates a new CodecRegistry with default codecs.
   */
  @SuppressWarnings({"this-escape", "method.invocation"})
  public CodecRegistry() {
    this.oidCache = Caffeine.newBuilder()
        .maximumSize(OID_CACHE_SIZE)
        .build();

    // Load SPI codecs once
    loadSpiCodecs();

    // Register built-in codecs. The instance is fully field-initialized at this
    // point (oidCache is set above; the registration maps are final and assigned
    // in their declarations), so the constructor escape is safe.
    registerBuiltinCodecs();
    registerSpiCodecs();
  }

  /**
   * Loads codecs via ServiceLoader (SPI).
   * This is called once per driver initialization.
   */
  private static synchronized void loadSpiCodecs() {
    if (spiLoaded) {
      return;
    }
    spiLoaded = true;

    try {
      ServiceLoader<Codec> loader = ServiceLoader.load(Codec.class);
      Iterator<Codec> it = loader.iterator();
      while (it.hasNext()) {
        try {
          Codec codec = it.next();
          spiCodecs.put(codec.getTypeName(), codec);
        } catch (Exception e) {
          // Log and continue - don't let one bad codec break everything
          // In production, this should use the driver's logging infrastructure
        }
      }
    } catch (Exception e) {
      // ServiceLoader failed - continue with built-in codecs only
    }
  }

  /**
   * Registers all built-in codecs.
   */
  private void registerBuiltinCodecs() {
    // Numeric types
    registerByName(Int2Codec.INSTANCE);
    registerByName(Int4Codec.INSTANCE);
    registerByName(Int8Codec.INSTANCE);
    registerByName(Float4Codec.INSTANCE);
    registerByName(Float8Codec.INSTANCE);
    registerByName(NumericCodec.INSTANCE);
    registerByName(MoneyCodec.INSTANCE);
    registerByName(OidCodec.INSTANCE);

    // String types
    registerByName(TextCodecImpl.INSTANCE);
    registerByName(VarcharCodec.INSTANCE);
    registerByName(BpcharCodec.INSTANCE);
    registerByName(NameCodec.INSTANCE);
    registerByName(BoolCodec.INSTANCE);

    // Date/time types
    registerByName(DateCodec.INSTANCE);
    registerByName(TimeCodec.INSTANCE);
    registerByName(TimetzCodec.INSTANCE);
    registerByName(TimestampCodec.INSTANCE);
    registerByName(TimestamptzCodec.INSTANCE);
    registerByName(IntervalCodec.INSTANCE);

    // Binary types
    registerByName(ByteaCodec.INSTANCE);
    registerByName(UuidCodec.INSTANCE);

    // Bit string types
    registerByName(BitCodec.INSTANCE);
    registerAlias("bit varying", BitCodec.INSTANCE);
    registerAlias("varbit", BitCodec.INSTANCE);

    // JSON/XML types
    registerByName(JsonCodec.INSTANCE);
    registerByName(JsonbCodec.INSTANCE);
    registerByName(XmlCodec.INSTANCE);

    // Composite types
    registerByName(ArrayCodec.INSTANCE);
    registerByName(CompositeCodec.INSTANCE);
    registerByName(DomainCodec.INSTANCE);

    // Range types
    registerByName(RangeCodec.INSTANCE);

    // Extension types (built-in support)
    registerByName(HstoreCodec.INSTANCE);

    // Geometric types
    registerByName(GeometricCodec.POINT);
    registerByName(GeometricCodec.BOX);
    registerByName(GeometricCodec.CIRCLE);
    registerByName(GeometricCodec.LINE);
    registerByName(GeometricCodec.LSEG);
    registerByName(GeometricCodec.PATH);
    registerByName(GeometricCodec.POLYGON);

    // Type aliases
    registerAlias("int2", Int2Codec.INSTANCE);
    registerAlias("smallint", Int2Codec.INSTANCE);
    registerAlias("int4", Int4Codec.INSTANCE);
    registerAlias("integer", Int4Codec.INSTANCE);
    registerAlias("int", Int4Codec.INSTANCE);
    registerAlias("serial", Int4Codec.INSTANCE);
    registerAlias("int8", Int8Codec.INSTANCE);
    registerAlias("bigint", Int8Codec.INSTANCE);
    registerAlias("bigserial", Int8Codec.INSTANCE);
    registerAlias("float4", Float4Codec.INSTANCE);
    registerAlias("real", Float4Codec.INSTANCE);
    registerAlias("float8", Float8Codec.INSTANCE);
    registerAlias("double precision", Float8Codec.INSTANCE);
    registerAlias("numeric", NumericCodec.INSTANCE);
    registerAlias("decimal", NumericCodec.INSTANCE);
    registerAlias("varchar", VarcharCodec.INSTANCE);
    registerAlias("character varying", VarcharCodec.INSTANCE);
    registerAlias("bpchar", BpcharCodec.INSTANCE);
    registerAlias("character", BpcharCodec.INSTANCE);
    registerAlias("char", BpcharCodec.INSTANCE);
    registerAlias("bool", BoolCodec.INSTANCE);
    registerAlias("boolean", BoolCodec.INSTANCE);
    registerAlias("timetz", TimetzCodec.INSTANCE);
    registerAlias("time with time zone", TimetzCodec.INSTANCE);
    registerAlias("time without time zone", TimeCodec.INSTANCE);
    registerAlias("timestamptz", TimestamptzCodec.INSTANCE);
    registerAlias("timestamp with time zone", TimestamptzCodec.INSTANCE);
    registerAlias("timestamp without time zone", TimestampCodec.INSTANCE);

    // Range type aliases
    registerAlias("int4range", RangeCodec.INSTANCE);
    registerAlias("int8range", RangeCodec.INSTANCE);
    registerAlias("numrange", RangeCodec.INSTANCE);
    registerAlias("tsrange", RangeCodec.INSTANCE);
    registerAlias("tstzrange", RangeCodec.INSTANCE);
    registerAlias("daterange", RangeCodec.INSTANCE);

    // Register codecs by their default Java types
    registerByClass(Short.class, Int2Codec.INSTANCE);
    registerByClass(Integer.class, Int4Codec.INSTANCE);
    registerByClass(Long.class, Int8Codec.INSTANCE);
    registerByClass(Float.class, Float4Codec.INSTANCE);
    registerByClass(Double.class, Float8Codec.INSTANCE);
    registerByClass(java.math.BigDecimal.class, NumericCodec.INSTANCE);
    registerByClass(String.class, TextCodecImpl.INSTANCE);
    registerByClass(Boolean.class, BoolCodec.INSTANCE);
    registerByClass(java.sql.Date.class, DateCodec.INSTANCE);
    registerByClass(java.sql.Time.class, TimeCodec.INSTANCE);
    registerByClass(java.sql.Timestamp.class, TimestamptzCodec.INSTANCE);
    registerByClass(java.util.UUID.class, UuidCodec.INSTANCE);
    registerByClass(byte[].class, ByteaCodec.INSTANCE);
    registerByClass(org.postgresql.util.PGRange.class, RangeCodec.INSTANCE);
  }

  /**
   * Applies SPI-loaded codecs to this registry after built-ins so a consumer
   * can override default codecs from the test/application classpath.
   */
  private void registerSpiCodecs() {
    for (Codec codec : spiCodecs.values()) {
      registerByName(codec);
      registerByClass(codec.getDefaultJavaType(), codec);
    }
  }

  /**
   * Registers a codec by its type name.
   *
   * @param codec the codec to register
   */
  public void registerByName(Codec codec) {
    codecsByName.put(codec.getTypeName(), codec);
  }

  /**
   * Registers an alias for a codec.
   *
   * @param alias the alias name
   * @param codec the codec
   */
  public void registerAlias(String alias, Codec codec) {
    codecsByName.put(alias, codec);
  }

  /**
   * Registers a codec for a specific Java class.
   *
   * @param javaClass the Java class
   * @param codec the codec to register
   */
  public void registerByClass(Class<?> javaClass, Codec codec) {
    codecsByClass.put(javaClass, codec);
  }

  /**
   * Registers a codec for a specific OID.
   *
   * <p>This creates an explicit binding that overrides type-name based lookup.</p>
   *
   * @param oid the PostgreSQL type OID
   * @param codec the codec to register
   */
  public void registerByOid(int oid, Codec codec) {
    explicitOidCodecs.put(oid, codec);
    oidCache.put(oid, codec);
  }

  /**
   * Registers a custom codec for this connection.
   *
   * <p>Custom codecs are tracked separately and can be cleared via {@link #resetCustomCodecs()}.
   * This is useful for connection pool reset scenarios.</p>
   *
   * @param codec the codec to register
   */
  public void registerCustomCodec(Codec codec) {
    String typeName = codec.getTypeName();
    customCodecNames.add(typeName);
    codecsByName.put(typeName, codec);
    oidCache.invalidateAll();
  }

  /**
   * Unregisters a custom codec by type name.
   *
   * @param typeName the type name to unregister
   */
  public void unregisterCustomCodec(String typeName) {
    if (customCodecNames.remove(typeName)) {
      codecsByName.remove(typeName);
      oidCache.invalidateAll();
    }
  }

  /**
   * Clears all custom codecs registered on this registry.
   *
   * <p>Built-in codecs are not affected. This is useful for connection pool
   * reset scenarios.</p>
   */
  public void resetCustomCodecs() {
    for (String typeName : customCodecNames) {
      codecsByName.remove(typeName);
    }
    customCodecNames.clear();
    explicitOidCodecs.clear();
    oidCache.invalidateAll();
  }

  /**
   * Gets the codec for a specific type name.
   *
   * @param typeName the PostgreSQL type name
   * @return the codec, or null if not found
   */
  public @Nullable Codec getByName(String typeName) {
    // First check connection-scoped registry
    Codec codec = codecsByName.get(typeName);
    if (codec != null) {
      return codec;
    }

    // Then check SPI-loaded codecs
    codec = spiCodecs.get(typeName);
    if (codec != null) {
      return codec;
    }

    return null;
  }

  /**
   * Gets the codec for a specific OID, using type information for resolution.
   *
   * <p>This method uses Caffeine caching for performance. The resolution order is:</p>
   * <ol>
   *   <li>Explicit OID registration</li>
   *   <li>Type name lookup via PgType</li>
   *   <li>Fallback codec</li>
   * </ol>
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information (may be null for cache-only lookup)
   * @return the codec (never null - returns FallbackCodec for unknown types)
   */
  public Codec getByOid(int oid, @Nullable PgType pgType) {
    // Check explicit registrations first
    Codec explicit = explicitOidCodecs.get(oid);
    if (explicit != null) {
      return explicit;
    }

    // Check cache
    Codec cached = oidCache.getIfPresent(oid);
    if (cached != null) {
      return cached;
    }

    // Resolve via type name
    if (pgType != null) {
      String typeName = pgType.getTypeName().getName();
      Codec codec = getByName(typeName);
      if (codec != null) {
        oidCache.put(oid, codec);
        return codec;
      }

      // Resolve by typtype/typcategory for user-defined types
      Codec resolved = resolveByTyptype(pgType);
      if (resolved != null) {
        oidCache.put(oid, resolved);
        return resolved;
      }
    }

    // Fallback for unknown types
    return FallbackCodec.INSTANCE;
  }

  /**
   * Gets the codec for a specific Java class.
   *
   * @param javaClass the Java class
   * @return the codec, or null if not registered
   */
  public @Nullable Codec getByClass(Class<?> javaClass) {
    return codecsByClass.get(javaClass);
  }

  /**
   * Finds a codec that can handle the given Java class.
   *
   * <p>First checks for an exact match. If not found, checks superclasses
   * and interfaces. This supports polymorphic encoding (e.g., any SQLData
   * implementation).</p>
   *
   * @param javaClass the Java class to find a codec for
   * @return a codec that can handle the class, or null if none found
   */
  public @Nullable Codec findCodecFor(Class<?> javaClass) {
    // Exact match
    Codec codec = codecsByClass.get(javaClass);
    if (codec != null) {
      return codec;
    }

    // Check superclass hierarchy
    Class<?> current = javaClass.getSuperclass();
    while (current != null && current != Object.class) {
      codec = codecsByClass.get(current);
      if (codec != null) {
        return codec;
      }
      current = current.getSuperclass();
    }

    // Check interfaces
    for (Class<?> iface : javaClass.getInterfaces()) {
      codec = codecsByClass.get(iface);
      if (codec != null) {
        return codec;
      }
    }

    return null;
  }

  /**
   * Resolves a codec based on the type's typtype and typcategory.
   * Used for user-defined types where no explicit name-based codec is registered.
   *
   * @param pgType the type information
   * @return the resolved codec, or null if no resolution is possible
   */
  private static @Nullable Codec resolveByTyptype(PgType pgType) {
    // Array types (typcategory='A')
    if (pgType.isArray()) {
      return ArrayCodec.INSTANCE;
    }
    // Composite types (typtype='c')
    if (pgType.isComposite()) {
      return CompositeCodec.INSTANCE;
    }
    // Domain types (typtype='d')
    if (pgType.isDomain()) {
      return DomainCodec.INSTANCE;
    }
    // Enum types (typtype='e')
    if (pgType.isEnum()) {
      return EnumCodec.INSTANCE;
    }
    // Range types (typtype='r')
    if (pgType.getTyptype() == 'r') {
      return RangeCodec.INSTANCE;
    }
    return null;
  }

  /**
   * Gets the binary codec for a specific OID.
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information
   * @return the binary codec, or null if the codec doesn't support binary
   */
  public @Nullable BinaryCodec getBinaryCodec(int oid, @Nullable PgType pgType) {
    Codec codec = getByOid(oid, pgType);
    return codec instanceof BinaryCodec ? (BinaryCodec) codec : null;
  }

  /**
   * Gets the text codec for a specific OID.
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information
   * @return the text codec, or null if the codec doesn't support text
   */
  public @Nullable TextCodec getTextCodec(int oid, @Nullable PgType pgType) {
    Codec codec = getByOid(oid, pgType);
    return codec instanceof TextCodec ? (TextCodec) codec : null;
  }

  /**
   * Gets the binary codec for a specific OID.
   *
   * <p>Convenience method that returns FallbackCodec if no binary codec is found.</p>
   *
   * @param oid the PostgreSQL type OID
   * @return the binary codec (never null)
   */
  public BinaryCodec getBinaryCodec(int oid) {
    Codec codec = getByOid(oid, null);
    return codec instanceof BinaryCodec ? (BinaryCodec) codec : FallbackCodec.INSTANCE;
  }

  /**
   * Gets the text codec for a specific OID.
   *
   * <p>Convenience method that returns FallbackCodec if no text codec is found.</p>
   *
   * @param oid the PostgreSQL type OID
   * @return the text codec (never null)
   */
  public TextCodec getTextCodec(int oid) {
    Codec codec = getByOid(oid, null);
    return codec instanceof TextCodec ? (TextCodec) codec : FallbackCodec.INSTANCE;
  }

  /**
   * Invalidates the OID cache.
   *
   * <p>Call this when type mappings may have changed (e.g., after DDL operations
   * that create/drop types).</p>
   */
  public void invalidateCache() {
    oidCache.invalidateAll();
  }

  /**
   * Invalidates a specific OID from the cache.
   *
   * @param oid the OID to invalidate
   */
  public void invalidateOid(int oid) {
    oidCache.invalidate(oid);
    explicitOidCodecs.remove(oid);
  }

  /**
   * Checks if a codec is registered for the given type name.
   *
   * @param typeName the PostgreSQL type name
   * @return true if a codec is registered
   */
  public boolean hasCodecForName(String typeName) {
    return codecsByName.containsKey(typeName) || spiCodecs.containsKey(typeName);
  }

  /**
   * Checks if a codec is registered for the given Java class.
   *
   * @param javaClass the Java class
   * @return true if a codec is registered
   */
  public boolean hasCodecForClass(Class<?> javaClass) {
    return codecsByClass.containsKey(javaClass);
  }
}
