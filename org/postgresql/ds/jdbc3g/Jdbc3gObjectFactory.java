package org.postgresql.ds.jdbc3g;

import javax.naming.Name;
import javax.naming.Context;
import java.util.Hashtable;
import javax.naming.spi.ObjectFactory;

import org.postgresql.ds.common.AbstractObjectFactory;

public class Jdbc3gObjectFactory extends AbstractObjectFactory implements ObjectFactory {

	public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<String, ?> environment) throws Exception {
		return getObjectInstanceImpl(obj, name, nameCtx, environment);
	}

}
