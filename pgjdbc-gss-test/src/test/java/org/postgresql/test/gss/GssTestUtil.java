/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.gss;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Small helpers shared by the GSS test: free-port lookup, file read/write and process spawning.
 */
final class GssTestUtil {
  private GssTestUtil() {
  }

  /**
   * Returns a free TCP port. There is an inherent race between closing the socket and the caller
   * binding the port, however it matches the behaviour the test relied on before.
   */
  static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /**
   * Appends {@code text} followed by a newline to {@code fileName}. When {@code truncate} is set the
   * file is overwritten instead, so a single call fully replaces the previous content (this is how
   * pg_hba.conf and pg_ident.conf are rewritten between scenarios).
   */
  static void writeText(String fileName, String text, boolean truncate) throws IOException {
    Path path = Paths.get(fileName);
    byte[] bytes = (text + "\n ").getBytes(StandardCharsets.UTF_8);
    if (truncate) {
      Files.write(path, bytes);
    } else {
      Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
  }

  static String readText(String fileName) throws IOException {
    return new String(Files.readAllBytes(Paths.get(fileName)), StandardCharsets.UTF_8);
  }

  /**
   * Runs a command, forwarding its output to this JVM, and waits for it to finish.
   */
  static int runAndWait(List<String> command, Map<String, String> env)
      throws IOException, InterruptedException {
    Process process = start(command, env);
    process.getOutputStream().close();
    return process.waitFor();
  }

  /**
   * Starts a (typically long-running) command, forwarding its output to this JVM, without waiting.
   */
  static Process start(List<String> command, Map<String, String> env) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command);
    if (env != null) {
      builder.environment().putAll(env);
    }
    builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    return builder.start();
  }

  static void deleteRecursively(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    // Best effort: leftover files in build/ are not fatal for the test
    file.delete();
  }

  /**
   * Writes {@code text} to the process standard input and closes it.
   */
  static void writeStdin(Process process, String text) throws IOException {
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(text.getBytes(StandardCharsets.UTF_8));
    }
  }
}
