/**
 * Copyright (c) 2013, impossibl.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of impossibl.com nor the names of its contributors may
 *    be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.postgresql.util;

import org.postgresql.system.Context;
import org.postgresql.types.Type;

import java.io.IOException;
import java.nio.charset.Charset;

public class ByteBuf {

  public static byte[][] allocAll(int count) {
    byte[][] buffers = new byte[count][];
    for (int c = 0; c < count; ++c)
      buffers[c] = new byte[0];
    return buffers;
  }

  public static byte[][] duplicateAll(byte[][] buffers) {
    buffers = buffers.clone();
    for (int c = 0; c < buffers.length; ++c)
      buffers[c] = buffers[c].clone();
    return buffers;
  }

  public static byte[][] retainedDuplicateAll(byte[][] buffers) {
    return duplicateAll(buffers);
  }

  public static void releaseAll(byte[][] byteBufs) {
    for (int c = 0; c < byteBufs.length; ++c) {
      byteBufs[c] = null;
    }
  }

  public interface EncodeFunction {
    void encode() throws IOException;
  }

  public static byte[][] encode(CharSequence[] textBuffers) {
    byte[][] binaryBuffers = new byte[textBuffers.length][];
    for (int bufferIdx = 0; bufferIdx < textBuffers.length; ++bufferIdx) {
      binaryBuffers[bufferIdx] = textBuffers[bufferIdx].toString().getBytes();
    }
    return binaryBuffers;
  }

  public static void lengthEncodeBinary(Type.Codec.Encoder<byte[]> encoder, Context context, Type type, Object value, Object sourceContext, byte[] buffer) throws IOException {
    lengthEncode(buffer, value, () -> encoder.encode(context, type, value, sourceContext, buffer));
  }

  public static int lengthEncode(byte[] buffer, Object value, EncodeFunction encode) throws IOException {

    int lengthOff = 0;

    if (value == null) {
      return lengthOff;
    }

    int dataOff = 0;

    encode.encode();

    return lengthOff;
  }

  public interface DecodeFunction {
    Object decode(byte[] buffer) throws IOException;
  }

  public static Object lengthDecodeBinary(Type.Codec.Decoder<byte[]> decoder, Context context, Type type, Short typeLength, Integer typeModifier, byte[] buffer, Class<?> targetClass, Object targetContext) throws IOException {
    return lengthDecode(buffer, data -> decoder.decode(context, type, typeLength, typeModifier, data, targetClass, targetContext));
  }

  public static Object lengthDecode(byte[] buffer, DecodeFunction decode) throws IOException {

    int length = buffer.length;
    if (length == 0) {
      return null;
    }

    byte[] data = new byte[length];
    System.arraycopy(buffer, 0, data, 0, length);

    try {
      return decode.decode(data);
    }
    finally {
      // Release any resources
    }
  }

  public static String readCString(byte[] buffer, Charset charset) {

    int strLen = 0;
    for (byte b : buffer) {
      if (b == 0) {
        break;
      }
      strLen++;
    }

    byte[] bytes = new byte[strLen];
    System.arraycopy(buffer, 0, bytes, 0, strLen);

    return new String(bytes, charset);
  }

  public static void writeCString(byte[] buffer, String val, Charset charset) {

    byte[] valBytes = val.getBytes(charset);
    System.arraycopy(valBytes, 0, buffer, 0, valBytes.length);
    buffer[valBytes.length] = 0;
  }
}
