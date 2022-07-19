/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is used to mark a test method as a test that should be run with stubbing system
 * calls like {@code System#getProperty} and {@code System#getenv}.
 * <p>The tests should be run in isolation to prevent concurrent modification of properties and
 * the environment.</p>
 * <p>Note: environment mocking works from a single thread only until
 * <a href="https://github.com/webcompere/system-stubs/pull/46">Fix multi-threaded
 * environment variable mocking</a>, and <a href="https://github.com/mockito/mockito/issues/2142">Mocked
 * static methods are not available in other threads</a> are resolved</p>
 */
@Isolated
@ExtendWith(SystemStubsExtension.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface StubEnvironmentAndProperties {
}
