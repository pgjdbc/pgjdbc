/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UTF-8 encoder which validates input and is optimized for jdk 9+ where {@code String} objects are backed by
 * {@code byte[]}.
 * @author Brett Okken
 */
final class ByteOptimizedUTF8Encoder extends OptimizedUTF8Encoder {

  /**
   * {@code MethodHandle} for a jdk 9+ {@code byteArrayViewVarHandle} for {@code long[]} using the {@link ByteOrder#nativeOrder()}.
   * The method signature is {@code long get(byte[], int)}.
   */
  private static final @Nullable MethodHandle VAR_HANDLE_GET_LONG;

  /**
   * {@code MethodHandle} for the actual implementation to use at runtime.
   */
  private static final MethodHandle NEGATIVE_INDEX;

  /**
   * Mask to identify if the sign bit for any byte is set.
   */
  private static final long POSITIVE_MASK = 0x7F7F7F7F7F7F7F7FL;

  static {
    MethodHandle varHandleGetLong = null;
    MethodHandle negativeIdx = null;
    final MethodHandles.Lookup lookup = MethodHandles.lookup();
    final MethodType negIdxType = MethodType.methodType(int.class, byte[].class, int.class, int.class);
    try {
      final Class<?> varHandleClazz = Class.forName("java.lang.invoke.VarHandle", true, null);
      final Method byteArrayViewHandle = MethodHandles.class.getDeclaredMethod("byteArrayViewVarHandle", new Class[] {Class.class, ByteOrder.class});
      final Object varHandle = byteArrayViewHandle.invoke(null, long[].class, ByteOrder.nativeOrder());
      final Class<?> accessModeEnum = Class.forName("java.lang.invoke.VarHandle$AccessMode", true, null);
      @SuppressWarnings({ "unchecked", "rawtypes" })
      final Object getAccessModeEnum = Enum.valueOf((Class)accessModeEnum, "GET");
      final Method toMethodHandle = varHandleClazz.getDeclaredMethod("toMethodHandle", accessModeEnum);
      varHandleGetLong = (MethodHandle) toMethodHandle.invoke(varHandle, getAccessModeEnum);
      negativeIdx = lookup.findStatic(ByteOptimizedUTF8Encoder.class, "varHandleNegativeIndex", negIdxType);
    } catch (Throwable t) {
      Logger.getLogger(ByteOptimizedUTF8Encoder.class.getName())
            .log(Level.INFO, "Failure trying to load byteArrayViewVarHandle.", t);
      varHandleGetLong = null;
      try {
        negativeIdx = lookup.findStatic(ByteOptimizedUTF8Encoder.class, "legacyNegativeIndex", negIdxType);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
    VAR_HANDLE_GET_LONG = varHandleGetLong;
    NEGATIVE_INDEX = negativeIdx;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String decode(byte[] encodedString, int offset, int length) throws IOException {
    //for very short strings going straight to chars is up to 30% faster
    if (length < 24) {
      return charDecode(encodedString, offset, length);
    }
    //check to see if there are any negative byte values
    final int indexOfNegativeByte = negativeIndex(encodedString, offset, length);
    // if indexOfNegativeByte is -1, that means ascii, use String constructor
    return indexOfNegativeByte == -1 ? new String(encodedString, offset, length, StandardCharsets.US_ASCII)
                                     : slowDecode(encodedString, offset, length, indexOfNegativeByte);
  }

  /**
   * Decodes to {@code char[]} in presence of non-ascii values after first copying all known ascii chars directly
   * from {@code byte[]} to {@code char[]}.
   */
  private synchronized String slowDecode(byte[] encodedString, int offset, int length, int curIdx) throws IOException {
    final char[] chars = getCharArray(length);
    int out = 0;
    for (int i = offset; i < curIdx; ++i) {
      chars[out++] = (char) encodedString[i];
    }
    return decodeToChars(encodedString, curIdx, length - (curIdx - offset), chars, out);
  }

  /**
   * Returns index into <i>bytes</i> which is at or shortly before a negative value. Will return {@code -1}
   * if all values are non-negative.
   * @param bytes The bytes to check.
   * @param offset The offset into <i>bytes</i> to start checking.
   * @param length The number of bytes to check.
   * @return {@code -1} if no values in range are negative. A non-negative value indicates that some value
   * at or after the returned index is negative.
   */
  private static int negativeIndex(byte[] bytes, int offset, int length) {
    try {
      return (int) NEGATIVE_INDEX.invokeExact(bytes, offset, length);
    } catch (RuntimeException e) {
      throw e;
    } catch (Error e) {
      throw e;
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * This method is only called if {@link #VAR_HANDLE_GET_LONG} cannot be loaded at runtime. It does
   * a traditional loop over <i>bytes</i> to see if any are negative.
   */
  @SuppressWarnings("unused") //called via MethodHandle
  private static int legacyNegativeIndex(byte[] bytes, int offset, int length) {
    for (int i = offset, j = offset + length; i < j; ++i) {
      // bytes are signed values. all ascii values are positive
      if (bytes[i] < 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Uses {@link #VAR_HANDLE_GET_LONG} to read 8 bytes at a time as a {@code long} from <i>bytes</i> to check
   * if any are negative using {@link #POSITIVE_MASK}.
   */
  @SuppressWarnings("unused") //called via MethodHandle
  private static int varHandleNegativeIndex(byte[] bytes, int offset, int length) throws Throwable {
    final int toIdx = offset + length;
    int i = offset;
    for (int j = toIdx - 7; i < j; i += 8) {
      //the only way to get here is if this value is not null
      @SuppressWarnings("nullness")
      final long l = (long) VAR_HANDLE_GET_LONG.invokeExact(bytes, i);
      if ((l & POSITIVE_MASK) != l) {
        //we could do work to find a more specific value for i
        //but it does not really matter, as the bytes just have to be
        //looped over again anyway in either slowDecode or decodeToChars
        return i;
      }
    }
    //take care of any remaining (up to 7)
    for ( ; i<toIdx; ++i) {
      if (bytes[i] < 0) {
        return i;
      }
    }
    return -1;
  }
}
