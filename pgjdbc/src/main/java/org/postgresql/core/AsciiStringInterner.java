/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provides the canonicalization/interning of {@code String} instances which contain only ascii characters,
 * keyed by the {@code byte[]} representation (in ascii).
 *
 * <p>
 * The values are stored in {@link SoftReference}s, allowing them to be garbage collected if not in use and there is
 * memory pressure.
 * </p>
 *
 * <p>
 * <b>NOTE:</b> Instances are safe for concurrent use.
 * </p>
 *
 * @author Brett Okken
 */
final class AsciiStringInterner {

  private abstract static class BaseKey {
    private final int hash;

    BaseKey(int hash) {
      this.hash = hash;
    }

    @Override
    public final int hashCode() {
      return hash;
    }

    @Override
    public final boolean equals(@Nullable Object obj) {
      if (obj == this) {
        return true;
      }
      if (!(obj instanceof BaseKey)) {
        return false;
      }
      final BaseKey other = (BaseKey) obj;
      return equalsBytes(other);
    }

    abstract boolean equalsBytes(BaseKey other);

    abstract boolean equals(byte[] other, int offset, int length);

    abstract void appendString(StringBuilder sb);
  }

  /**
   * Only used for lookups, never to actually store entries.
   */
  private static class TempKey extends BaseKey {
    final byte[] bytes;
    final int offset;
    final int length;

    TempKey(int hash, byte[] bytes, int offset, int length) {
      super(hash);
      this.bytes = bytes;
      this.offset = offset;
      this.length = length;
    }

    @Override
    boolean equalsBytes(BaseKey other) {
      return other.equals(bytes, offset, length);
    }

    @Override
    public boolean equals(byte[] other, int offset, int length) {
      return arrayEquals(this.bytes, this.offset, this.length, other, offset, length);
    }

    @Override
    void appendString(StringBuilder sb) {
      for (int i = offset, j = offset + length; i < j; ++i) {
        sb.append((char) bytes[i]);
      }
    }
  }

  /**
   * Instance used for inserting values into the cache. The {@code byte[]} must be a copy
   * that will never be mutated.
   */
  private static final class Key extends BaseKey {
    final byte[] key;

    Key(byte[] key, int hash) {
      super(hash);
      this.key = key;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean equalsBytes(BaseKey other) {
      return other.equals(key, 0, key.length);
    }

    @Override
    public boolean equals(byte[] other, int offset, int length) {
      return arrayEquals(this.key, 0, this.key.length, other, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void appendString(StringBuilder sb) {
      for (int i = 0; i < key.length; ++i) {
        sb.append((char) key[i]);
      }
    }
  }

  /**
   * Custom {@link SoftReference} implementation which maintains a reference to the key in the cache,
   * which allows aggressive cleaning when garbage collector collects the {@code String} instance.
   */
  private final class StringReference extends SoftReference<String> {

    private final BaseKey key;

    StringReference(BaseKey key, String referent) {
      super(referent, refQueue);
      this.key = key;
    }

    void dispose() {
      cache.remove(key, this);
    }
  }

  /**
   * Contains the canonicalized values, keyed by the ascii {@code byte[]}.
   */
  final ConcurrentMap<BaseKey, SoftReference<String>> cache = new ConcurrentHashMap<>(128);

  /**
   * Used for {@link Reference} as values in {@code cache}.
   */
  final ReferenceQueue<String> refQueue = new ReferenceQueue<>();

  /**
   * Preemptively populates a value into the cache. This is intended to be used with {@code String} constants
   * which are frequently used. While this can work with other {@code String} values, if <i>val</i> is ever
   * garbage collected, it will not be actively removed from this instance.
   *
   * @param val The value to intern. Must not be {@code null}.
   * @return Indication if <i>val</i> is an ascii String and placed into cache.
   */
  public boolean putString(String val) {
    //ask for utf-8 so that we can detect if any of the characters are not ascii
    final byte[] copy = val.getBytes(StandardCharsets.UTF_8);
    final int hash = hashKey(copy, 0, copy.length);
    if (hash == 0) {
      return false;
    }
    final Key key = new Key(copy, hash);
    //we are assuming this is a java interned string from , so this is unlikely to ever be
    //reclaimed. so there is no value in using the custom StringReference or hand off to
    //the refQueue.
    //on the outside chance it actually does get reclaimed, it will just hang around as an
    //empty reference in the map unless/until attempted to be retrieved
    cache.put(key, new SoftReference<String>(val));
    return true;
  }

  /**
   * Produces a {@link String} instance for the given <i>bytes</i>. If all are valid ascii (i.e. {@code >= 0})
   * either an existing value will be returned, or the newly created {@code String} will be stored before being
   * returned.
   *
   * <p>
   * If non-ascii bytes are discovered, the <i>encoding</i> will be used to
   * {@link Encoding#decode(byte[], int, int) decode} and that value will be returned (but not stored).
   * </p>
   *
   * @param bytes The bytes of the String. Must not be {@code null}.
   * @param offset Offset into <i>bytes</i> to start.
   * @param length The number of bytes in <i>bytes</i> which are relevant.
   * @param encoding To use if non-ascii bytes seen.
   * @return Decoded {@code String} from <i>bytes</i>.
   * @throws IOException If error decoding from <i>Encoding</i>.
   */
  public String getString(byte[] bytes, int offset, int length, Encoding encoding) throws IOException {
    if (length == 0) {
      return "";
    }

    final int hash = hashKey(bytes, offset, length);
    // 0 indicates the presence of a non-ascii character - defer to encoding to create the string
    if (hash == 0) {
      return encoding.decode(bytes, offset, length);
    }
    cleanQueue();
    // create a TempKey with the byte[] given
    final TempKey tempKey = new TempKey(hash, bytes, offset, length);
    SoftReference<String> ref = cache.get(tempKey);
    if (ref != null) {
      final String val = ref.get();
      if (val != null) {
        return val;
      }
    }
    // in order to insert we need to create a "real" key with copy of bytes that will not be changed
    final byte[] copy = Arrays.copyOfRange(bytes, offset, offset + length);
    final Key key = new Key(copy, hash);
    final String value = new String(copy, StandardCharsets.US_ASCII);

    // handle case where a concurrent thread has populated the map or existing value has cleared reference
    ref = cache.compute(key, (k,v) -> {
      if (v == null) {
        return new StringReference(key, value);
      }
      final String val = v.get();
      return val != null ? v : new StringReference(key, value);
    });

    return castNonNull(ref.get());
  }

  /**
   * Produces a {@link String} instance for the given <i>bytes</i>.
   *
   * <p>
   * If all are valid ascii (i.e. {@code >= 0}) and a corresponding {@code String} value exists, it
   * will be returned. If no value exists, a {@code String} will be created, but not stored.
   * </p>
   *
   * <p>
   * If non-ascii bytes are discovered, the <i>encoding</i> will be used to
   * {@link Encoding#decode(byte[], int, int) decode} and that value will be returned (but not stored).
   * </p>
   *
   * @param bytes The bytes of the String. Must not be {@code null}.
   * @param offset Offset into <i>bytes</i> to start.
   * @param length The number of bytes in <i>bytes</i> which are relevant.
   * @param encoding To use if non-ascii bytes seen.
   * @return Decoded {@code String} from <i>bytes</i>.
   * @throws IOException If error decoding from <i>Encoding</i>.
   */
  public String getStringIfPresent(byte[] bytes, int offset, int length, Encoding encoding) throws IOException {
    if (length == 0) {
      return "";
    }

    final int hash = hashKey(bytes, offset, length);
    // 0 indicates the presence of a non-ascii character - defer to encoding to create the string
    if (hash == 0) {
      return encoding.decode(bytes, offset, length);
    }
    cleanQueue();
    // create a TempKey with the byte[] given
    final TempKey tempKey = new TempKey(hash, bytes, offset, length);
    SoftReference<String> ref = cache.get(tempKey);
    if (ref != null) {
      final String val = ref.get();
      if (val != null) {
        return val;
      }
    }

    return new String(bytes, offset, length, StandardCharsets.US_ASCII);
  }

  /**
   * Process any entries in {@link #refQueue} to purge from the {@link #cache}.
   * @see StringReference#dispose()
   */
  private void cleanQueue() {
    Reference<?> ref;
    while ((ref = refQueue.poll()) != null) {
      ((StringReference)ref).dispose();
    }
  }

  /**
   * Generates a hash value for the relevant entries in <i>bytes</i> as long as all values are ascii ({@code >= 0}).
   * @return hash code for relevant bytes, or {@code 0} if non-ascii bytes present.
   */
  private static int hashKey(byte[] bytes, int offset, int length) {
    int result = 1;
    for (int i = offset, j = offset + length; i < j; ++i) {
      final byte b = bytes[i];
      // bytes are signed values. all ascii values are positive
      if (b < 0) {
        return 0;
      }
      result = 31 * result + b;
    }
    return result;
  }

  /**
   * Performs equality check between <i>a</i> and <i>b</i> (with corresponding offset/length values).
   * <p>
   * The {@code static boolean equals(byte[].class, int, int, byte[], int, int} method in {@link java.util.Arrays}
   * is optimized for longer {@code byte[]} instances than is expected to be seen here.
   * </p>
   */
  static boolean arrayEquals(byte[] a, int aOffset, int aLength, byte[] b, int bOffset, int bLength) {
    if (aLength != bLength) {
      return false;
    }
    //TODO: in jdk9, could use VarHandle to read 4 bytes at a time as an int for comparison
    // or 8 bytes as a long - though we likely expect short values here
    for (int i = 0; i < aLength; ++i) {
      if (a[aOffset + i] != b[bOffset + i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(32 + (8 * cache.size()));
    sb.append("AsciiStringInterner [");
    cache.forEach((k,v) -> {
      sb.append('\'');
      k.appendString(sb);
      sb.append("', ");
    });
    //replace trailing ', ' with ']';
    final int length = sb.length();
    if (length > 21) {
      sb.setLength(sb.length() - 2);
    }
    sb.append(']');
    return sb.toString();
  }
}
