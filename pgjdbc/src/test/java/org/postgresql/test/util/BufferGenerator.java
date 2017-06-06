/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Random;

/**
 * Created by amozhenin on 30.09.2015.
 */
public class BufferGenerator {
  public static final int ROW_COUNT = 100000;

  public static void main(String[] args) throws Exception {
    Random random = new Random();
    random.setSeed(new Date().getTime());
    OutputStream out = null;
    try {
      File outFile = new File("target", "buffer.txt");
      outFile.getParentFile().mkdir();
      out = new BufferedOutputStream(new FileOutputStream(outFile));
      for (long i = 0; i < ROW_COUNT; i++) {
        StringBuffer line = new StringBuffer();
        line.append("VERY_LONG_LINE_TO_ASSIST_IN_DETECTION_OF_ISSUE_366_#_").append(i).append('\t');
        int letter = random.nextInt(26); // don't really care about uniformity for a test
        char character = (char) ((int) 'A' + letter); // black magic
        line.append("VERY_LONG_STRING_TO_REPRODUCE_ISSUE_366_").append(character).append(character);
        line.append(character).append('\t').append(random.nextDouble()).append('\n');
        out.write(line.toString().getBytes("UTF-8"));
      }
    } finally {
      if (out != null) {
        out.close();
      }
    }
  }

}
