package org.postgresql.ds.jdbc23;

import javax.naming.Name;
import javax.naming.Context;
import java.util.Hashtable;
import javax.naming.spi.ObjectFactory;

import org.postgresql.ds.common.AbstractObjectFactory;

public class Jdbc23ObjectFactory extends AbstractObjectFactory implements ObjectFactory {

	public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable environment) throws Exception {
		return getObjectInstanceImpl(obj, name, nameCtx, environment);
	}

}
