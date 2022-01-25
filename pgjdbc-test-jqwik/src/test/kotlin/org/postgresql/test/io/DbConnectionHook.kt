/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.io

import net.jqwik.api.lifecycle.LifecycleContext
import net.jqwik.api.lifecycle.Lifespan
import net.jqwik.api.lifecycle.ParameterResolutionContext
import net.jqwik.api.lifecycle.ResolveParameterHook
import net.jqwik.api.lifecycle.Store
import net.jqwik.api.lifecycle.TryLifecycleContext
import org.postgresql.test.TestUtil
import org.postgresql.test.jqwik.createStore
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
        createStore<Connection>(DbConnectionHook::class, Lifespan.RUN) {
            onClose { it.close() }
            initialValue { TestUtil.openDB() }
        }.get()
}
