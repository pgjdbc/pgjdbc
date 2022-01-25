/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jqwik

import net.jqwik.api.lifecycle.CannotFindStoreException
import net.jqwik.api.lifecycle.Lifespan
import net.jqwik.api.lifecycle.Store
import java.lang.IllegalArgumentException
import java.util.function.Consumer
import java.util.function.Supplier

class StoreBuilder<T> {
    internal val cleanups = mutableListOf<Consumer<T>>()
    internal lateinit var initialValue: Supplier<T>

    fun onClose(cleanup: Consumer<T>) {
        cleanups += cleanup
    }

    fun initialValue(initialValue: Supplier<T>) {
        this.initialValue = initialValue
    }
}

// See https://github.com/jlink/jqwik/issues/290
fun <T> createStore(
    identifier: Any,
    lifespan: Lifespan,
    configure: StoreBuilder<T>.() -> Unit
): Store<T> =
    try {
        Store.get<T>(identifier).apply {
            if (lifespan != lifespan()) {
                throw IllegalArgumentException("Can't create store $identifier with lifespan $lifespan, as existing one has lifespan ${lifespan()} ")
            }
        }
    } catch (e: CannotFindStoreException) {
        val params = StoreBuilder<T>().apply(configure)
        Store.create(identifier, lifespan, params.initialValue).apply {
            for (cleanup in params.cleanups) {
                onClose(cleanup)
            }
        }
    }
