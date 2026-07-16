/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.CodecLookup;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.codec.ArrayCodec;
import org.postgresql.jdbc.codec.BitCodec;
import org.postgresql.jdbc.codec.BoolCodec;
import org.postgresql.jdbc.codec.BoxCodec;
import org.postgresql.jdbc.codec.BpcharCodec;
import org.postgresql.jdbc.codec.ByteaCodec;
import org.postgresql.jdbc.codec.CharCodec;
import org.postgresql.jdbc.codec.CircleCodec;
import org.postgresql.jdbc.codec.CompositeCodec;
import org.postgresql.jdbc.codec.DateCodec;
import org.postgresql.jdbc.codec.DomainCodec;
import org.postgresql.jdbc.codec.EnumCodec;
import org.postgresql.jdbc.codec.FallbackCodec;
import org.postgresql.jdbc.codec.Float4Codec;
import org.postgresql.jdbc.codec.Float8Codec;
import org.postgresql.jdbc.codec.HstoreCodec;
import org.postgresql.jdbc.codec.Int2Codec;
import org.postgresql.jdbc.codec.Int4Codec;
import org.postgresql.jdbc.codec.Int8Codec;
import org.postgresql.jdbc.codec.IntervalCodec;
import org.postgresql.jdbc.codec.JsonCodec;
import org.postgresql.jdbc.codec.JsonbCodec;
import org.postgresql.jdbc.codec.LineCodec;
import org.postgresql.jdbc.codec.LsegCodec;
import org.postgresql.jdbc.codec.MoneyCodec;
import org.postgresql.jdbc.codec.MultirangeCodec;
import org.postgresql.jdbc.codec.NameCodec;
import org.postgresql.jdbc.codec.NumericCodec;
import org.postgresql.jdbc.codec.Oid8Codec;
import org.postgresql.jdbc.codec.OidCodec;
import org.postgresql.jdbc.codec.PathCodec;
import org.postgresql.jdbc.codec.PointCodec;
import org.postgresql.jdbc.codec.PolygonCodec;
import org.postgresql.jdbc.codec.RangeCodec;
import org.postgresql.jdbc.codec.TextCodec;
import org.postgresql.jdbc.codec.TextLikeCodec;
import org.postgresql.jdbc.codec.TimeCodec;
import org.postgresql.jdbc.codec.TimestampCodec;
import org.postgresql.jdbc.codec.TimestamptzCodec;
import org.postgresql.jdbc.codec.TimetzCodec;
import org.postgresql.jdbc.codec.UuidCodec;
import org.postgresql.jdbc.codec.VarcharCodec;
import org.postgresql.jdbc.codec.Xid8Codec;
import org.postgresql.jdbc.codec.XmlCodec;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry for codec instances.
 *
 * <p>Manages codec lookup by OID, type name, and Java class. This registry is
 * connection-scoped to allow per-connection codec customization.</p>
 *
 * <h2>Codec Resolution Order</h2>
 *
 * <p>A type's codec is resolved by OID first and by name second, with explicit
 * layering so a custom or service-loaded codec never silently shadows a built-in
 * type. {@link #getByOid(int, TypeDescriptor)} consults, in order:</p>
 * <ol>
 *   <li>a per-connection codec bound to the exact OID ({@link #registerByOid});</li>
 *   <li>a per-connection codec registered by name ({@link #registerByName} /
 *       {@link #registerCustomCodec}), matched by {@code (namespace, name)} and
 *       then by bare name;</li>
 *   <li>a built-in codec bound to the type's canonical OID — the primary identity
 *       for built-in scalar types, so a same-named user or service-loaded codec
 *       cannot grab, say, {@code pg_catalog.point} across every schema;</li>
 *   <li>a {@link ServiceLoader}-provided codec, matched by name;</li>
 *   <li>a built-in codec matched by name (aliases, and types without a pinned OID
 *       such as {@code hstore});</li>
 *   <li>a built-in container codec selected from {@code typtype}/{@code typcategory}
 *       (array, composite, domain, enum, range);</li>
 *   <li>the {@link FallbackCodec} for unknown types.</li>
 * </ol>
 *
 * <p>Name resolution is layered <em>user &gt; SPI &gt; built-in</em>, while the OID
 * identity of a built-in type takes precedence over a service-loaded name match.
 * Name lookups are keyed by {@code (namespace, name)}: a codec registered with a
 * bare name still matches a type in any schema, but a built-in type is reached by
 * its OID before any bare-name service-loaded match, which removes the cross-schema
 * shadowing the old bare-name map allowed.</p>
 *
 * <h2>Caching</h2>
 *
 * <p>OID → Codec lookups are cached using Caffeine for performance.
 * The cache is size-bounded (default 1000 entries) with LRU eviction.</p>
 *
 * <h2>Registration Timing</h2>
 *
 * <p>A column's codec is resolved on first read and cached for that result set only;
 * {@link PgResultSet} resolves it through this registry. Registering a codec updates the registry's
 * cached resolution, so the next execution -- a fresh result set -- sees the new codec, while a
 * {@link java.sql.ResultSet} that is already open keeps the codecs it has resolved.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public class CodecRegistry implements CodecLookup {

  private static final Logger LOGGER = Logger.getLogger(CodecRegistry.class.getName());

  /** Maximum number of OID → Codec mappings to cache. */
  private static final int OID_CACHE_SIZE = 1000;

  /** Namespace of built-in PostgreSQL types. */
  private static final String PG_CATALOG = "pg_catalog";

  // ---- User layer (per-connection, highest precedence) --------------------

  // User OID registrations: an explicit binding for an exact OID.
  private final Map<Integer, Codec> userOidCodecs = new ConcurrentHashMap<>();

  // User name registrations (registerByName / registerAlias / registerCustomCodec).
  private final Map<NameKey, Codec> userCodecsByName = new ConcurrentHashMap<>();

  // ---- Built-in layer (driver-provided, lowest precedence) ----------------

  // Built-in codecs bound to their canonical OID: the primary identity for
  // built-in scalar types (int4 → 23, point → 600, ...).
  private final Map<Integer, Codec> builtinCodecsByOid = new ConcurrentHashMap<>();

  // Built-in codecs and aliases by name; the fallback for types without a pinned
  // OID (such as hstore) and for bare-name lookups.
  private final Map<NameKey, Codec> builtinCodecsByName = new ConcurrentHashMap<>();

  // ---- Cross-layer maps ---------------------------------------------------

  // OID -> codec cache (Caffeine LRU cache).
  private final Cache<Integer, Codec> oidCache;

  // Track custom codec names for reset functionality.
  private final Set<NameKey> customCodecNames = ConcurrentHashMap.newKeySet();

  // ---- SPI layer (driver-scoped, shared across registries) ----------------

  private static volatile boolean spiLoaded = false;
  private static final Map<NameKey, Codec> spiCodecsByName = new ConcurrentHashMap<>();

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
    registerBuiltinOids();
    registerSpiCodecs();
  }

  /**
   * Loads codecs via {@link ServiceLoader} (SPI).
   *
   * <p>This is called once per driver initialization. Codecs are loaded with an
   * explicit class loader (the thread context loader, falling back to this
   * class's loader), and any provider that fails to load is logged and skipped
   * rather than aborting the rest of the SPI scan.</p>
   */
  private static synchronized void loadSpiCodecs() {
    if (spiLoaded) {
      return;
    }
    spiLoaded = true;

    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = CodecRegistry.class.getClassLoader();
    }

    try {
      ServiceLoader<Codec> serviceLoader = ServiceLoader.load(Codec.class, loader);
      Iterator<Codec> it = serviceLoader.iterator();
      while (it.hasNext()) {
        Codec codec;
        try {
          codec = it.next();
        } catch (ServiceConfigurationError | RuntimeException e) {
          // A single bad provider must not break the whole scan. Previously the
          // error was swallowed; surface it so misconfigured codecs are diagnosable.
          LOGGER.log(Level.WARNING, "Failed to load a codec via ServiceLoader; skipping it", e);
          continue;
        }
        NameKey key = new NameKey(null, codec.getPrimaryTypeName());
        Codec previous = spiCodecsByName.putIfAbsent(key, codec);
        if (previous != null) {
          LOGGER.log(Level.WARNING,
              "Duplicate service-loaded codec for type name {0}: keeping {1}, ignoring {2}",
              new Object[]{key, previous.getClass().getName(), codec.getClass().getName()});
        }
      }
    } catch (ServiceConfigurationError | RuntimeException e) {
      // ServiceLoader failed - continue with built-in codecs only.
      LOGGER.log(Level.WARNING,
          "Codec ServiceLoader failed; continuing with built-in codecs only", e);
    }
  }

  /**
   * Registers all built-in codecs by name and Java class.
   */
  private void registerBuiltinCodecs() {
    // Numeric types
    registerBuiltin(Int2Codec.INSTANCE);
    registerBuiltin(Int4Codec.INSTANCE);
    registerBuiltin(Int8Codec.INSTANCE);
    registerBuiltin(Float4Codec.INSTANCE);
    registerBuiltin(Float8Codec.INSTANCE);
    registerBuiltin(NumericCodec.INSTANCE);
    registerBuiltin(MoneyCodec.INSTANCE);
    registerBuiltin(OidCodec.INSTANCE);
    registerBuiltin(Oid8Codec.INSTANCE);
    registerBuiltin(Xid8Codec.INSTANCE);

    // String types
    registerBuiltin(TextCodec.INSTANCE);
    registerBuiltin(VarcharCodec.INSTANCE);
    registerBuiltin(BpcharCodec.INSTANCE);
    registerBuiltin(CharCodec.INSTANCE);
    registerBuiltin(NameCodec.INSTANCE);
    registerBuiltin(BoolCodec.INSTANCE);

    // Date/time types
    registerBuiltin(DateCodec.INSTANCE);
    registerBuiltin(TimeCodec.INSTANCE);
    registerBuiltin(TimetzCodec.INSTANCE);
    registerBuiltin(TimestampCodec.INSTANCE);
    registerBuiltin(TimestamptzCodec.INSTANCE);
    registerBuiltin(IntervalCodec.INSTANCE);

    // Binary types
    registerBuiltin(ByteaCodec.INSTANCE);
    registerBuiltin(UuidCodec.INSTANCE);

    // Bit string types
    registerBuiltin(BitCodec.INSTANCE);
    registerBuiltinAlias("bit varying", BitCodec.INSTANCE);
    registerBuiltinAlias("varbit", BitCodec.INSTANCE);

    // JSON/XML types
    registerBuiltin(JsonCodec.INSTANCE);
    registerBuiltin(JsonbCodec.INSTANCE);
    registerBuiltin(XmlCodec.INSTANCE);

    // Composite types
    registerBuiltin(ArrayCodec.INSTANCE);
    registerBuiltin(CompositeCodec.INSTANCE);
    registerBuiltin(DomainCodec.INSTANCE);

    // Range types
    registerBuiltin(RangeCodec.INSTANCE);

    // Multirange types (PostgreSQL 14+)
    registerBuiltin(MultirangeCodec.INSTANCE);

    // Extension types (built-in support). hstore has an installation-dependent OID
    // and lives in a user schema, so it is registered by bare name rather than
    // pinned by OID or qualified with pg_catalog.
    registerBuiltinExtension(HstoreCodec.INSTANCE);

    // Geometric types
    registerBuiltin(PointCodec.INSTANCE);
    registerBuiltin(BoxCodec.INSTANCE);
    registerBuiltin(CircleCodec.INSTANCE);
    registerBuiltin(LineCodec.INSTANCE);
    registerBuiltin(LsegCodec.INSTANCE);
    registerBuiltin(PathCodec.INSTANCE);
    registerBuiltin(PolygonCodec.INSTANCE);

    // Type aliases
    registerBuiltinAlias("int2", Int2Codec.INSTANCE);
    registerBuiltinAlias("smallint", Int2Codec.INSTANCE);
    registerBuiltinAlias("int4", Int4Codec.INSTANCE);
    registerBuiltinAlias("integer", Int4Codec.INSTANCE);
    registerBuiltinAlias("int", Int4Codec.INSTANCE);
    registerBuiltinAlias("serial", Int4Codec.INSTANCE);
    registerBuiltinAlias("int8", Int8Codec.INSTANCE);
    registerBuiltinAlias("bigint", Int8Codec.INSTANCE);
    registerBuiltinAlias("bigserial", Int8Codec.INSTANCE);
    registerBuiltinAlias("float4", Float4Codec.INSTANCE);
    registerBuiltinAlias("real", Float4Codec.INSTANCE);
    registerBuiltinAlias("float8", Float8Codec.INSTANCE);
    registerBuiltinAlias("double precision", Float8Codec.INSTANCE);
    registerBuiltinAlias("numeric", NumericCodec.INSTANCE);
    registerBuiltinAlias("decimal", NumericCodec.INSTANCE);
    registerBuiltinAlias("varchar", VarcharCodec.INSTANCE);
    registerBuiltinAlias("character varying", VarcharCodec.INSTANCE);
    registerBuiltinAlias("bpchar", BpcharCodec.INSTANCE);
    registerBuiltinAlias("character", BpcharCodec.INSTANCE);
    // "char" is the internal single-byte type (OID 18), not character(n)/bpchar.
    registerBuiltinAlias("char", CharCodec.INSTANCE);
    registerBuiltinAlias("bool", BoolCodec.INSTANCE);
    registerBuiltinAlias("boolean", BoolCodec.INSTANCE);
    registerBuiltinAlias("timetz", TimetzCodec.INSTANCE);
    registerBuiltinAlias("time with time zone", TimetzCodec.INSTANCE);
    registerBuiltinAlias("time without time zone", TimeCodec.INSTANCE);
    registerBuiltinAlias("timestamptz", TimestamptzCodec.INSTANCE);
    registerBuiltinAlias("timestamp with time zone", TimestamptzCodec.INSTANCE);
    registerBuiltinAlias("timestamp without time zone", TimestampCodec.INSTANCE);

    // Range type aliases
    registerBuiltinAlias("int4range", RangeCodec.INSTANCE);
    registerBuiltinAlias("int8range", RangeCodec.INSTANCE);
    registerBuiltinAlias("numrange", RangeCodec.INSTANCE);
    registerBuiltinAlias("tsrange", RangeCodec.INSTANCE);
    registerBuiltinAlias("tstzrange", RangeCodec.INSTANCE);
    registerBuiltinAlias("daterange", RangeCodec.INSTANCE);

    // Multirange type aliases (PostgreSQL 14+)
    registerBuiltinAlias("int4multirange", MultirangeCodec.INSTANCE);
    registerBuiltinAlias("int8multirange", MultirangeCodec.INSTANCE);
    registerBuiltinAlias("nummultirange", MultirangeCodec.INSTANCE);
    registerBuiltinAlias("tsmultirange", MultirangeCodec.INSTANCE);
    registerBuiltinAlias("tstzmultirange", MultirangeCodec.INSTANCE);
    registerBuiltinAlias("datemultirange", MultirangeCodec.INSTANCE);
  }

  /**
   * Pins built-in scalar codecs to their canonical OID.
   *
   * <p>This is the primary identity for built-in types: resolution matches a
   * pinned OID before any service-loaded name, so a third-party codec named, say,
   * {@code point} cannot shadow the built-in {@code pg_catalog.point} (OID 600).
   * Types whose OID is installation-dependent (for example {@code hstore}) are not
   * pinned and resolve by name instead.</p>
   */
  private void registerBuiltinOids() {
    // Numeric types
    pinBuiltinOid(Oid.INT2, Int2Codec.INSTANCE);
    pinBuiltinOid(Oid.INT4, Int4Codec.INSTANCE);
    pinBuiltinOid(Oid.INT8, Int8Codec.INSTANCE);
    pinBuiltinOid(Oid.FLOAT4, Float4Codec.INSTANCE);
    pinBuiltinOid(Oid.FLOAT8, Float8Codec.INSTANCE);
    pinBuiltinOid(Oid.NUMERIC, NumericCodec.INSTANCE);
    pinBuiltinOid(Oid.MONEY, MoneyCodec.INSTANCE);
    pinBuiltinOid(Oid.OID, OidCodec.INSTANCE);
    pinBuiltinOid(Oid.OID8, Oid8Codec.INSTANCE);
    pinBuiltinOid(Oid.XID8, Xid8Codec.INSTANCE);

    // String types
    pinBuiltinOid(Oid.TEXT, TextCodec.INSTANCE);
    pinBuiltinOid(Oid.VARCHAR, VarcharCodec.INSTANCE);
    pinBuiltinOid(Oid.BPCHAR, BpcharCodec.INSTANCE);
    pinBuiltinOid(Oid.CHAR, CharCodec.INSTANCE);
    pinBuiltinOid(Oid.NAME, NameCodec.INSTANCE);
    pinBuiltinOid(Oid.BOOL, BoolCodec.INSTANCE);

    // Date/time types
    pinBuiltinOid(Oid.DATE, DateCodec.INSTANCE);
    pinBuiltinOid(Oid.TIME, TimeCodec.INSTANCE);
    pinBuiltinOid(Oid.TIMETZ, TimetzCodec.INSTANCE);
    pinBuiltinOid(Oid.TIMESTAMP, TimestampCodec.INSTANCE);
    pinBuiltinOid(Oid.TIMESTAMPTZ, TimestamptzCodec.INSTANCE);
    pinBuiltinOid(Oid.INTERVAL, IntervalCodec.INSTANCE);

    // Binary types
    pinBuiltinOid(Oid.BYTEA, ByteaCodec.INSTANCE);
    pinBuiltinOid(Oid.UUID, UuidCodec.INSTANCE);

    // Bit string types
    pinBuiltinOid(Oid.BIT, BitCodec.INSTANCE);
    pinBuiltinOid(Oid.VARBIT, BitCodec.INSTANCE);

    // JSON/XML types
    pinBuiltinOid(Oid.JSON, JsonCodec.INSTANCE);
    pinBuiltinOid(Oid.JSONB, JsonbCodec.INSTANCE);
    pinBuiltinOid(Oid.XML, XmlCodec.INSTANCE);

    // Geometric types
    pinBuiltinOid(Oid.POINT, PointCodec.INSTANCE);
    pinBuiltinOid(Oid.BOX, BoxCodec.INSTANCE);
    pinBuiltinOid(Oid.CIRCLE, CircleCodec.INSTANCE);
    pinBuiltinOid(Oid.LINE, LineCodec.INSTANCE);
    pinBuiltinOid(Oid.LSEG, LsegCodec.INSTANCE);
    pinBuiltinOid(Oid.PATH, PathCodec.INSTANCE);
    pinBuiltinOid(Oid.POLYGON, PolygonCodec.INSTANCE);
  }

  /**
   * Read-only view of the built-in codecs pinned by their canonical OID -- the {@code int4 -> 23},
   * {@code point -> 600}, ... bindings set up in {@link #registerBuiltinOids()}.
   *
   * <p>The returned map is a snapshot copy ordered by OID. It carries only the pinned built-in
   * codecs: user and service-loaded registrations, the name-only built-ins (such as {@code hstore}),
   * and the unpinned {@link org.postgresql.jdbc.codec.FallbackCodec} are all absent. Intended for
   * tests and tooling that enumerate the built-in codecs -- for example a capability-coverage guard
   * that checks every codec advertising an optional capability is reachable by OID.</p>
   *
   * @return an unmodifiable {@code OID -> codec} snapshot of the pinned built-in codecs
   * @since 42.8.0
   */
  @Override
  public Map<Integer, Codec> builtinCodecsByOid() {
    return Collections.unmodifiableMap(new TreeMap<>(builtinCodecsByOid));
  }

  /**
   * Logs diagnostics for service-loaded codecs that collide with a built-in.
   *
   * <p>Service-loaded codecs are kept in their own layer (see the class-level
   * resolution order). A service-loaded codec that shares a name with a built-in
   * is logged: built-in types still resolve by OID, so the service-loaded codec
   * applies only to non-built-in types of that name.</p>
   */
  private void registerSpiCodecs() {
    for (Map.Entry<NameKey, Codec> entry : spiCodecsByName.entrySet()) {
      Codec codec = entry.getValue();
      if (hasBuiltinName(codec.getPrimaryTypeName())) {
        LOGGER.log(Level.FINE,
            "Service-loaded codec {0} shares type name {1} with a built-in codec; built-in types "
                + "resolve by OID, so the service-loaded codec applies only to non-built-in types "
                + "of that name",
            new Object[]{codec.getClass().getName(), codec.getPrimaryTypeName()});
      }
    }
  }

  /**
   * Registers a codec by its type name.
   *
   * <p>This is a per-connection (user-layer) registration: it takes precedence
   * over service-loaded and built-in codecs of the same name. It does not affect a result set that
   * has already resolved its codecs; see {@link #getByOid(int, TypeDescriptor)}.</p>
   *
   * @param codec the codec to register
   */
  public void registerByName(Codec codec) {
    putUserName(new NameKey(null, codec.getPrimaryTypeName()), codec);
  }

  /**
   * Registers an alias for a codec.
   *
   * <p>This is a per-connection (user-layer) registration; see {@link #registerByName(Codec)}.</p>
   *
   * @param alias the alias name
   * @param codec the codec
   */
  public void registerAlias(String alias, Codec codec) {
    putUserName(new NameKey(null, alias), codec);
  }

  private void putUserName(NameKey key, Codec codec) {
    if (LOGGER.isLoggable(Level.FINE)
        && (hasBuiltinName(key.name) || spiCodecsByName.containsKey(key))) {
      LOGGER.log(Level.FINE, "User codec {0} overrides an existing codec for type name {1}",
          new Object[]{codec.getClass().getName(), key});
    }
    userCodecsByName.put(key, codec);
    // A prior getByOid() may have cached a codec (or a typtype-resolved default) for an OID that
    // resolves to this name, so drop the OID cache to keep it coherent with the new mapping.
    oidCache.invalidateAll();
  }

  /**
   * Registers a codec for a specific OID.
   *
   * <p>This creates an explicit per-connection binding that overrides every other
   * layer for that OID.</p>
   *
   * @param oid the PostgreSQL type OID
   * @param codec the codec to register
   */
  public void registerByOid(int oid, Codec codec) {
    userOidCodecs.put(oid, codec);
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
    NameKey key = new NameKey(null, codec.getPrimaryTypeName());
    customCodecNames.add(key);
    putUserName(key, codec);
  }

  /**
   * Unregisters a custom codec by type name.
   *
   * @param typeName the type name to unregister
   */
  public void unregisterCustomCodec(String typeName) {
    NameKey key = new NameKey(null, typeName);
    if (customCodecNames.remove(key)) {
      userCodecsByName.remove(key);
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
    for (NameKey key : customCodecNames) {
      userCodecsByName.remove(key);
    }
    customCodecNames.clear();
    userOidCodecs.clear();
    oidCache.invalidateAll();
  }

  /**
   * Gets the codec for a specific type name.
   *
   * <p>The bare name is resolved across the user, service-loaded and built-in
   * layers in that order.</p>
   *
   * @param typeName the PostgreSQL type name
   * @return the codec, or null if not found
   */
  @Override
  public @Nullable Codec getByName(String typeName) {
    NameKey key = new NameKey(null, typeName);
    Codec codec = userCodecsByName.get(key);
    if (codec != null) {
      return codec;
    }
    codec = spiCodecsByName.get(key);
    if (codec != null) {
      return codec;
    }
    codec = builtinCodecsByName.get(key);
    if (codec != null) {
      return codec;
    }
    // Built-in pg_catalog types are keyed by (pg_catalog, name); accept the bare
    // name here as a convenience.
    return builtinCodecsByName.get(new NameKey(PG_CATALOG, typeName));
  }

  /**
   * Gets the codec for a specific OID, using type information for resolution.
   *
   * <p>This method uses Caffeine caching for performance. The full resolution
   * order is documented on the {@linkplain CodecRegistry class}.</p>
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information (may be null for cache-only lookup)
   * @return the codec (never null - returns FallbackCodec for unknown types)
   */
  @Override
  public Codec getByOid(int oid, @Nullable TypeDescriptor pgType) {
    // 1. User OID registration: an explicit per-connection binding wins outright.
    Codec userOid = userOidCodecs.get(oid);
    if (userOid != null) {
      return userOid;
    }

    // 2. Memoized resolution.
    Codec cached = oidCache.getIfPresent(oid);
    if (cached != null) {
      return cached;
    }

    // 3. User name registration (user layer beats SPI and built-in).
    if (pgType != null) {
      Codec userName = lookupByName(userCodecsByName, pgType);
      if (userName != null) {
        oidCache.put(oid, userName);
        return userName;
      }
    }

    // 4. Built-in by canonical OID: the primary identity, so a same-named SPI
    // codec cannot shadow a built-in type.
    Codec builtinOid = builtinCodecsByOid.get(oid);
    if (builtinOid != null) {
      oidCache.put(oid, builtinOid);
      return builtinOid;
    }

    if (pgType != null) {
      // 5. Service-loaded codec by name.
      Codec spi = lookupByName(spiCodecsByName, pgType);
      if (spi != null) {
        oidCache.put(oid, spi);
        return spi;
      }

      // 6. Built-in by name (aliases, and types without a pinned OID such as hstore).
      Codec builtinName = lookupByName(builtinCodecsByName, pgType);
      if (builtinName != null) {
        oidCache.put(oid, builtinName);
        return builtinName;
      }

      // 7. Built-in container codec from typtype/typcategory.
      Codec resolved = resolveByTyptype(pgType);
      if (resolved != null) {
        oidCache.put(oid, resolved);
        return resolved;
      }

      // 8. Text-like send: a codec-less type whose server typsend emits raw charset text decodes as
      // text into a PGobject, so even a field nested in a binary record reads as a readable PGobject
      // rather than PGUnknownBinary.
      Codec textLike = resolveByTextLikeSend(pgType);
      if (textLike != null) {
        oidCache.put(oid, textLike);
        return textLike;
      }
    }

    // 9. Fallback for unknown types.
    return FallbackCodec.INSTANCE;
  }

  /**
   * Resolves a codec by {@code (namespace, name)} within a single layer, falling
   * back to a bare-name match so a codec registered without a namespace still
   * applies to a type in any schema.
   */
  private static @Nullable Codec lookupByName(Map<NameKey, Codec> layer, TypeDescriptor pgType) {
    String namespace = pgType.getTypeName().getNamespace();
    String name = pgType.getTypeName().getName();
    if (namespace != null) {
      Codec exact = layer.get(new NameKey(namespace, name));
      if (exact != null) {
        return exact;
      }
    }
    return layer.get(new NameKey(null, name));
  }

  /**
   * Resolves a codec based on the type's typtype and typcategory.
   * Used for user-defined types where no explicit name-based codec is registered.
   *
   * @param pgType the type information
   * @return the resolved codec, or null if no resolution is possible
   */
  private static @Nullable Codec resolveByTyptype(TypeDescriptor pgType) {
    // Domains are checked first: a domain over an array (CREATE DOMAIN d AS int[]) inherits
    // typcategory='A' from its base type but has typelem=0, so the typcategory-based isArray()
    // check below would otherwise claim it and ArrayCodec would decode every value as null.
    // typtype is the authoritative discriminator, and DomainCodec unwraps to the base codec.
    if (pgType.isDomain()) {
      return DomainCodec.INSTANCE;
    }
    // Array types (typcategory='A')
    if (pgType.isArray()) {
      return ArrayCodec.INSTANCE;
    }
    // Composite types (typtype='c')
    if (pgType.isComposite()) {
      return CompositeCodec.INSTANCE;
    }
    // Enum types (typtype='e')
    if (pgType.isEnum()) {
      return EnumCodec.INSTANCE;
    }
    // Range types (typtype='r')
    if (pgType.getTyptype() == 'r') {
      return RangeCodec.INSTANCE;
    }
    // Multirange types (typtype='m', PostgreSQL 14+)
    if (pgType.isMultirange()) {
      return MultirangeCodec.INSTANCE;
    }
    return null;
  }

  /**
   * Resolves a codec-less type whose server {@code typsend} emits raw charset text
   * ({@code textsend}/{@code varcharsend}/{@code bpcharsend}/{@code namesend}) to
   * {@link TextLikeCodec}. The binary wire of such a type is the charset text, so it decodes the
   * same in binary and text into a {@code PGobject} — unlike {@link FallbackCodec}, which would
   * surface a binary value (e.g. a field in a binary {@code record}) as {@code PGUnknownBinary}.
   *
   * <p>The {@code typsend} identity lives on {@link PgType}, not the {@link TypeDescriptor} SPI, so
   * this only applies when the descriptor is a {@code PgType}; an offline descriptor of another
   * implementation falls through to {@link FallbackCodec}.</p>
   *
   * @param pgType the type information
   * @return {@link TextLikeCodec#INSTANCE}, or null when the type is not a text-send {@code PgType}
   */
  private static @Nullable Codec resolveByTextLikeSend(TypeDescriptor pgType) {
    return pgType instanceof PgType && ((PgType) pgType).hasTextLikeSend()
        ? TextLikeCodec.INSTANCE
        : null;
  }

  /**
   * Gets the binary codec for a specific OID.
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information
   * @return the binary codec, or null if the codec doesn't support binary
   */
  @Override
  public @Nullable BinaryCodec getBinaryCodec(int oid, @Nullable TypeDescriptor pgType) {
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
  @Override
  public org.postgresql.api.codec.@Nullable TextCodec getTextCodec(int oid, @Nullable TypeDescriptor pgType) {
    Codec codec = getByOid(oid, pgType);
    return codec instanceof org.postgresql.api.codec.TextCodec ? (org.postgresql.api.codec.TextCodec) codec : null;
  }

  /**
   * Whether the driver can decode this type from the binary wire format.
   *
   * <p>This resolves the codec and asks its read-side capability
   * ({@link BinaryCodec#decodesBinary()}) rather than testing {@code instanceof}
   * alone, so a codec that implements {@link BinaryCodec} only for the encode direction
   * is not mistaken for a binary decoder. The format-negotiation layer gates binary
   * receive on this.</p>
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information
   * @return true if the resolved codec can decode the binary representation
   */
  @Override
  public boolean canDecodeBinary(int oid, @Nullable TypeDescriptor pgType) {
    return CodecFormatSupport.canReadBinary(getByOid(oid, pgType));
  }

  /**
   * Whether the driver can decode this type from the text wire format.
   *
   * <p>The text counterpart to {@link #canDecodeBinary(int, TypeDescriptor)}, consulting
   * {@link org.postgresql.api.codec.TextCodec#decodesText()}. Text is the universal receive format, so this is
   * true for almost every type; it is exposed for callers (offline and {@code COPY}) that
   * choose a format from codec capability rather than format negotiation.</p>
   *
   * @param oid the PostgreSQL type OID
   * @param pgType the type information
   * @return true if the resolved codec can decode the text representation
   */
  @Override
  public boolean canDecodeText(int oid, @Nullable TypeDescriptor pgType) {
    return CodecFormatSupport.canReadText(getByOid(oid, pgType));
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
    userOidCodecs.remove(oid);
  }

  /**
   * Registers a built-in {@code pg_catalog} codec by its type name.
   */
  private void registerBuiltin(Codec codec) {
    builtinCodecsByName.put(new NameKey(PG_CATALOG, codec.getPrimaryTypeName()), codec);
  }

  /**
   * Registers a built-in {@code pg_catalog} alias for a codec.
   */
  private void registerBuiltinAlias(String alias, Codec codec) {
    builtinCodecsByName.put(new NameKey(PG_CATALOG, alias), codec);
  }

  /**
   * Registers a built-in codec for an extension type whose schema and OID are
   * installation-dependent (for example {@code hstore}). It is keyed by bare name,
   * so it matches the type in whatever schema the extension was installed.
   */
  private void registerBuiltinExtension(Codec codec) {
    builtinCodecsByName.put(new NameKey(null, codec.getPrimaryTypeName()), codec);
  }

  /**
   * Pins a built-in codec to a canonical OID.
   */
  private void pinBuiltinOid(int oid, Codec codec) {
    builtinCodecsByOid.put(oid, codec);
  }

  /**
   * Whether a built-in codec is registered under the given bare type name, either
   * as a {@code pg_catalog} type or as a bare-name extension type.
   */
  private boolean hasBuiltinName(String name) {
    return builtinCodecsByName.containsKey(new NameKey(PG_CATALOG, name))
        || builtinCodecsByName.containsKey(new NameKey(null, name));
  }

  /**
   * A codec registry key: an optional namespace (schema) plus a type name. A
   * {@code null} namespace is an unqualified key that matches a type in any
   * schema during the bare-name fallback of {@link #lookupByName}.
   */
  private static final class NameKey {
    final @Nullable String namespace;
    final String name;

    NameKey(@Nullable String namespace, String name) {
      this.namespace = namespace;
      this.name = name;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof NameKey)) {
        return false;
      }
      NameKey that = (NameKey) o;
      return Objects.equals(namespace, that.namespace) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(namespace) * 31 + name.hashCode();
    }

    @Override
    public String toString() {
      return namespace == null ? name : namespace + "." + name;
    }
  }
}
