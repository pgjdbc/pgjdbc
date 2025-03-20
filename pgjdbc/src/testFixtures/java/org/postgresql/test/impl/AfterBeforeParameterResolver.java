/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.engine.execution.BeforeEachMethodAdapter;
import org.junit.jupiter.engine.extension.ExtensionRegistry;

import java.lang.annotation.Annotation;
import java.util.Optional;

/**
 * Passes JUnit5's {@code ParameterizedTest} parameters to {@code @BeforeEach} and {@code AfterEach}
 * methods.
 *
 * @see <a href="https://github.com/junit-team/junit5/issues/3157">Parameterized BeforeEach or
 *     AfterEach only</a>
 */
public class AfterBeforeParameterResolver implements BeforeEachMethodAdapter, ParameterResolver {
  private @Nullable ParameterResolver parameterisedTestParameterResolver;

  @Override
  public void invokeBeforeEachMethod(ExtensionContext context, ExtensionRegistry registry) {
    Optional<ParameterResolver> resolverOptional = registry.getExtensions(ParameterResolver.class)
        .stream()
        .filter(parameterResolver -> parameterResolver.getClass().getName().contains(
            "ParameterizedTestParameterResolver"))
        .findFirst();
    parameterisedTestParameterResolver = resolverOptional.orElse(null);
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext,
      ExtensionContext extensionContext) throws ParameterResolutionException {
    // JUnit asks us to resolve a parameter for "BeforeEach" method,
    // and we delegate to the "parameterized test" implementation,
    // however it expects to resolve a parameter on a "test method".
    if (parameterisedTestParameterResolver != null
        && isExecutedOnAfterOrBeforeMethod(parameterContext)) {
      // pContext refers to a parameter on a test method
      ParameterContext pContext = getTestMethodParameterContext(parameterContext, extensionContext);
      return parameterisedTestParameterResolver.supportsParameter(pContext, extensionContext);
    }
    return false;
  }

  private static DefaultParameterContext getTestMethodParameterContext(ParameterContext parameterContext,
      ExtensionContext extensionContext) {
    return new DefaultParameterContext(
        parameterContext.getIndex(),
        extensionContext.getRequiredTestMethod().getParameters()[parameterContext.getIndex()],
        parameterContext.getTarget());
  }

  private static boolean isExecutedOnAfterOrBeforeMethod(ParameterContext parameterContext) {
    for (Annotation annotation : parameterContext.getDeclaringExecutable().getDeclaredAnnotations()) {
      if (isAfterEachOrBeforeEachAnnotation(annotation)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isAfterEachOrBeforeEachAnnotation(Annotation annotation) {
    return annotation.annotationType() == BeforeEach.class || annotation.annotationType() == AfterEach.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext,
      ExtensionContext extensionContext) throws ParameterResolutionException {
    return parameterisedTestParameterResolver.resolveParameter(
        getTestMethodParameterContext(parameterContext, extensionContext),
        extensionContext);
  }
}
