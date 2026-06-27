/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.postgresql.api.codec.Codec;
import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodecRegistry} OID-cache coherence: a name-based registration must be
 * observed by a subsequent OID lookup, even when an earlier lookup already cached a codec for
 * that OID.
 */
class CodecRegistryTest {

  /** A minimal codec usable as a registration target; only the marker methods are exercised. */
  private static final class StubCodec implements Codec {
    private final String typeName;

    StubCodec(String typeName) {
      this.typeName = typeName;
    }

    @Override
    public String getTypeName() {
      return typeName;
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return Object.class;
    }
  }

  /** A user type whose typtype/typcategory leave it unresolved until a name-based codec exists. */
  private static PgType userType(String name, int oid) {
    return new PgType(new ObjectName("public", name), name, oid, 'b', 'U', -1, 0, 0, 0);
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
}
