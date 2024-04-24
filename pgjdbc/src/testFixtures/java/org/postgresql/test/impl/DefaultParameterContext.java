/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.impl;

import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;

/**
 * Implements {@link ParameterContext} as JUnit does not provide default implementation.
 */
public class DefaultParameterContext implements ParameterContext {
  private final int index;
  private final Parameter parameter;
  private final Optional<Object> target;

  public DefaultParameterContext(int index, Parameter parameter,
      Optional<Object> target) {
    this.index = index;
    this.parameter = parameter;
    this.target = target;
  }

  @Override
  public int getIndex() {
    return index;
  }

  @Override
  public Parameter getParameter() {
    return parameter;
  }

  @Override
  public Optional<Object> getTarget() {
    return target;
  }

  @Override
  public boolean isAnnotated(Class<? extends Annotation> annotationType) {
    return AnnotationUtils.isAnnotated(parameter, index, annotationType);
  }

  @Override
  public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
    return AnnotationUtils.findAnnotation(parameter, index, annotationType);
  }

  @Override
  public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> annotationType) {
    return AnnotationUtils.findRepeatableAnnotations(parameter, index, annotationType);
  }

  @Override
  public String toString() {
    return "DefaultParameterContext[parameter=" + parameter + ", index=" + index + ", target=" + target + "]";
  }
}
