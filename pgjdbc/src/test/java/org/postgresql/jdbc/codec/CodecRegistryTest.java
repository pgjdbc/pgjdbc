/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodecRegistry} resolution: OID-cache coherence, OID-primary identity for
 * built-in types, and the user &gt; SPI &gt; built-in layering.
 */
class CodecRegistryTest {

  /** A minimal codec usable as a registration target; only the marker methods are exercised. */
  private static final class StubCodec implements Codec {
    private final String typeName;

    StubCodec(String typeName) {
      this.typeName = typeName;
    }

    @Override
    public String getPrimaryTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return Object.class;
    }
  }

  /** A binary codec that opts out of binary reads, exercising the read-side capability gate. */
  private static final class BinaryReadOptOutCodec implements BinaryCodec {
    @Override
    public String getPrimaryTypeName() {
      return "binary_read_optout";
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return Object.class;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx) {
      return null;
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return new byte[0];
    }

    @Override
    public boolean decodesBinary() {
      return false;
    }
  }

  /** A user type whose typtype/typcategory leave it unresolved until a name-based codec exists. */
  private static PgType userType(String name, int oid) {
    return new PgType(new ObjectName("public", name), name, oid, 'b', 'U', -1, 0, 0, 0);
  }

  /** A built-in scalar type living in {@code pg_catalog}. */
  private static PgType builtinType(String name, int oid) {
    return new PgType(new ObjectName("pg_catalog", name), name, oid, 'b', 'N', -1, 0, 0, 0);
  }

  /** A user-defined composite type in an arbitrary schema. */
  private static PgType compositeType(String namespace, String name, int oid) {
    return new PgType(new ObjectName(namespace, name), namespace + "." + name, oid, 'c', 'C', -1, 0, 0, 0);
  }

  @Test
  void registerByName_invalidatesOidCache() {
    CodecRegistry registry = new CodecRegistry();
    PgType type = userType("codecregistry_byname", 999_001);

    Codec first = new StubCodec("codecregistry_byname");
    registry.registerByName(first);
    // The first resolve caches `first` for this OID.
    assertSame(first, registry.getByOid(type.getOid(), type));

    Codec second = new StubCodec("codecregistry_byname");
    registry.registerByName(second);
    // The re-registration must invalidate the OID cache; otherwise getByOid would keep
    // returning the stale `first`.
    assertSame(second, registry.getByOid(type.getOid(), type));
  }

  @Test
  void registerAlias_invalidatesOidCache() {
    CodecRegistry registry = new CodecRegistry();
    PgType type = userType("codecregistry_alias", 999_002);

    Codec first = new StubCodec("codecregistry_alias_a");
    registry.registerAlias("codecregistry_alias", first);
    assertSame(first, registry.getByOid(type.getOid(), type));

    Codec second = new StubCodec("codecregistry_alias_b");
    registry.registerAlias("codecregistry_alias", second);
    assertSame(second, registry.getByOid(type.getOid(), type));
  }

  @Test
  void getByOid_resolvesBuiltinByCanonicalOid() {
    CodecRegistry registry = new CodecRegistry();
    assertSame(PointCodec.INSTANCE, registry.getByOid(Oid.POINT, builtinType("point", Oid.POINT)));
    assertSame(Int4Codec.INSTANCE, registry.getByOid(Oid.INT4, builtinType("int4", Oid.INT4)));
  }

  @Test
  void getByOid_userTypeNamedLikeBuiltin_doesNotResolveToBuiltinCodec() {
    CodecRegistry registry = new CodecRegistry();
    // A user composite named "point" in another schema must resolve as a composite,
    // not be captured by the built-in geometric "point" codec via its bare name.
    PgType userPoint = compositeType("myschema", "point", 990_100);
    assertSame(CompositeCodec.INSTANCE, registry.getByOid(userPoint.getOid(), userPoint));
  }

  @Test
  void getByOid_userNameRegistrationOverridesBuiltin() {
    CodecRegistry registry = new CodecRegistry();
    PgType int4 = builtinType("int4", Oid.INT4);
    assertSame(Int4Codec.INSTANCE, registry.getByOid(Oid.INT4, int4));

    Codec custom = new StubCodec("int4");
    registry.registerByName(custom);
    // The user layer outranks the built-in OID identity.
    assertSame(custom, registry.getByOid(Oid.INT4, int4));
    assertNotSame(Int4Codec.INSTANCE, registry.getByOid(Oid.INT4, int4));
  }

  @Test
  void getByName_resolvesBuiltinByBareName() {
    CodecRegistry registry = new CodecRegistry();
    // pg_catalog types are reachable by their bare name.
    assertSame(Int4Codec.INSTANCE, registry.getByName("int4"));
    assertSame(PointCodec.INSTANCE, registry.getByName("point"));
    // hstore is a bare-name extension codec.
    assertSame(HstoreCodec.INSTANCE, registry.getByName("hstore"));
    // No codec is registered for the array type name.
    assertNull(registry.getByName("_int4"));
  }

  @Test
  void canDecodeBinary_followsCodecCapability() {
    CodecRegistry registry = new CodecRegistry();
    // Every geometric codec, point and circle alike, decodes binary.
    assertTrue(registry.canDecodeBinary(Oid.POINT, builtinType("point", Oid.POINT)));
    assertTrue(registry.canDecodeBinary(Oid.CIRCLE, builtinType("circle", Oid.CIRCLE)));

    // A binary codec that opts out of binary reads is gated by the capability, not instanceof.
    Codec optOut = new BinaryReadOptOutCodec();
    registry.registerByName(optOut);
    PgType type = userType("binary_read_optout", 990_200);
    assertSame(optOut, registry.getByOid(type.getOid(), type));
    assertFalse(registry.canDecodeBinary(type.getOid(), type));
  }

  @Test
  void resetCustomCodecs_clearsEveryUserLayerRegistration() {
    CodecRegistry registry = new CodecRegistry();

    Codec byName = new StubCodec("reset_byname");
    registry.registerByName(byName);

    Codec aliased = new StubCodec("reset_alias_target");
    registry.registerAlias("reset_alias", aliased);

    Codec custom = new StubCodec("reset_custom");
    registry.registerCustomCodec(custom);

    Codec byOid = new StubCodec("reset_byoid");
    PgType byOidType = userType("reset_byoid", 999_010);
    registry.registerByOid(byOidType.getOid(), byOid);

    // All four user-layer registrations resolve before the reset.
    assertSame(byName, registry.getByName("reset_byname"));
    assertSame(aliased, registry.getByName("reset_alias"));
    assertSame(custom, registry.getByName("reset_custom"));
    assertSame(byOid, registry.getByOid(byOidType.getOid(), byOidType));

    registry.resetCustomCodecs();

    // None survive. The name registrations from registerByName/registerAlias, which the old
    // customCodecNames tracking missed and thus leaked across pooled logical connections, are
    // cleared along with the registerCustomCodec and registerByOid bindings.
    assertNull(registry.getByName("reset_byname"));
    assertNull(registry.getByName("reset_alias"));
    assertNull(registry.getByName("reset_custom"));
    assertNotSame(byOid, registry.getByOid(byOidType.getOid(), byOidType));
  }

  @Test
  void unregisterCustomCodec_removesRegisterByNameRegistration() {
    CodecRegistry registry = new CodecRegistry();

    Codec byName = new StubCodec("unregister_byname");
    registry.registerByName(byName);
    assertSame(byName, registry.getByName("unregister_byname"));

    // unregisterCustomCodec removes any user-layer codec of that name, not only those added
    // through registerCustomCodec.
    registry.unregisterCustomCodec("unregister_byname");
    assertNull(registry.getByName("unregister_byname"));
  }

  @Test
  void canDecodeText_followsCodecCapability() {
    CodecRegistry registry = new CodecRegistry();
    // Every geometric codec, point and circle alike, can also read text.
    assertTrue(registry.canDecodeText(Oid.POINT, builtinType("point", Oid.POINT)));
    assertTrue(registry.canDecodeText(Oid.CIRCLE, builtinType("circle", Oid.CIRCLE)));

    // A binary-only codec cannot decode text.
    Codec optOut = new BinaryReadOptOutCodec();
    registry.registerByName(optOut);
    PgType type = userType("binary_read_optout", 990_201);
    assertFalse(registry.canDecodeText(type.getOid(), type));
  }
}
