/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.geometric.PGpoint;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

class ClassUtilsTest {

  @Test
  void loadsClassWithDriverClassLoader() throws Exception {
    Class<? extends PGobject> cls = ClassUtils.forName(PGobject.class.getName(), PGobject.class,
        ClassLoaderStrategy.DRIVER, getClass().getClassLoader());
    assertSame(PGobject.class, cls);
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedOverloadTreatsNullClassLoaderAsBootstrap() throws Exception {
    // The released forName(String, Class, ClassLoader) overload must keep null == bootstrap loader.
    Class<? extends CharSequence> cls =
        ClassUtils.forName("java.lang.String", CharSequence.class, null);
    assertSame(String.class, cls);
  }

  @Test
  void rejectsClassThatIsNotASubtype() {
    assertThrows(ClassCastException.class, () -> ClassUtils.forName("java.lang.String",
        PGobject.class, ClassLoaderStrategy.DRIVER, getClass().getClassLoader()));
  }

  @Test
  void fallsBackToContextClassLoaderWhenDriverCannotSee() throws Exception {
    String className = PGpoint.class.getName();
    // A classloader that defines PGpoint itself, so a class it resolves reports it as the loader.
    ClassLoader contextLoader = new SingleClassDefiningClassLoader(getClass().getClassLoader(),
        className, bytecodeOf(PGpoint.class));
    // A classloader whose parent is the bootstrap loader cannot see driver classes.
    ClassLoader blind = new ClassLoader(null) {
    };
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(contextLoader);

      // driver-first: the blind classloader misses, so the class comes from the context classloader.
      Class<? extends PGobject> cls = ClassUtils.forName(className, PGobject.class,
          ClassLoaderStrategy.DRIVER_FIRST, blind);
      assertSame(contextLoader, cls.getClassLoader(),
          "class must be loaded from the thread context classloader");
      assertNotSame(PGpoint.class, cls,
          "the context classloader defines its own copy, distinct from the application's");

      // driver: there is no fallback, so the blind classloader fails outright.
      assertThrows(ClassNotFoundException.class, () -> ClassUtils.forName(className,
          PGobject.class, ClassLoaderStrategy.DRIVER, blind));
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  private static byte[] bytecodeOf(Class<?> clazz) throws IOException {
    try (InputStream in = clazz.getResourceAsStream(clazz.getSimpleName() + ".class")) {
      if (in == null) {
        throw new IOException("Cannot find bytecode for " + clazz.getName());
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  /**
   * Defines one named class from the given bytecode (child-first); every other class delegates to
   * the parent. A class it defines reports this loader as its {@link Class#getClassLoader()}.
   */
  private static final class SingleClassDefiningClassLoader extends ClassLoader {
    private final String className;
    private final byte[] bytecode;

    SingleClassDefiningClassLoader(ClassLoader parent, String className, byte[] bytecode) {
      super(parent);
      this.className = className;
      this.bytecode = bytecode;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (className.equals(name)) {
        synchronized (getClassLoadingLock(name)) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded == null) {
            loaded = defineClass(name, bytecode, 0, bytecode.length);
          }
          if (resolve) {
            resolveClass(loaded);
          }
          return loaded;
        }
      }
      return super.loadClass(name, resolve);
    }
  }
}
