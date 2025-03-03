/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ds;

import org.postgresql.PGProperty;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * DataSource for Custom HostChooser implementation.
 */
public class PGClusterAwareDataSource extends PGSimpleDataSource {

  /**
   * Sets the class name of the custom host chooser. an implementation of the HostChooser interface.
   * If this is not specified the driver would use the defaut HostChooser,
   * `MultiHostChooser` or `SingleHostChooser`.
   *
   * @param classname of the custom host chooser, an implementation of the HostChooser interface.
   */
  public void setHostChooserImpl(String classname) {
    PGProperty.HOST_CHOOSER_IMPL.set(properties, classname);
  }

  /**
   * @return HostChooserImp
   * @see PGProperty#HOST_CHOOSER_IMPL
   */
  public @Nullable String getHostChooserImpl() {
    return PGProperty.HOST_CHOOSER_IMPL.getOrDefault(properties);
  }

  /**
   * Sets the specific properties required by the custom host chooser.
   *
   * @param props specific properties required by the custom host chooser as a string.
   * @see PGProperty#HOST_CHOOSER_IMPL_PROPERTIES
   */
  public void setHostChooserImplProperties(String props) {
    PGProperty.HOST_CHOOSER_IMPL_PROPERTIES.set(properties, props);
  }

  /**
   * @return HostChooserImp
   * @see PGProperty#HOST_CHOOSER_IMPL_PROPERTIES
   */
  public @Nullable String getHostChooserImplProperties() {
    return PGProperty.HOST_CHOOSER_IMPL_PROPERTIES.getOrDefault(properties);
  }
}
