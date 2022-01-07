package org.postgresql.test.io

import net.jqwik.api.lifecycle.LifecycleContext
import net.jqwik.api.lifecycle.Lifespan
import net.jqwik.api.lifecycle.ParameterResolutionContext
import net.jqwik.api.lifecycle.ResolveParameterHook
import net.jqwik.api.lifecycle.Store
import net.jqwik.api.lifecycle.TryLifecycleContext
import org.postgresql.test.TestUtil
import java.sql.Connection
import java.util.Optional

class DbConnectionHook : ResolveParameterHook {
    override fun resolve(
        parameterContext: ParameterResolutionContext,
        lifecycleContext: LifecycleContext
    ): Optional<ResolveParameterHook.ParameterSupplier> =
        if (parameterContext.typeUsage().isOfType(Connection::class.java)) {
            Optional.of(ResolveParameterHook.ParameterSupplier { connect() })
        } else {
            Optional.empty()
        }

    private fun connect(): Connection =
        Store.getOrCreate(DbConnectionHook::class, Lifespan.RUN) {
            TestUtil.openDB()
        }.run {
            onClose {
                it.close()
            }
            get()
        }
}
