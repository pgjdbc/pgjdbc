/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class FileUtils {

  private FileUtils() {
    // prevent instantiation of static helper class
  }

  public static BufferedInputStream newBufferedInputStream(String path) throws FileNotFoundException {
    return new BufferedInputStream(new FileInputStream(path));
  }

  public static BufferedInputStream newBufferedInputStream(File file) throws FileNotFoundException {
    return new BufferedInputStream(new FileInputStream(file));
  }
}
