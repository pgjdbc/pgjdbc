package org.postgresql.ds;

import org.postgresql.PGProperty;

public class PGClusterAwareDataSource extends PGSimpleDataSource {

  public void setHostChooserImpl(String classname) {
    PGProperty.HOST_CHOOSER_IMPL.set(properties, classname);
  }

  /**
   * @return HostChooserImp
   * @see PGProperty#HOST_CHOOSER_IMPL
   */
  public String getHostChooserImpl() {
    return PGProperty.HOST_CHOOSER_IMPL.getOrDefault(properties);
  }

  public void setHostChooserImplProperties(String classname) {
    PGProperty.HOST_CHOOSER_IMPL_PROPERTIES.set(properties, classname);
  }

  /**
   * @return HostChooserImp
   * @see PGProperty#HOST_CHOOSER_IMPL_PROPERTIES
   */
  public String getHostChooserImplProperties() {
    return PGProperty.HOST_CHOOSER_IMPL_PROPERTIES.getOrDefault(properties);
  }
}
