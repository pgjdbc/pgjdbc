/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.util;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wrapper around a length-limited InputStream.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public final class StreamWrapper implements Closeable {

  private static final int MAX_MEMORY_BUFFER_BYTES = 51200;

  private static final String TEMP_FILE_PREFIX = "postgres-pgjdbc-stream";

  public StreamWrapper(byte[] data, int offset, int length) {
    this.stream = null;
    this.rawData = data;
    this.offset = offset;
    this.length = length;
  }

  public StreamWrapper(InputStream stream, int length) {
    this.stream = stream;
    this.rawData = null;
    this.offset = 0;
    this.length = length;
  }

  public StreamWrapper(InputStream stream) throws PSQLException {
    try {
      ByteArrayOutputStream memoryOutputStream = new ByteArrayOutputStream();
      final int memoryLength = copyStream(stream, memoryOutputStream, MAX_MEMORY_BUFFER_BYTES);
      byte[] rawData = memoryOutputStream.toByteArray();

      if (memoryLength == -1) {
        final int diskLength;
        final Path tempFile = Files.createTempFile(TEMP_FILE_PREFIX, ".tmp");
        try (OutputStream diskOutputStream = Files.newOutputStream(tempFile);) {
          diskOutputStream.write(rawData);
          diskLength = copyStream(stream, diskOutputStream, Integer.MAX_VALUE - rawData.length);
          if (diskLength == -1) {
            throw new PSQLException(GT.tr("Object is too large to send over the protocol."),
                PSQLState.NUMERIC_CONSTANT_OUT_OF_RANGE);
          }
        } catch (RuntimeException | Error | PSQLException e) {
          try {
            tempFile.toFile().delete();
          } catch (Throwable ignore) {
          }
          throw e;
        }
        // The finalize action is not created if the above code throws
        this.offset = 0;
        this.length = rawData.length + diskLength;
        this.rawData = null;
        this.stream = null; // The stream is opened on demand
        TempFileHolder tempFileHolder = new TempFileHolder(tempFile);
        this.tempFileHolder = tempFileHolder;
        cleaner = LazyCleaner.getInstance().register(leakHandle, tempFileHolder);
      } else {
        this.rawData = rawData;
        this.stream = null;
        this.offset = 0;
        this.length = rawData.length;
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
          PSQLState.IO_ERROR, e);
    }
  }

  public InputStream getStream() throws IOException {
    if (stream != null) {
      return stream;
    }
    TempFileHolder finalizeAction = this.tempFileHolder;
    if (finalizeAction != null) {
      return finalizeAction.getStream();
    }

    return new java.io.ByteArrayInputStream(castNonNull(rawData), offset, length);
  }

  @Override
  public void close() throws IOException {
    if (cleaner != null) {
      cleaner.clean();
    }
  }

  public int getLength() {
    return length;
  }

  public int getOffset() {
    return offset;
  }

  public byte @Nullable [] getBytes() {
    return rawData;
  }

  public String toString() {
    return "<stream of " + length + " bytes>";
  }

  private static int copyStream(InputStream inputStream, OutputStream outputStream, int limit)
      throws IOException {
    int totalLength = 0;
    byte[] buffer = new byte[2048];
    int readLength = inputStream.read(buffer);
    while (readLength > 0) {
      totalLength += readLength;
      outputStream.write(buffer, 0, readLength);
      if (totalLength >= limit) {
        return -1;
      }
      readLength = inputStream.read(buffer);
    }
    return totalLength;
  }

  private final @Nullable InputStream stream;
  private @Nullable TempFileHolder tempFileHolder;
  private final Object leakHandle = new Object();
  private LazyCleaner.@Nullable Cleanable<IOException> cleaner;
  private final byte @Nullable [] rawData;
  private final int offset;
  private final int length;
}
