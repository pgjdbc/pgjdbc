# PgType Design Specification v2.2

## Overview

Refactoring of the pgjdbc type system to:
1. Support structs, arrays, arrays of structs
2. Provide clean separation between PostgreSQL type metadata and Java class mappings
3. **Minimize code duplication** between PgResultSet, PgArray, SQLInput, SQLOutput, and other components
4. Support JDBC 4.2+ APIs including `java.sql.SQLType`
5. Full ORM compatibility (Hibernate, jOOQ, MyBatis)

**Minimum PostgreSQL version:** 9.1 (tested on 9.1 through 16+ in CI)

## Design Decisions Summary

| Aspect | Decision                                                                              |
|--------|---------------------------------------------------------------------------------------|
| Conversion approach | Streaming (pull) API like kotlinx-serialization                                       |
| Codec organization | Separate file per codec (Int4Codec.java, TextCodec.java, etc.)                        |
| Binary/Text codecs | Separate interfaces: BinaryCodec, TextCodec                                           |
| Codec state | Stateless codecs (singletons), connection settings in CodecContext                    |
| CodecContext state | Immutable, use `withTypeMap()` for per-call customization                             |
| Nesting depth tracking | ThreadLocal counter (CodecDepth), shared for encode/decode                            |
| Codec registration | SPI (ServiceLoader) per-Driver scope + programmatic API via `PGConnection`            |
| Codec identification | By type name (OID resolved at connection time)                                        |
| Schema conflicts | Last registered wins (schema not considered)                                          |
| Primitive optimization | Decode: specialized methods (decodeAsInt, decodeAsLong) with default fallback. Encode: typed setters use ByteConverter directly (no boxing) |
| Object types | Generic decode(bytes, targetClass, ctx)                                               |
| Type coercion | Codec handles all conversions (e.g., NumericCodec.decodeAsInt())                      |
| Overflow handling | SQLException on overflow (as in PgResultSet.readLongValue())                          |
| Array handling | ArrayCodec composition with elementCodec, typeMap from CodecContext                   |
| Composite handling | CompositeCodec for named types                                                        |
| Record type (OID 2249) | FallbackCodec (unknown type), no special handling                                     |
| SQLInput impl | Generic base `PgSQLInput<BufferType>`, two subclasses for binary/text                 |
| SQLOutput impl | Generic base `PgSQLOutput<BufferType>`, two subclasses for binary/text                |
| Package structure | Interfaces in org.postgresql.api.codec, impls in org.postgresql.jdbc.codec (internal) |
| API stability | @Experimental for first release, stable in next major                                 |
| Component separation | TypeInfoCache, JavaTypeRegistry, CodecRegistry as separate classes                    |
| Component ownership | CodecContext owns all three                                                           |
| Cache implementation | Caffeine (size-based LRU) for oid→codec, single cache with instanceof                 |
| Cache invalidation | Epoch-based (any DDL command increments epoch)                                        |
| Codec caching in Field | Field caches resolved codec reference after first lookup                              |
| Fields loading | Lazy via get() + putIfAbsent() (avoids computeIfAbsent deadlock)                      |
| ByteConverter | Retained for protocol handling, used by codecs internally                             |
| PGobject classes | All preserved for backward compatibility                                              |
| Unknown types | PGUnknownBinary for binary, PGobject for text (two separate classes)                  |
| Migration strategy | Big bang (full replacement in one release)                                            |
| Testing | Both unit tests (per codec) and integration tests                                     |
| PGSQLType enum | Enum with built-in PostgreSQL types for JDBC 4.2 SQLType support                      |
| Boolean JDBC type | Configurable via `map.pg_type.boolean=bit                                             |boolean` (affects metadata too) |
| setTypeMap() behavior | Creates new CodecContext and replaces current in connection                           |
| setNull() behavior | Uses describe to get proper OID for Types.OTHER                                       |
| PgArray context | Stores CodecContext snapshot at creation time                                         |
| getObject(i, T[].class) | Supported for array columns (e.g., Integer[].class)                                   |
| Connection.createStruct() | Implemented, returns PGStruct for setObject()                                         |
| CallableStatement STRUCT | Included in scope for OUT parameters                                                  |
| DatabaseMetaData.getUDTs() | Implemented using TypeInfoCache                                                       |
| ParameterMetaData | Considers Codec.getDefaultJavaType() for type mapping                                 |
| Caffeine dependency | shaded library to reduce jar size and dependency conflicts                            |
| Range types | GenericRangeCodec for user-defined ranges via PgType.typelem                          |
| Multirange types | Deferred to future version                                                            |
| @Experimental | Own annotation in org.postgresql.api.Experimental                                     |
| ObjectName.parse() | Retained for typeMap lookup with schema.type parsing                                  |
| enquoteIdentifier | Logic unified in ObjectName class                                                     |
| Pool reset | unregisterCodec() method for explicit removal                                         |
| Large Objects | Outside scope, existing API retained                                                  |

---

## 1. Core Architecture

### 1.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CodecContext                                 │
│  (Per-connection, immutable, passed to all codecs)                  │
├─────────────────────────────────────────────────────────────────────┤
│  - TypeInfoCache typeInfo     // PgType cache                       │
│  - CodecRegistry codecs       // Codec lookup                       │
│  - JavaTypeRegistry javaTypes // Java ↔ PG mappings                 │
│  - TimeZone timezone          // From PgPreparedStatement.getDefaultCalendar() │
│  - Charset encoding                                                  │
│  - Map<String,Class<?>> typeMap // Current type mappings            │
│  + withTypeMap(map) → CodecContext  // Returns new instance         │
└─────────────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  TypeInfoCache  │  │  CodecRegistry  │  │ JavaTypeRegistry│
│                 │  │                 │  │                 │
│ - PgType cache  │  │ - name → codec  │  │ - typeMap       │
│ - getByOid()    │  │ - oid → codec   │  │ - arrayOidMap   │
│ - getByName()   │  │   (Caffeine)    │  │                 │
│ - getFields()   │  │ - register()    │  │                 │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

### 1.2 Nesting Depth Protection

```java
// ThreadLocal for tracking nested encode/decode depth (shared counter)
// Safe with virtual threads due to short lifespan and proper cleanup
public final class CodecDepth {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_DEPTH = 64;

    public static void enter() throws SQLException {
        int depth = DEPTH.get() + 1;
        if (depth > MAX_DEPTH) {
            throw new SQLException("Maximum type nesting depth exceeded: " + MAX_DEPTH);
        }
        DEPTH.set(depth);
    }

    public static void exit() {
        DEPTH.set(DEPTH.get() - 1);
    }
    
    public static void clear() {
        DEPTH.remove();
    }
}
```

### 1.3 Codec Interface Hierarchy

```java
// Base marker interface - PUBLIC API in org.postgresql.api.codec
// @Experimental in first release
@Experimental
public interface Codec {
    /** Type name this codec handles (e.g., "int4", "geometry") */
    String getTypeName();

    /** Java type this codec produces by default */
    Class<?> getDefaultJavaType();
}

// Binary format codec - PUBLIC API
@Experimental
public interface BinaryCodec extends Codec {
    Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException;
    byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException;

    // Primitive specializations with default fallback implementations
    // IMPORTANT: All implementations MUST check overflow and throw SQLException
    // Reference implementation: PgResultSet.readLongValue()
    default int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeBinary(data, type, ctx)).intValue();
    }

    default long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeBinary(data, type, ctx)).longValue();
    }

    default float decodeAsFlaot(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeBinary(data, type, ctx)).floatValue();
    }

    default double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeBinary(data, type, ctx)).doubleValue();
    }

    default boolean decodeAsBoolean(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return (Boolean) decodeBinary(data, type, ctx);
    }

    default String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        Object value = decodeBinary(data, type, ctx);
        return value == null ? null : value.toString();
    }

    default BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        Object value = decodeBinary(data, type, ctx);
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        throw new SQLException("Cannot convert to BigDecimal: " + value.getClass());
    }

    default byte[] decodeAsBytes(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        Object value = decodeBinary(data, type, ctx);
        if (value instanceof byte[]) return (byte[]) value;
        throw new SQLException("Cannot convert to byte[]: " + value.getClass());
    }

    // Generic decode with target type - codec handles all conversions
    // Note: primitive classes (int.class, long.class) are NOT supported
    // JDBC does not require getObject(i, int.class)
    default <T> T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
            throws SQLException {
        // Each codec implements conversion logic for supported target types
        throw new SQLException("Conversion to " + targetClass + " not supported");
    }
}

// Text format codec - PUBLIC API
@Experimental
public interface TextCodec extends Codec {
    Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException;
    String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException;

    // Same primitive specializations as BinaryCodec
    default int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeText(data, type, ctx)).intValue();
    }

    default long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeText(data, type, ctx)).longValue();
    }

    default double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeText(data, type, ctx)).doubleValue();
    }

    default float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
        return ((Number) decodeText(data, type, ctx)).floatValue();
    }

    default boolean decodeAsBoolean(String data, PgType type, CodecContext ctx) throws SQLException {
        return (Boolean) decodeText(data, type, ctx);
    }

    default String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
        Object value = decodeText(data, type, ctx);
        return value == null ? null : value.toString();
    }

    // Fast path for raw text bytes - avoids String conversion for numeric parsing
    // Default implementations convert to String and delegate
    default int decodeTextBytesAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return decodeAsInt(new String(data, java.nio.charset.StandardCharsets.UTF_8), type, ctx);
    }

    default long decodeTextBytesAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
        return decodeAsLong(new String(data, java.nio.charset.StandardCharsets.UTF_8), type, ctx);
    }
    // ... etc
}
```

### 1.4 Built-in Codecs (Examples)

```java
// Each codec in its own file: Int4Codec.java
// Package: org.postgresql.jdbc.codec (internal, package-private)
final class Int4Codec implements BinaryCodec, TextCodec {
    static final Int4Codec INSTANCE = new Int4Codec();

    @Override
    public String getTypeName() { return "int4"; }

    @Override
    public Class<?> getDefaultJavaType() { return Integer.class; }

    // Optimized binary decode - no boxing for int
    @Override
    public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) {
        return ByteConverter.int4(data, 0);
    }

    @Override
    public Object decodeBinary(byte[] data, PgType type, CodecContext ctx) {
        return decodeAsInt(data, type, ctx);  // Boxing only when needed
    }

    @Override
    public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) {
        int v = ((Number) value).intValue();
        byte[] result = new byte[4];
        ByteConverter.int4(result, 0, v);
        return result;
    }

    @Override
    public Object decodeText(String data, PgType type, CodecContext ctx) {
        return Integer.parseInt(data);
    }

    @Override
    public String encodeText(Object value, PgType type, CodecContext ctx) {
        return value.toString();
    }

    // Codec handles conversions to other types
    @Override
    public <T> T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
            throws SQLException {
        int value = decodeAsInt(data, type, ctx);
        if (targetClass == Integer.class) {
            return targetClass.cast(value);
        }
        if (targetClass == Long.class) {
            return targetClass.cast((long) value);
        }
        if (targetClass == String.class) {
            return targetClass.cast(String.valueOf(value));
        }
        if (targetClass == BigDecimal.class) {
            return targetClass.cast(BigDecimal.valueOf(value));
        }
        throw new SQLException("Cannot convert int4 to " + targetClass);
    }
}
```

---

## 2. JDBC 4.2 SQLType Support

### 2.1 PGSQLType Enum

```java
/**
 * PostgreSQL-specific SQLType implementation for JDBC 4.2+.
 * Provides built-in PostgreSQL types as SQLType constants.
 */
public enum PGSQLType implements SQLType {
    INT2(Oid.INT2, "int2"),
    INT4(Oid.INT4, "int4"),
    INT8(Oid.INT8, "int8"),
    FLOAT4(Oid.FLOAT4, "float4"),
    FLOAT8(Oid.FLOAT8, "float8"),
    NUMERIC(Oid.NUMERIC, "numeric"),
    BOOL(Oid.BOOL, "bool"),
    TEXT(Oid.TEXT, "text"),
    VARCHAR(Oid.VARCHAR, "varchar"),
    BYTEA(Oid.BYTEA, "bytea"),
    DATE(Oid.DATE, "date"),
    TIME(Oid.TIME, "time"),
    TIMETZ(Oid.TIMETZ, "timetz"),
    TIMESTAMP(Oid.TIMESTAMP, "timestamp"),
    TIMESTAMPTZ(Oid.TIMESTAMPTZ, "timestamptz"),
    UUID(Oid.UUID, "uuid"),
    JSON(Oid.JSON, "json"),
    JSONB(Oid.JSONB, "jsonb"),
    XML(Oid.XML, "xml"),
    INTERVAL(Oid.INTERVAL, "interval"),
    POINT(Oid.POINT, "point"),
    LINE(Oid.LINE, "line"),
    LSEG(Oid.LSEG, "lseg"),
    BOX(Oid.BOX, "box"),
    PATH(Oid.PATH, "path"),
    POLYGON(Oid.POLYGON, "polygon"),
    CIRCLE(Oid.CIRCLE, "circle"),
    // Only well-known built-in PostgreSQL types
    ;

    private final int oid;
    private final String name;

    @Override
    public String getName() { return name; }

    @Override
    public String getVendor() { return "org.postgresql"; }

    @Override
    public Integer getVendorTypeNumber() { return oid; }
}
```

### 2.2 SQLType Handling in JavaTypeRegistry

```java
public final class JavaTypeRegistry {
    private final TypeInfoCache typeInfo;
    
    /**
     * Resolves OID for SQLType parameter in setObject(int, Object, SQLType).
     * For JDBCType (getVendor() == "java.sql"), delegates to getVendorTypeNumber()
     * and maps via standard java.sql.Types constants.
     */
    public int getOidForSQLType(SQLType sqlType, @Nullable Object value) throws SQLException {
        if ("java.sql".equals(sqlType.getVendor())) {
            // JDBCType - use getVendorTypeNumber() which returns java.sql.Types constant
            return getOidForSqlType(sqlType.getVendorTypeNumber(), value);
        }
        if ("org.postgresql".equals(sqlType.getVendor())) {
            // PGSQLType - use OID directly
            return sqlType.getVendorTypeNumber();
        }
        // Custom SQLType implementations - use name-based lookup
        return getOidByTypeName(sqlType.getName());
    }

    /**
     * Maps java.sql.Types constant to PostgreSQL OID.
     * Used for setObject(int, Object, int sqlType) and JDBCType handling.
     */
    public int getOidForSqlType(int sqlType, @Nullable Object value) throws SQLException {
        // Standard mapping: Types.INTEGER → INT4, Types.VARCHAR → VARCHAR, etc.
        switch (sqlType) {
            case Types.INTEGER: return Oid.INT4;
            case Types.BIGINT: return Oid.INT8;
            case Types.SMALLINT: return Oid.INT2;
            case Types.VARCHAR: return Oid.VARCHAR;
            case Types.TIMESTAMP: return Oid.TIMESTAMP;
            case Types.TIMESTAMP_WITH_TIMEZONE: return Oid.TIMESTAMPTZ;
            case Types.OTHER:
                if (value instanceof Struct) {
                    Struct struct = (Struct) value;
                    return typeInfo.getPgTypeByName(struct.getSQLTypeName());
                }
            // ... etc
            default:
                throw new SQLException("Unsupported SQL type: " + sqlType);
        }
    }
}
```

---

## 3. Composite Codecs

### 3.1 ArrayCodec - Composition Pattern

`Array.getResultSet()` uses existing `BaseStatement.createDriverResultSet` mechanism.

```java
public final class ArrayCodec implements BinaryCodec, TextCodec {
    // No element codec stored - resolved dynamically from registry
    // Element codec is cached before loop for performance

    @Override
    public Object decodeBinary(byte[] data, PgType arrayType, CodecContext ctx)
            throws SQLException {
        CodecDepth.enter();
        try {
            int elementOid = arrayType.getTypelem();
            // Cache element codec before loop - single lookup
            BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid);

            // Parse array header, then decode each element using elementCodec
            return decodeArrayElements(data, elementCodec, ctx);
        } finally {
            CodecDepth.exit();
        }
    }

    // For getArray(map) with SQLData mapping - uses typeMap from CodecContext
    public Object decodeBinaryAs(byte[] data, PgType arrayType,
            Class<?> elementClass, CodecContext ctx) throws SQLException {
        // ctx.getTypeMap() provides current type mappings
        // ... decode elements and convert to elementClass
    }
}
```

### 3.2 CompositeCodec - For Named Types

```java
public final class CompositeCodec implements BinaryCodec, TextCodec {

    @Override
    public Object decodeBinary(byte[] data, PgType type, CodecContext ctx)
            throws SQLException {
        CodecDepth.enter();
        try {
            // Binary format contains OIDs for each field - no schema lookup needed
            return decodeToPGobject(data, type, ctx);
        } finally {
            CodecDepth.exit();
        }
    }

    @Override
    public Object decodeText(String data, PgType type, CodecContext ctx)
            throws SQLException {
        CodecDepth.enter();
        try {
            // Text format "(val1,val2,...)" requires field structure
            // Fields loaded via PgType.getFields() (lazy loading)
            List<PgField> fields = type.getFields();
            return decodeTextToPGobject(data, fields, type, ctx);
        } finally {
            CodecDepth.exit();
        }
    }

    // For getObject(i, MySQLDataClass.class)
    // Note: No auto-registration occurs. Registration is explicit via SPI or API only.
    public <T extends SQLData> T decodeBinaryAs(byte[] data, PgType type,
            Class<T> sqlDataClass, CodecContext ctx) throws SQLException {

        T instance = sqlDataClass.getConstructor().newInstance();

        // Create SQLInput that uses codecs internally (recursive through codecs)
        SQLInput sqlInput = new PgSQLInputBinary(data, type, ctx);

        instance.readSQL(sqlInput, type.getFullName());
        return instance;
    }
}
```

### 3.3 Anonymous Record Type (OID 2249)

Anonymous row types (OID 2249) are treated as unknown types:
- Text format has no type information for fields
- Binary format has OIDs, but text doesn't
- `getObject()` result must not depend on wire format (binary vs text)
- Returns `PGobject` (text) or `PGUnknownBinary` (binary) via FallbackCodec

Future consideration: PGRecord class with optional field access.

### 3.4 DomainCodec - For Domain Types

```java
public final class DomainCodec implements BinaryCodec, TextCodec {

    @Override
    public Object decodeBinary(byte[] data, PgType domainType, CodecContext ctx)
            throws SQLException {
        // Domain type delegates to base type codec
        // Domain constraints are validated by the server, not the driver
        int baseTypeOid = domainType.getBasetype();
        PgType baseType = ctx.getTypeInfo().getPgTypeByOid(baseTypeOid);
        BinaryCodec baseCodec = ctx.getCodecs().getBinaryCodec(baseTypeOid);
        return baseCodec.decodeBinary(data, baseType, ctx);
    }

    @Override
    public byte[] encodeBinary(Object value, PgType domainType, CodecContext ctx)
            throws SQLException {
        int baseTypeOid = domainType.getBasetype();
        PgType baseType = ctx.getTypeInfo().getPgTypeByOid(baseTypeOid);
        BinaryCodec baseCodec = ctx.getCodecs().getBinaryCodec(baseTypeOid);
        return baseCodec.encodeBinary(value, baseType, ctx);
    }

    // Text methods similar
}
```

### 3.5 PgSQLInput - Generic Base with Two Implementations

```java
/**
 * Base class for SQLInput implementations.
 * Uses generic BufferType to avoid code duplication between binary and text formats.
 * Note: byte[] is a reference type, no boxing occurs.
 */
public abstract class PgSQLInput<BufferType> implements SQLInput {
    protected final BufferType[] attributeValues;
    protected final PgType compositeType;
    protected final CodecContext ctx;
    protected final List<PgField> fields;
    protected int fieldIndex = 0;
    protected boolean lastWasNull = false;

    protected PgSQLInput(BufferType[] attributeValues, PgType type, CodecContext ctx)
            throws SQLException {
        this.attributeValues = attributeValues;
        this.compositeType = type;
        this.ctx = ctx;
        this.fields = type.getFields();
    }

    @Override
    public boolean wasNull() throws SQLException {
        return lastWasNull;
    }

    protected abstract int decodeInt(BufferType data, PgType fieldType) throws SQLException;
    protected abstract long decodeLong(BufferType data, PgType fieldType) throws SQLException;
    protected abstract String decodeString(BufferType data, PgType fieldType) throws SQLException;
    // ... other abstract decode methods

    @Override
    public int readInt() throws SQLException {
        PgField field = fields.get(fieldIndex++);
        BufferType data = attributeValues[fieldIndex - 1];
        if (data == null) {
            lastWasNull = true;
            return 0;
        }
        lastWasNull = false;
        PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(field.getTypeOid());
        return decodeInt(data, fieldType);
    }

    @Override
    public String readString() throws SQLException {
        PgField field = fields.get(fieldIndex++);
        BufferType data = attributeValues[fieldIndex - 1];
        if (data == null) {
            lastWasNull = true;
            return null;
        }
        lastWasNull = false;
        PgType fieldType = ctx.getTypeInfo().getPgTypeByOid(field.getTypeOid());
        return decodeString(data, fieldType);
    }

    // readObject() and readArray() use recursive codec calls for nested types

    // Other read methods follow same pattern
}

/**
 * Binary format SQLInput implementation.
 */
public final class PgSQLInputBinary extends PgSQLInput<byte[]> {

    public PgSQLInputBinary(byte[] compositeData, PgType type, CodecContext ctx)
            throws SQLException {
        super(parseCompositeData(compositeData), type, ctx);
    }

    @Override
    protected int decodeInt(byte[] data, PgType fieldType) throws SQLException {
        BinaryCodec codec = ctx.getCodecs().getBinaryCodec(fieldType.getOid());
        return codec.decodeAsInt(data, fieldType, ctx);
    }

    @Override
    protected String decodeString(byte[] data, PgType fieldType) throws SQLException {
        BinaryCodec codec = ctx.getCodecs().getBinaryCodec(fieldType.getOid());
        return codec.decodeAsString(data, fieldType, ctx);
    }

    // ... other decode implementations
}

/**
 * Text format SQLInput implementation.
 */
public final class PgSQLInputText extends PgSQLInput<String> {

    public PgSQLInputText(String compositeData, PgType type, CodecContext ctx)
            throws SQLException {
        super(parseCompositeString(compositeData), type, ctx);
    }

    @Override
    protected int decodeInt(String data, PgType fieldType) throws SQLException {
        TextCodec codec = ctx.getCodecs().getTextCodec(fieldType.getOid());
        return codec.decodeAsInt(data, fieldType, ctx);
    }

    @Override
    protected String decodeString(String data, PgType fieldType) throws SQLException {
        TextCodec codec = ctx.getCodecs().getTextCodec(fieldType.getOid());
        return codec.decodeAsString(data, fieldType, ctx);
    }

    // ... other decode implementations
}
```

### 3.6 PgSQLOutput - Generic Base for SQLData Encoding

```java
/**
 * Base class for SQLOutput implementations.
 * Uses generic BufferType to avoid code duplication between binary and text formats.
 * Used by PreparedStatement.setObject(i, sqlDataObject) to encode SQLData.
 */
public abstract class PgSQLOutput<BufferType> implements SQLOutput {
    protected final List<BufferType> attributeValues = new ArrayList<>();
    protected final PgType compositeType;
    protected final CodecContext ctx;
    protected final List<PgField> fields;
    protected int fieldIndex = 0;

    protected PgSQLOutput(PgType type, CodecContext ctx) throws SQLException {
        this.compositeType = type;
        this.ctx = ctx;
        this.fields = type.getFields();
    }

    // Abstract encode methods return BufferType to be collected in attributeValues
    protected abstract BufferType encodeInt(int value, PgType fieldType) throws SQLException;
    protected abstract BufferType encodeLong(long value, PgType fieldType) throws SQLException;
    protected abstract BufferType encodeString(String value, PgType fieldType) throws SQLException;
    // ... other abstract encode methods

    protected PgField nextField() throws SQLException {
        if (fieldIndex >= fields.size()) {
            throw new SQLException("Attempt to write past end of composite type fields");
        }
        return fields.get(fieldIndex++);
    }

    protected PgType getFieldType(PgField field) throws SQLException {
        return ctx.getTypeInfo().getPgTypeByOid(field.getTypeOid());
    }

    @Override
    public void writeInt(int x) throws SQLException {
        PgField field = nextField();
        attributeValues.add(encodeInt(x, getFieldType(field)));
    }

    @Override
    public void writeString(String x) throws SQLException {
        PgField field = nextField();
        if (x == null) {
            attributeValues.add(null);
            return;
        }
        attributeValues.add(encodeString(x, getFieldType(field)));
    }

    // writeObject() and writeArray() use recursive codec calls for nested types
}

/**
 * Binary format SQLOutput implementation.
 */
public final class PgSQLOutputBinary extends PgSQLOutput<byte[]> {

    public PgSQLOutputBinary(PgType type, CodecContext ctx) throws SQLException {
        super(type, ctx);
    }

    @Override
    protected byte[] encodeInt(int value, PgType fieldType) throws SQLException {
        BinaryCodec codec = ctx.getCodecs().getBinaryCodec(fieldType.getOid());
        return codec.encodeBinary(value, fieldType, ctx);
    }

    // ... other encode implementations

    public byte[] toBytes() {
        // Serialize to PostgreSQL binary composite format
    }
}

/**
 * Text format SQLOutput implementation.
 */
public final class PgSQLOutputText extends PgSQLOutput<String> {

    public PgSQLOutputText(PgType type, CodecContext ctx) throws SQLException {
        super(type, ctx);
    }

    @Override
    protected String encodeInt(int value, PgType fieldType) throws SQLException {
        TextCodec codec = ctx.getCodecs().getTextCodec(fieldType.getOid());
        return codec.encodeText(value, fieldType, ctx);
    }

    // ... other encode implementations

    public String toCompositeString() {
        // Serialize to PostgreSQL text composite format "(val1,val2,...)"
    }
}
```

---

## 4. CodecRegistry

### 4.1 Registration

```java
public final class CodecRegistry {
    // Built-in codecs (global, immutable)
    private static final Map<String, Codec> BUILTIN_CODECS = initBuiltinCodecs();

    // Per-connection custom codecs
    private final Map<String, Codec> customCodecs = new ConcurrentHashMap<>();

    // Single OID → Codec cache (Caffeine with size-based LRU eviction)
    // Most codecs implement both BinaryCodec and TextCodec, so single cache avoids duplication
    private final Cache<Integer, Codec> codecByOid;

    private final TypeInfoCache typeInfo;

    public CodecRegistry(TypeInfoCache typeInfo, int maxCacheSize) {
        this.typeInfo = typeInfo;
        this.codecByOid = Caffeine.newBuilder()
            .maximumSize(maxCacheSize)
            .build();
    }

    public void register(Codec codec) {
        customCodecs.put(codec.getTypeName(), codec);
        // Clear OID cache since new codec may override existing
        codecByOid.invalidateAll();
    }

    public BinaryCodec getBinaryCodec(int oid) throws SQLException {
        Codec codec = codecByOid.get(oid, this::resolveCodecForOid);
        if (codec instanceof BinaryCodec) {
            return (BinaryCodec) codec;
        }
        throw new SQLException("No binary codec for OID " + oid);
    }

    public TextCodec getTextCodec(int oid) throws SQLException {
        Codec codec = codecByOid.get(oid, this::resolveCodecForOid);
        if (codec instanceof TextCodec) {
            return (TextCodec) codec;
        }
        throw new SQLException("No text codec for OID " + oid);
    }

    private Codec resolveCodecForOid(int oid) throws SQLException {
        PgType type = typeInfo.getPgTypeByOid(oid);
        if (type.getTyptype() == 'r') {
            return GenericRangeCodec.INSTANCE;
        }
        // Without schema
        // It is fine to skip schema for retrieving the codec as "base type name without schema" is the only way to identify a type in postgresql
        // For instance, if a codec knows the way to decode "hstore", we can't predict the schema for hstore, and we have to resort to "name without schema"
        // for the codec identification.
        String typeName = type.getTypeName().getName();

        // Check custom first, then built-in
        Codec codec = customCodecs.get(typeName);
        if (codec == null) {
            codec = BUILTIN_CODECS.get(typeName);
        }
        if (codec == null) {
            // Check for domain type
            if (type.getBasetype() != 0) {
                codec = DomainCodec.INSTANCE;
            }
        }
        if (codec == null) {
            codec = getFallbackCodec(type);
        }
        return codec;
    }

    private Codec getFallbackCodec(PgType type) {
        // Return FallbackCodec that produces PGUnknownBinary/PGobject
        return FallbackCodec.INSTANCE;
    }
}
```

### 4.2 FallbackCodec for Unknown Types

```java
public final class FallbackCodec implements BinaryCodec, TextCodec {
    public static final FallbackCodec INSTANCE = new FallbackCodec();

    @Override
    public String getTypeName() { return "*"; }  // Wildcard

    @Override
    public Class<?> getDefaultJavaType() { return PGobject.class; }

    @Override
    public Object decodeBinary(byte[] data, PgType type, CodecContext ctx) {
        // Use PGUnknownBinary for binary data (separate class from PGobject)
        PGUnknownBinary obj = new PGUnknownBinary();
        obj.setType(type.getFullName());
        obj.setBytes(data);
        return obj;
    }

    @Override
    public Object decodeText(String data, PgType type, CodecContext ctx) {
        PGobject obj = new PGobject();
        obj.setType(type.getFullName());
        obj.setValue(data);
        return obj;
    }

    // encode methods throw SQLException for unknown types
}
```

### 4.3 SPI Registration

```java
// META-INF/services/org.postgresql.api.codec.Codec
// Codecs loaded via ServiceLoader at first connection

// In CodecRegistry initialization (loaded at first connection):
private static Map<String, Codec> initBuiltinCodecs() {
    Map<String, Codec> codecs = new HashMap<>();

    // Core types
    codecs.put("int2", Int2Codec.INSTANCE);
    codecs.put("int4", Int4Codec.INSTANCE);
    codecs.put("int8", Int8Codec.INSTANCE);
    codecs.put("float4", Float4Codec.INSTANCE);
    codecs.put("float8", Float8Codec.INSTANCE);
    codecs.put("numeric", NumericCodec.INSTANCE);
    codecs.put("bool", BoolCodec.INSTANCE);
    codecs.put("text", TextCodecImpl.INSTANCE);
    codecs.put("varchar", VarcharCodec.INSTANCE);
    codecs.put("bytea", ByteaCodec.INSTANCE);
    codecs.put("date", DateCodec.INSTANCE);
    codecs.put("time", TimeCodec.INSTANCE);
    codecs.put("timetz", TimetzCodec.INSTANCE);
    codecs.put("timestamp", TimestampCodec.INSTANCE);
    codecs.put("timestamptz", TimestamptzCodec.INSTANCE);
    codecs.put("uuid", UuidCodec.INSTANCE);
    codecs.put("json", JsonCodec.INSTANCE);
    codecs.put("jsonb", JsonbCodec.INSTANCE);

    // Range types - separate instances per type
    codecs.put("int4range", Int4RangeCodec.INSTANCE);
    codecs.put("int8range", Int8RangeCodec.INSTANCE);
    codecs.put("numrange", NumRangeCodec.INSTANCE);
    codecs.put("tsrange", TsRangeCodec.INSTANCE);
    codecs.put("tstzrange", TstzRangeCodec.INSTANCE);
    codecs.put("daterange", DateRangeCodec.INSTANCE);

    // Geometric types
    codecs.put("point", PointCodec.INSTANCE);
    codecs.put("line", LineCodec.INSTANCE);
    codecs.put("lseg", LsegCodec.INSTANCE);
    codecs.put("box", BoxCodec.INSTANCE);
    codecs.put("path", PathCodec.INSTANCE);
    codecs.put("polygon", PolygonCodec.INSTANCE);
    codecs.put("circle", CircleCodec.INSTANCE);

    // hstore (built-in, loaded by name from any schema)
    codecs.put("hstore", HstoreCodec.INSTANCE);

    // Load from SPI - codecs are singleton instances
    ServiceLoader<Codec> providers = ServiceLoader.load(Codec.class);
    for (Codec codec : providers) {
        codecs.put(codec.getTypeName(), codec);
    }

    return Collections.unmodifiableMap(codecs);
}
```

### 4.4 Per-Connection Codec Registration

```java
// Extended PGConnection interface (existing interface)
public interface PGConnection extends Connection {
    // ... existing methods ...

    /**
     * Registers a custom codec for this connection.
     * @param codec the codec to register
     */
    void registerCodec(Codec codec);

    /**
     * Unregisters a custom codec from this connection.
     * Useful for connection pool reset scenarios.
     * @param typeName the type name to unregister
     */
    void unregisterCodec(String typeName);

    /**
     * Gets the codec registry for this connection.
     * @return the codec registry
     */
    CodecRegistry getCodecRegistry();

    /**
     * Clear all custom codecs (for pool reset)
     */
    void resetCodecs();
}

// Usage:
PGConnection pgConn = connection.unwrap(PGConnection.class);
pgConn.registerCodec(new MyCustomCodec());

// For pool reset:
pgConn.unregisterCodec("my_custom_type");
```

---

## 5. TypeInfoCache

### 5.1 PgType with typdelim and typbasetype

```java
public final class PgType {
    private final int oid;
    private final ObjectName typeName;
    private final int typelem;      // Element OID for arrays
    private final int typarray;     // Array OID for this type
    private final char typdelim;    // Delimiter character (loaded from pg_type.typdelim)
    private final char typtype;     // 'b'=base, 'c'=composite, 'e'=enum, 'd'=domain, etc.
    private final int typrelid;     // For composite types, the pg_class OID
    private final int typbasetype;  // For domain types, the base type OID

    // Fields loaded lazily via ConcurrentHashMap
    private volatile List<PgField> fields;

    public List<PgField> getFields() throws SQLException {
        // Lazy loading with double-checked locking
        if (fields == null) {
            synchronized (this) {
                if (fields == null) {
                    fields = loadFields();
                }
            }
        }
        return fields;
    }
    // ... getters
}
```

### 5.2 Lazy Fields Loading and Cache Invalidation

```java
public final class TypeInfoCache {
    private final ConcurrentHashMap<Integer, PgType> typesByOid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<PgField>> fieldsByOid = new ConcurrentHashMap<>();

    private volatile long typeCacheEpoch;

    // Use get() + putIfAbsent() pattern to avoid computeIfAbsent deadlock
    // (computeIfAbsent can deadlock if lambda accesses the same map)
    public List<PgField> getFields(int compositeOid) throws SQLException {
        List<PgField> fields = fieldsByOid.get(compositeOid);
        if (fields != null) {
            return fields;
        }
        fields = loadFields(compositeOid);
        List<PgField> existing = fieldsByOid.putIfAbsent(compositeOid, fields);
        return existing != null ? existing : fields;
    }

    private List<PgField> loadFields(int oid) throws SQLException {
        // Load from pg_attribute WHERE attrelid = (SELECT typrelid FROM pg_type WHERE oid = ?)
        // Returns empty list for non-composite types
    }

    /**
     * Invalidates cache when DDL commands are detected.
     * Called when command status contains CREATE, DROP, ALTER.
     * Note: This doesn't catch DDL inside functions or changes from other sessions.
     */
    public void invalidateIfNeeded(long connectionEpoch) {
        if (this.typeCacheEpoch != connectionEpoch) {
            typesByOid.clear();
            fieldsByOid.clear();
            this.typeCacheEpoch = connectionEpoch;  // Update epoch after clearing
        }
    }

    /**
     * Called after each command execution.
     * Increments epoch if DDL command detected.
     */
    public void checkCommandStatus(String status) {
        if (status != null && (
                status.startsWith("CREATE ") ||
                status.startsWith("DROP ") ||
                status.startsWith("ALTER "))) {
            incrementEpoch();
        }
    }
}
```

---

## 6. Integration with PgResultSet

### 6.1 Field Contains PgType and Cached Codec

```java
public class Field {
    private final int oid;
    private final String columnName;
    private volatile PgType pgType;    // Lazy loaded
    private volatile Codec cachedCodec; // Cached after first lookup

    public PgType getPgType(CodecContext ctx) throws SQLException {
        if (pgType == null) {
            synchronized (this) {
                if (pgType == null) {
                    pgType = ctx.getTypeInfo().getPgTypeByOid(oid);
                }
            }
        }
        return pgType;
    }

    public Codec getCodec(CodecContext ctx) throws SQLException {
        if (cachedCodec == null) {
            synchronized (this) {
                if (cachedCodec == null) {
                    cachedCodec = ctx.getCodecs().getCodec(oid);
                }
            }
        }
        return cachedCodec;
    }
}
```

### 6.2 Unified getInt() / getLong() / etc.

```java
public class PgResultSet implements ResultSet {

    private final CodecContext codecContext;  // From connection

    @Override
    public int getInt(int columnIndex) throws SQLException {
        byte[] data = getRawBytes(columnIndex);
        if (data == null) return 0;

        Field field = fields[columnIndex - 1];
        PgType type = field.getPgType(codecContext);
        Codec codec = field.getCodec(codecContext);  // Cached lookup

        if (isBinary(columnIndex)) {
            return ((BinaryCodec) codec).decodeAsInt(data, type, codecContext);
        } else {
            String text = getString(columnIndex);
            return ((TextCodec) codec).decodeAsInt(text, type, codecContext);
        }
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        byte[] data = getRawBytes(columnIndex);
        if (data == null) return null;

        Field field = fields[columnIndex - 1];
        PgType pgType = field.getPgType(codecContext);
        Codec codec = field.getCodec(codecContext);

        if (isBinary(columnIndex)) {
            return ((BinaryCodec) codec).decodeBinaryAs(data, pgType, type, codecContext);
        } else {
            String text = getString(columnIndex);
            return ((TextCodec) codec).decodeTextAs(text, pgType, type, codecContext);
        }
    }
}
```

---

## 7. PgPreparedStatement Parameter Binding

### 7.1 setObject() with SQLType Support

```java
public class PgPreparedStatement implements PreparedStatement {

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        setObject(parameterIndex, x, Types.OTHER);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        int oid = resolveOid(parameterIndex, x, targetSqlType);
        // ... encode and bind
    }

    @Override
    public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
        // JDBC 4.2 support
        int oid = javaTypeRegistry.getOidForSQLType(targetSqlType, x);
        // ... encode and bind
    }

    private int resolveOid(int parameterIndex, Object value, int sqlType) throws SQLException {
        // Strategy 1: If sqlType is specified and not Types.OTHER, use it
        if (sqlType != Types.OTHER) {
            return javaTypeRegistry.getOidForSqlType(sqlType, value);
        }

        // Strategy 2: For ambiguous cases, use describe statement (cached in statement)
        if (isAmbiguousType(value)) {
            Integer describedOid = getDescribedParameterOid(parameterIndex);
            if (describedOid != null) {
                return describedOid;
            }
        }

        // Strategy 3: Default mapping from Java class
        return javaTypeRegistry.getOidForJavaClass(value.getClass());
    }

    private boolean isAmbiguousType(Object value) {
        // String can map to text, varchar, json, xml, etc.
        // Number can map to int2, int4, int8, numeric, etc.
        return value instanceof String || value instanceof Number;
    }
}
```

---

## 8. Date/Time Codecs with Timezone Handling

### 8.1 Connection Properties for Date/Time Types

```java
// Connection property: getobject.timestamp=java.sql|java.time (default: java.sql)
// Controls what getObject() without Class parameter returns for date/time types.
// getObject(int, Class) always uses the requested class.

public final class TimestampCodec implements BinaryCodec, TextCodec {

    @Override
    public String getTypeName() { return "timestamp"; }

    @Override
    public Class<?> getDefaultJavaType() { return Timestamp.class; }  // java.sql for BC

    @Override
    public Object decodeBinary(byte[] data, PgType type, CodecContext ctx) {
        LocalDateTime ldt = decodeToLocalDateTime(data);

        // Check connection property for default type
        String pref = ctx.getProperty("getobject.timestamp", "java.sql");
        if ("java.time".equals(pref)) {
            return ldt;
        }
        return Timestamp.valueOf(ldt);
    }

    @Override
    public <T> T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
            throws SQLException {
        // If user explicitly requests a class, always return that class
        LocalDateTime ldt = decodeToLocalDateTime(data);

        if (targetClass == LocalDateTime.class) {
            return targetClass.cast(ldt);
        }
        if (targetClass == LocalDate.class) {
            return targetClass.cast(ldt.toLocalDate());
        }
        if (targetClass == LocalTime.class) {
            return targetClass.cast(ldt.toLocalTime());
        }
        if (targetClass == Timestamp.class) {
            return targetClass.cast(Timestamp.valueOf(ldt));
        }
        if (targetClass == Date.class) {
            return targetClass.cast(Date.valueOf(ldt.toLocalDate()));
        }
        if (targetClass == Instant.class) {
            // Use connection timezone for conversion
            // Timezone determined by PgPreparedStatement.getDefaultCalendar()
            ZoneId zone = ctx.getTimezone().toZoneId();
            return targetClass.cast(ldt.atZone(zone).toInstant());
        }
        if (targetClass == OffsetDateTime.class) {
            ZoneId zone = ctx.getTimezone().toZoneId();
            return targetClass.cast(ldt.atZone(zone).toOffsetDateTime());
        }
        if (targetClass == ZonedDateTime.class) {
            ZoneId zone = ctx.getTimezone().toZoneId();
            return targetClass.cast(ldt.atZone(zone));
        }

        throw new SQLException("Cannot convert timestamp to " + targetClass);
    }
}

public final class TimestamptzCodec implements BinaryCodec, TextCodec {

    @Override
    public <T> T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
            throws SQLException {
        // timestamptz is stored as UTC instant
        Instant instant = decodeToInstant(data);

        if (targetClass == Instant.class) {
            return targetClass.cast(instant);
        }
        if (targetClass == OffsetDateTime.class) {
            // Convert to connection timezone
            ZoneId zone = ctx.getTimezone().toZoneId();
            return targetClass.cast(instant.atZone(zone).toOffsetDateTime());
        }
        if (targetClass == ZonedDateTime.class) {
            // Return in UTC timezone (timestamptz is stored as UTC)
            return targetClass.cast(instant.atZone(ZoneOffset.UTC));
        }
        if (targetClass == Timestamp.class) {
            // Convert to connection timezone for Timestamp
            return targetClass.cast(Timestamp.from(instant));
        }
        // ... other conversions
    }
}
```

---

## 9. Range Types

### 9.1 PGRange Class

```java
/**
 * Represents a PostgreSQL range type.
 * Uses Object for bounds to support any element type.
 */
public final class PGRange<T> extends PGobject {
    private T lower;
    private T upper;
    private boolean lowerInclusive;
    private boolean upperInclusive;
    private boolean isEmpty;

    // Getters, setters, equals, hashCode, toString
}

// Separate codec instances per range type
public final class Int4RangeCodec implements BinaryCodec, TextCodec {
    static final Int4RangeCodec INSTANCE = new Int4RangeCodec();

    @Override
    public String getTypeName() { return "int4range"; }

    @Override
    public Class<?> getDefaultJavaType() { return PGRange.class; }

    @Override
    public Object decodeBinary(byte[] data, PgType rangeType, CodecContext ctx)
            throws SQLException {
        // Parse range flags (empty, lower/upper inclusive/infinite)
        // Decode bounds using element type codec (cached before loop)
        int elementOid = rangeType.getTypelem();
        BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid);

        // Returns PGRange<Integer>
        return decodeRange(data, elementCodec, ctx);
    }
}
```

---

## 10. Enum Types

Enum types are decoded as String only. PostgreSQL enum → String conversion.
For setObject with Java enum, application should convert to String first.
No automatic Java Enum ↔ PostgreSQL enum mapping.

---

## 11. Java ↔ PostgreSQL Type Mappings

### 11.1 Default Java → PostgreSQL Array Mappings

| Java Type | PostgreSQL Array Type | OID |
|-----------|----------------------|-----|
| `boolean[]` / `Boolean[]` | `bool[]` | 1000 |
| `byte[]` | `bytea` | 17 (not array) |
| `short[]` / `Short[]` | `int2[]` | 1005 |
| `int[]` / `Integer[]` | `int4[]` | 1007 |
| `long[]` / `Long[]` | `int8[]` | 1016 |
| `float[]` / `Float[]` | `float4[]` | 1021 |
| `double[]` / `Double[]` | `float8[]` | 1022 |
| `String[]` | `text[]` | 1009 |
| `BigDecimal[]` | `numeric[]` | 1231 |
| `Date[]` | `date[]` | 1182 |
| `Time[]` | `time[]` | 1183 |
| `Timestamp[]` | `timestamp[]` | 1115 |
| `UUID[]` | `uuid[]` | 2951 |

### 11.2 Default PostgreSQL → Java Scalar Mappings

| PostgreSQL Type | Default Java Type | Notes |
|-----------------|-------------------|-------|
| `int2` | `Integer` | BC: historically returned Integer |
| `int4` | `Integer` | |
| `int8` | `Long` | |
| `float4` | `Float` | |
| `float8` | `Double` | |
| `numeric` | `BigDecimal` | |
| `bool` | `Boolean` | JDBC type configurable via `map.pg_type.boolean` |
| `text`, `varchar` | `String` | |
| `bytea` | `byte[]` | getObject(i, byte[].class) and getBytes() both work |
| `date` | `java.sql.Date` | java.time via `getobject.date=java.time` |
| `time` | `java.sql.Time` | java.time via `getobject.time=java.time` |
| `timetz` | `java.sql.Time` | java.time via `getobject.timetz=java.time` |
| `timestamp` | `java.sql.Timestamp` | java.time via `getobject.timestamp=java.time` |
| `timestamptz` | `java.sql.Timestamp` | java.time via `getobject.timestamptz=java.time` |
| `uuid` | `UUID` | |
| `json`, `jsonb` | `String` | No Jackson integration (user deserializes) |
| `enum` | `String` | |
| `range` | `PGRange<Object>` | |
| Composite types | `PGobject` | Or SQLData via typeMap |
| Domain types | Base type Java class | Via DomainCodec |
| Unknown types | `PGUnknownBinary` / `PGobject` | Two separate classes |

### 11.3 Connection Properties for Type Mapping

```
# Boolean JDBC type (default: bit for BC)
map.pg_type.boolean=bit|boolean

# Date/time default types (default: java.sql for BC)
getobject.date=java.sql|java.time
getobject.time=java.sql|java.time
getobject.timetz=java.sql|java.time
getobject.timestamp=java.sql|java.time
getobject.timestamptz=java.sql|java.time
```

---

## 12. ORM Compatibility

### 12.1 Hibernate Considerations

- **ResultSetMetaData.getColumnType()**: When `map.pg_type.boolean=boolean`, returns `Types.BOOLEAN` (affects Hibernate mapping detection)
- **getObject(i) round-trip**: PGobject returned for unknown types can be passed back to setObject() without data loss
- **JSON support**: Returns `String`, Hibernate 6 uses `@JdbcTypeCode(SqlTypes.JSON)` on application level
- **TypeHandler compatibility**: No conflicts - Hibernate works at ORM level, codecs at JDBC level

### 12.2 jOOQ Considerations

- **DatabaseMetaData.getColumns()**: NOT affected by custom codecs (always returns standard PostgreSQL → JDBC mapping)
- **Code generation**: Works as before, codec changes don't affect metadata
- **getObject(i, Integer[].class)**: Supported for array columns

### 12.3 MyBatis Considerations

- **TypeHandler**: Works at ORM level, no conflict with pgjdbc codecs
- **Custom type handling**: Users can use either MyBatis TypeHandler or pgjdbc Codec (JDBC level)

---

## 13. Connection.createStruct() Support

```java
// JDBC API implementation
public class PgConnection implements Connection, PGConnection {

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        // Resolve type using TypeInfoCache (respects search_path)
        PgType type = typeInfoCache.getPgTypeByName(typeName);
        if (type == null) {
            throw new SQLException("Unknown type: " + typeName);
        }
        if (type.getTyptype() != 'c') {
            throw new SQLException("Not a composite type: " + typeName);
        }

        // Create PGStruct that can be passed to setObject()
        return new PGStruct(type, attributes, codecContext);
    }
}

/**
 * Implementation of java.sql.Struct for PostgreSQL composite types.
 */
public class PGStruct implements Struct {
    private final PgType type;
    private final Object[] attributes;
    private final CodecContext ctx;

    @Override
    public String getSQLTypeName() throws SQLException {
        return type.getFullName();
    }

    @Override
    public Object[] getAttributes() throws SQLException {
        return attributes.clone();
    }

    @Override
    public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
        // Convert attributes using typeMap
        CodecContext withMap = ctx.withTypeMap(map);
        // ... convert using codecs
    }
}
```

---

## 14. DatabaseMetaData Integration

TypeInfoCache will be used to implement `DatabaseMetaData.getTypeInfo()` and `getUDTs()`:

```java
public class PgDatabaseMetaData implements DatabaseMetaData {
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        // Use TypeInfoCache to build type info result set
        return typeInfoCache.getTypeInfoResultSet();
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern,
            String typeNamePattern, int[] types) throws SQLException {
        // Query pg_type for composite (c), enum (e), domain (d) types
        // Uses TypeInfoCache for type information
        // Returns standard UDT columns: TYPE_CAT, TYPE_SCHEM, TYPE_NAME,
        // CLASS_NAME, DATA_TYPE, REMARKS, BASE_TYPE
        return typeInfoCache.getUDTsResultSet(schemaPattern, typeNamePattern, types);
    }
}
```

---

## 15. Migration Plan

### 15.1 Phase 1: Core Infrastructure

1. Create `CodecContext` class (immutable with `withTypeMap()`)
2. Create `Codec`, `BinaryCodec`, `TextCodec` interfaces in `org.postgresql.api.codec` (public API, @Experimental)
3. Create `org.postgresql.api.Experimental` annotation
4. Create `CodecRegistry` with Caffeine cache (single cache) and SPI support (per-Driver scope)
5. Create `CodecDepth` ThreadLocal for nesting protection (shared encode/decode counter)
6. Add `typdelim`, `typbasetype` to PgType and PG_TYPE_FIELDS query
7. Fix `TypeInfoCache.invalidateCacheIfNeeded()` epoch bug
8. Implement DDL-based cache invalidation (any CREATE/DROP/ALTER detection)
9. Implement lazy fields loading via get() + putIfAbsent() pattern
10. Add `Field.getPgType()` and `Field.getCodec()` for ResultSet integration
11. Create `PGSQLType` enum for JDBC 4.2 support
12. Create `ObjectName.parse()` for typeMap lookup with unified identifier parsing

### 15.2 Phase 2: Implement Codecs

1. Implement codecs for all built-in types (internal package-private classes)
2. Each codec includes full conversion logic (all decodeAsXxx methods)
3. All codecs check overflow and throw SQLException (as in PgResultSet.readLongValue())
4. TimestampCodec, DateCodec etc. include all java.time conversions (including ZonedDateTime)
5. Create DomainCodec for domain types
6. Create GenericRangeCodec for all range types (user-defined ranges via PgType.typelem)
7. Create geometric codecs (PointCodec, BoxCodec, etc.)
8. Create HstoreCodec (built-in)
9. Create ByteaCodec with InputStream support for getObject(i, InputStream.class)
10. ByteConverter retained, used internally by codecs
11. Create unit tests for each codec

### 15.3 Phase 3: Integration

1. Refactor `PgResultSet` to use codecs with cached codec in Field
2. Refactor `PgArray` to use `ArrayCodec` with CodecContext snapshot
3. Create generic `PgSQLInput<BufferType>` base with `PgSQLInputBinary` and `PgSQLInputText`
4. Create generic `PgSQLOutput<BufferType>` base with `PgSQLOutputBinary` and `PgSQLOutputText`
5. Add codec registration/unregistration methods to `PGConnection` interface
6. Refactor `PgPreparedStatement.setObject()` with SQLType support + describe strategy
7. Implement setNull() with describe for Types.OTHER
8. Add `getobject.timestamp`, `getobject.date`, `map.pg_type.boolean` properties
9. Integrate TypeInfoCache with DatabaseMetaData.getTypeInfo() and getUDTs()
10. Implement Connection.createStruct() returning PGStruct
11. Add getObject(i, Integer[].class) support for array columns
12. Implement CallableStatement STRUCT support for OUT parameters

### 15.4 Phase 4: Cleanup

1. Remove `SQLDataCodec`, `PGObjectCodec`, `FieldValueCodec` (branch-only classes)
2. Remove duplicate conversion logic from PgResultSet
3. Remove hstore from BASE_TYPES (resolved dynamically by typeName)
4. Unify JavaTypeRegistry to use ConcurrentHashMap throughout
5. Add Short.class → INT2_ARRAY mapping
6. Create PGUnknownBinary class for unknown binary types
7. Create PGStruct class for Connection.createStruct()

---

## 16. Files to Create/Modify

### New Files

```
org/postgresql/api/                  # PUBLIC API package
├── Experimental.java                # @Experimental annotation

org/postgresql/api/codec/            # PUBLIC API package
├── Codec.java                       # Base interface (@Experimental)
├── BinaryCodec.java                 # Binary format interface (@Experimental)
├── TextCodec.java                   # Text format interface (@Experimental)

org/postgresql/jdbc/
├── CodecContext.java                # Immutable context with withTypeMap()
├── CodecDepth.java                  # ThreadLocal for encode/decode nesting protection
├── PgSQLInput.java                  # Generic base class
├── PgSQLInputBinary.java            # Binary format implementation
├── PgSQLInputText.java              # Text format implementation
├── PgSQLOutput.java                 # Generic base class for SQLData encoding
├── PgSQLOutputBinary.java           # Binary format output implementation
├── PgSQLOutputText.java             # Text format output implementation
├── PGSQLType.java                   # Enum for JDBC 4.2 SQLType support
├── PGStruct.java                    # java.sql.Struct implementation
├── codec/                           # Internal package (package-private)
│   ├── Int2Codec.java
│   ├── Int4Codec.java
│   ├── Int8Codec.java
│   ├── Float4Codec.java
│   ├── Float8Codec.java
│   ├── NumericCodec.java            # With overflow checking
│   ├── BoolCodec.java
│   ├── TextCodecImpl.java
│   ├── VarcharCodec.java
│   ├── ByteaCodec.java              # With InputStream support
│   ├── DateCodec.java               # With getobject.date property support
│   ├── TimeCodec.java
│   ├── TimetzCodec.java
│   ├── TimestampCodec.java          # With getobject.timestamp, ZonedDateTime support
│   ├── TimestamptzCodec.java        # With ZonedDateTime (UTC) support
│   ├── UuidCodec.java
│   ├── JsonCodec.java
│   ├── JsonbCodec.java
│   ├── ArrayCodec.java
│   ├── CompositeCodec.java          # For named composite types
│   ├── DomainCodec.java             # For domain types
│   ├── GenericRangeCodec.java       # For all range types (including user-defined)
│   ├── HstoreCodec.java             # Built-in hstore
│   ├── PointCodec.java              # Geometric
│   ├── LineCodec.java
│   ├── LsegCodec.java
│   ├── BoxCodec.java
│   ├── PathCodec.java
│   ├── PolygonCodec.java
│   ├── CircleCodec.java
│   └── FallbackCodec.java           # Returns PGUnknownBinary/PGobject

org/postgresql/util/
├── PGUnknownBinary.java             # For unknown binary types (separate from PGobject)
├── PGRange.java                     # Range type representation
```

### Modified Files

```
org/postgresql/jdbc/
├── TypeInfoCache.java               # Add typdelim/typbasetype, DDL-based invalidation, getUDTs
├── JavaTypeRegistry.java            # Add Short.class mapping, SQLType support
├── CodecRegistry.java               # Rewrite with single Caffeine cache, per-Driver SPI scope
├── PgType.java                      # Add typdelim, typbasetype, lazy fields
├── ObjectName.java                  # Add parse() with unified identifier parsing
├── Field.java                       # Add getPgType(), getCodec() with caching
├── PgResultSet.java                 # Use codecs instead of switch blocks
├── PgArray.java                     # Use ArrayCodec, store CodecContext snapshot
├── PgPreparedStatement.java         # Use codecs for setXxx(), SQLType support, describe for setNull
├── PgCallableStatement.java         # Add STRUCT support for OUT parameters
├── PgConnection.java                # Implement registerCodec(), unregisterCodec(), createStruct()
├── PgDatabaseMetaData.java          # Implement getUDTs() using TypeInfoCache

org/postgresql/
├── PGConnection.java                # Add registerCodec(), unregisterCodec(), getCodecRegistry()
```

### Files to Delete (branch-only, not in master)

```
org/postgresql/jdbc/
├── SQLDataCodec.java                # Logic moves to CompositeCodec + PgSQLInput*
├── PGObjectCodec.java               # Logic moves to CompositeCodec
├── FieldValueCodec.java             # Logic moves to individual codecs
```

### Files to Keep

```
org/postgresql/core/ByteConverter.java  # Retained for protocol handling
org/postgresql/util/PGobject.java       # Retained for BC
org/postgresql/util/PGBinaryObject.java # Existing interface, retained
org/postgresql/util/PGInterval.java     # Retained for BC
org/postgresql/geometric/PGpoint.java   # Retained for BC
org/postgresql/geometric/PGbox.java     # Retained for BC
... (all PG* classes retained)
```

---

## 17. Performance Considerations

### 17.1 No Boxing for Primitives

**Decoding (ResultSet):** Codec interfaces provide primitive specializations (`decodeAsInt`, `decodeAsLong`, etc.)
that avoid boxing when reading results.

```java
// BAD: Current approach with boxing
public int getInt(int columnIndex) {
    Object obj = decode(data);          // Boxing: int → Integer
    return ((Number) obj).intValue();   // Unboxing
}

// GOOD: New approach
public int getInt(int columnIndex) {
    return codec.decodeAsInt(data, type, ctx);  // No boxing
}
```

**Encoding (PreparedStatement):** The codec encode API is `encodeBinary(Object value, ...)` which
requires boxing. Typed setters like `setInt(int)`, `setLong(long)`, `setFloat(float)`,
`setDouble(double)`, `setShort(short)` use `ByteConverter` directly to avoid boxing overhead.
This is intentional — adding `encodeFromInt(int)` specializations to the codec interface would
add significant API surface for minimal benefit, since the number of primitive setters is fixed
and small. Codecs are used for `setObject()` where the value is already boxed.

### 17.2 Codec Caching

- Codecs are singletons (stateless)
- Single OID → Codec cache via Caffeine (size-based LRU)
- **Field caches codec reference** after first lookup (no repeated registry access)
- instanceof check at call site (cheap)
- Cache invalidated on DDL command detection

### 17.3 Array Element Codec Caching

```java
// Element codec is cached before loop - single lookup
BinaryCodec elementCodec = ctx.getCodecs().getBinaryCodec(elementOid);
for (int i = 0; i < length; i++) {
    // Use cached elementCodec
    result[i] = elementCodec.decodeBinary(elementData[i], elementType, ctx);
}
```

### 17.4 No Intermediate Representations

- Streaming API: data decoded directly to target type
- No List<DecodedField> intermediate step
- SQLInput reads fields on demand via codec delegation

### 17.5 Immutable CodecContext

- Thread-safe by design
- `withTypeMap()` creates lightweight copy
- No synchronization needed during decode

---

## 18. Testing Strategy

### 18.1 Unit Tests (per codec)

```java
class Int4CodecTest {
    @Test
    void decodeBinary_positiveValue() {
        byte[] data = {0x00, 0x00, 0x00, 0x2A};  // 42
        int result = Int4Codec.INSTANCE.decodeAsInt(data, INT4_TYPE, mockContext);
        assertEquals(42, result);
    }

    @Test
    void decodeBinary_negativeValue() { ... }

    @Test
    void decodeText_positiveValue() { ... }

    @Test
    void encodeBinary_roundTrip() { ... }

    @Test
    void decodeBinaryAs_toLong() { ... }

    @Test
    void decodeBinaryAs_toString() { ... }

    @Test
    void decodeBinaryAs_toBigDecimal() { ... }
}

class NumericCodecTest {
    @Test
    void decodeAsInt_overflow_throwsSQLException() {
        // NUMERIC value too large for int should throw SQLException
        // Reference: PgResultSet.readLongValue() implementation
    }
}

class TimestampCodecTest {
    @Test
    void decodeBinaryAs_toLocalDateTime() { ... }

    @Test
    void decodeBinaryAs_toLocalDate() { ... }

    @Test
    void decodeBinaryAs_toInstant_usesTimezone() { ... }

    @Test
    void decodeBinaryAs_toOffsetDateTime() { ... }

    @Test
    void getObject_defaultProperty_returnsSqlTimestamp() { ... }

    @Test
    void getObject_javaTimeProperty_returnsLocalDateTime() { ... }
}

class PgSQLInputTest {
    @Test
    void wasNull_returnsTrueAfterNullField() { ... }

    @Test
    void wasNull_returnsFalseAfterNonNullField() { ... }
}
```

### 18.2 Integration Tests

```java
class CodecIntegrationTest {
    @Test
    void getInt_fromInt4Column() {
        rs.getInt(1);  // Uses Int4Codec
    }

    @Test
    void getInt_fromNumericColumn() {
        rs.getInt(1);  // Uses NumericCodec.decodeAsInt()
    }

    @Test
    void getObject_asSQLData() {
        MyStruct result = rs.getObject(1, MyStruct.class);
        // Uses CompositeCodec → PgSQLInputBinary → field codecs
    }

    @Test
    void getArray_withTypeMap() {
        Map<String, Class<?>> map = Map.of("my_struct", MyStruct.class);
        Object[] array = (Object[]) rs.getArray(1).getArray(map);
        // Uses ArrayCodec → CompositeCodec → SQLInput
    }

    @Test
    void getObject_domainType() {
        // Domain type uses base type codec
    }

    @Test
    void getObject_rangeType() {
        PGRange<?> range = rs.getObject(1, PGRange.class);
        // Uses RangeCodec
    }

    @Test
    void setObject_withSQLType() {
        ps.setObject(1, value, JDBCType.VARCHAR);
        // Uses JavaTypeRegistry.getOidForSQLType()
    }

    @Test
    void setObject_withPGSQLType() {
        ps.setObject(1, value, PGSQLType.JSONB);
        // Uses PGSQLType enum
    }
}

class CompositeEscapingTest {
    @Test
    void textFormat_doubleQuoteEscape() {
        // Test "" escape in composite text format
    }

    @Test
    void textFormat_backslashQuoteEscape() {
        // Test \" escape in composite text format
    }

    @Test
    void textFormat_combinedEscapes() {
        // Test combination of escaping methods
    }
}

class NestingDepthTest {
    @Test
    void deeplyNestedType_throwsOnExceedingLimit() {
        // Create type nested 65 levels deep, expect SQLException
    }
}

class CacheInvalidationTest {
    @Test
    void createType_invalidatesCache() {
        // Execute CREATE TYPE, verify cache invalidated
    }

    @Test
    void dropType_invalidatesCache() {
        // Execute DROP TYPE, verify cache invalidated
    }
}
```

---

## 19. Open Questions Resolved

| Question | Resolution |
|----------|------------|
| How to minimize duplication? | Single codec per type, used by ResultSet, Array, SQLInput |
| Boxing overhead? | Specialized decodeAsXxx methods in interface |
| Binary vs Text? | Separate interfaces, PgSQLInput generic base with two implementations |
| Codec registration? | SPI (ServiceLoader loads Codec directly) + programmatic via PGConnection |
| Component ownership? | CodecContext owns TypeInfoCache, CodecRegistry, JavaTypeRegistry |
| CodecContext thread-safety? | Immutable with withTypeMap() for per-call customization |
| Nesting depth? | ThreadLocal counter, max 64, safe with virtual threads |
| hstore schema? | Built-in codec, resolved dynamically by typeName |
| typeMap passing? | Stored in CodecContext, accessed via ctx.getTypeMap() |
| Fields loading? | Lazy via get() + putIfAbsent() (avoids computeIfAbsent deadlock) |
| Cache implementation? | Single Caffeine cache, instanceof check at call site |
| Cache invalidation? | DDL command detection (CREATE/DROP/ALTER in status) |
| Codec lookup in ResultSet? | Field caches codec reference after first lookup |
| Array element codec? | Cached before loop for performance |
| Anonymous row types (2249)? | FallbackCodec, no special handling (text has no type info) |
| setObject OID resolution? | Describe (cached in statement) + Java class mapping + SQLType support |
| Type conversions? | Each codec handles all conversions internally |
| Overflow handling? | SQLException (reference: PgResultSet.readLongValue()) |
| PGobject classes? | All preserved for backward compatibility |
| Unknown binary types? | PGUnknownBinary (separate class from PGobject) |
| ByteConverter? | Retained for protocol, used by codecs |
| Package structure? | Interfaces in org.postgresql.api.codec, impls in org.postgresql.jdbc.codec (internal) |
| API stability? | @Experimental for first release |
| SQLType support? | PGSQLType enum + JavaTypeRegistry.getOidForSQLType() |
| Date/time defaults? | java.sql by default, getobject.* properties for java.time |
| Range types? | Separate codec instances per range type |
| Domain types? | DomainCodec delegates to base type, constraints validated by server |
| Enum types? | String only, no Java Enum support |
| Geometric types? | Built-in codecs, return existing PGpoint, PGbox, etc. |
| Schema conflicts? | Last registered wins, schema not considered |
| Primitive class in getObject? | Not supported, JDBC doesn't require it |
| wasNull() in SQLInput? | lastWasNull flag, updated on each read |
| SPI loading? | ServiceLoader loads Codec directly at first connection |
| TypeReference for generics? | Deferred to future version |
| Auto-registration? | No auto-registration; explicit via SPI or API only |
| Boolean JDBC type? | Configurable via map.pg_type.boolean=bit\|boolean |
| Timezone source? | PgPreparedStatement.getDefaultCalendar() |
| JSON library integration? | None; returns String, user deserializes |
| ZonedDateTime for timestamptz? | Supported, always returns UTC time zone |
| setTypeMap() behavior? | Creates new CodecContext, replaces current in connection |
| SQLOutput implementation? | Generic PgSQLOutput base with binary/text subclasses |
| SPI isolation? | Per-Driver scope (DriverManager level) |
| ParameterMetaData codecs? | Considers Codec.getDefaultJavaType() for mapping |
| ObjectName.parse()? | Retained for typeMap lookup with schema.type parsing |
| CallableStatement STRUCT? | Included in scope for OUT parameters |
| Connection.createStruct()? | Implemented, returns PGStruct |
| createArrayOf implementation? | Uses TypeInfoCache for OID lookup |
| Encode/Decode depth? | Single CodecDepth counter (shared for both) |
| setNull() for Types.OTHER? | Uses describe to get proper OID |
| computeIfAbsent safety? | get() + putIfAbsent() pattern (avoids deadlock) |
| Custom range types? | GenericRangeCodec via PgType.typelem |
| Multirange types? | Deferred to future version |
| @Experimental annotation? | Own annotation in org.postgresql.api.Experimental |
| DatabaseMetaData.getUDTs()? | Implemented using TypeInfoCache |
| JSON JDBC type? | Returns Types.OTHER (not CLOB) |
| Pool reset codecs? | unregisterCodec() method for explicit removal |
| bytea streaming? | Add InputStream support for large binary data |
| DDL cache filter? | Any DDL command invalidates cache (not just types) |
| Large Objects? | Outside scope, existing API retained |
| enquoteIdentifier? | Logic unified in ObjectName class |
| BC mapping table? | Added to spec with version-specific changes |
| Minimum PG version? | 9.1 (tested in CI through 16+) |
| Escape rules reference? | Link to PostgreSQL composite type docs |
| MyBatis compatibility? | No conflict with codecs (uses ResultSetMetaData) |
