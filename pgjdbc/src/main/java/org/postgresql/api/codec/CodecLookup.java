/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * Read-only view over the driver's codec registry: it resolves a {@link Codec} for a type and
 * reports which wire formats that codec can decode, without exposing registration.
 *
 * <p>Obtain one from {@code OfflineCodecs.defaultRegistry()} and pass it to
 * {@link CodecContextBuilder#registry(CodecLookup)} to share a registry across offline contexts. The
 * object behind this view may be mutable; the interface exposes only the read-only lookups, so a
 * caller cannot register through it. Register custom codecs through the {@link Codec}
 * {@link java.util.ServiceLoader} SPI instead.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface CodecLookup {

  /**
   * Resolves the codec registered for a type name.
   *
   * @param typeName the PostgreSQL type name
   * @return the codec, or null if none is registered for that name
   */
  @Nullable Codec getByName(String typeName);

  /**
   * Resolves the codec for a type by OID, using the descriptor for name- and category-based
   * resolution. Returns the fallback codec for an unknown type, so the result is never null.
   *
   * @param oid the PostgreSQL type OID
   * @param type the type descriptor, or null for a cache-only lookup
   * @return the codec, never null
   */
  Codec getByOid(int oid, @Nullable TypeDescriptor type);

  /**
   * Resolves the codec registered for an exact Java class.
   *
   * @param javaClass the Java class
   * @return the codec, or null if none is registered for that class
   */
  @Nullable Codec getByClass(Class<?> javaClass);

  /**
   * Finds a codec that can encode the given Java class, checking the exact class first and then its
   * superclasses and interfaces.
   *
   * @param javaClass the Java class
   * @return a codec that handles the class, or null if none is found
   */
  @Nullable Codec findCodecFor(Class<?> javaClass);

  /**
   * Resolves the binary codec for a type by OID, or null when the resolved codec has no binary form.
   *
   * @param oid the PostgreSQL type OID
   * @param type the type descriptor
   * @return the binary codec, or null
   */
  @Nullable BinaryCodec getBinaryCodec(int oid, @Nullable TypeDescriptor type);

  /**
   * Resolves the text codec for a type by OID, or null when the resolved codec has no text form.
   *
   * @param oid the PostgreSQL type OID
   * @param type the type descriptor
   * @return the text codec, or null
   */
  @Nullable TextCodec getTextCodec(int oid, @Nullable TypeDescriptor type);

  /**
   * Resolves the binary codec for a type by OID, falling back to a codec that reads the value as raw
   * bytes so the result is never null.
   *
   * @param oid the PostgreSQL type OID
   * @return the binary codec, never null
   */
  BinaryCodec getBinaryCodec(int oid);

  /**
   * Resolves the text codec for a type by OID, falling back to a codec that reads the value as text
   * so the result is never null.
   *
   * @param oid the PostgreSQL type OID
   * @return the text codec, never null
   */
  TextCodec getTextCodec(int oid);

  /**
   * Reports whether the driver can decode this type from the binary wire format.
   *
   * @param oid the PostgreSQL type OID
   * @param type the type descriptor
   * @return true if the resolved codec can decode the binary representation
   */
  boolean canDecodeBinary(int oid, @Nullable TypeDescriptor type);

  /**
   * Reports whether the driver can decode this type from the text wire format.
   *
   * @param oid the PostgreSQL type OID
   * @param type the type descriptor
   * @return true if the resolved codec can decode the text representation
   */
  boolean canDecodeText(int oid, @Nullable TypeDescriptor type);

  /**
   * Returns a snapshot of the built-in codecs pinned to their canonical OID.
   *
   * <p>The map carries only the pinned built-in codecs: user- and service-loaded registrations, the
   * name-only built-ins (such as {@code hstore}), and the fallback codec are absent. Each call
   * allocates a fresh snapshot.</p>
   *
   * @return an unmodifiable {@code OID -> codec} snapshot of the pinned built-in codecs
   */
  Map<Integer, Codec> builtinCodecsByOid();

  /**
   * Reports whether a codec is registered for the given type name.
   *
   * @param typeName the PostgreSQL type name
   * @return true if a codec is registered
   */
  boolean hasCodecForName(String typeName);

  /**
   * Reports whether a codec is registered for the given Java class.
   *
   * @param javaClass the Java class
   * @return true if a codec is registered
   */
  boolean hasCodecForClass(Class<?> javaClass);
}
