package org.postgresql.system;

import org.postgresql.types.Registry;

public class BasicContext {

  /*
  private class RegistryTypeLoader implements Registry.TypeLoader {

    @Override
    public Type load(int oid) throws IOException {
      return BasicContext.this.loadType(oid);
    }

    @Override
    public CompositeType loadRelation(int relationOid) throws IOException {
      return BasicContext.this.loadRelationType(relationOid);
    }

    @Override
    public Type load(QualifiedName name) throws IOException {
      return BasicContext.this.loadType(name.toString());
    }

    @Override
    public Type load(String name) throws IOException {
      return BasicContext.this.loadType(name);
    }

  }
  */

  protected Registry registry;

  public Registry getRegistry() {
    return registry;
  }

}
