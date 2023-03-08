/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The action deletes temporary file in case the user submits a large input stream,
 * and then abandons the statement.
 */
class TempFileHolder implements LazyCleaner.CleaningAction<IOException> {

  private static final Logger LOGGER = Logger.getLogger(StreamWrapper.class.getName());
  private @Nullable InputStream stream;
  private @Nullable Path tempFile;

  TempFileHolder(Path tempFile) {
    this.tempFile = tempFile;
  }

  public InputStream getStream() throws IOException {
    InputStream stream = this.stream;
    if (stream == null) {
      stream = Files.newInputStream(castNonNull(tempFile));
      this.stream = stream;
    }
    return stream;
  }

  @Override
  public void onClean(boolean leak) throws IOException {
    if (leak) {
      LOGGER.log(Level.WARNING, GT.tr("StreamWrapper leak detected StreamWrapper.close() was not called. "));
    }
    Path tempFile = this.tempFile;
    if (tempFile != null) {
      tempFile.toFile().delete();
      this.tempFile = null;
    }
    InputStream stream = this.stream;
    if (stream != null) {
      stream.close();
      this.stream = null;
    }
  }

}
