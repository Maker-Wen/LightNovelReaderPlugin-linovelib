package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Uses the host's actual callback descriptor, including its R8-renamed controller type. */
internal object LinovelibHostDataSource {
    val tagClickMethod = WebBookDataSource::class.java.methods.single {
        it.name == "progressBookTagClick" && it.parameterCount == 2 &&
            it.parameterTypes[0] == String::class.java
    }

    fun wrap(
        source: WebBookDataSource,
        onTagClick: (String, Any?) -> Unit,
    ): WebBookDataSource = Proxy.newProxyInstance(
        WebBookDataSource::class.java.classLoader,
        arrayOf(WebBookDataSource::class.java),
    ) { proxy, method, arguments ->
        when {
            method == tagClickMethod -> {
                onTagClick(arguments!![0] as String, arguments[1])
                null
            }
            method.name == "equals" && method.declaringClass == Any::class.java -> proxy === arguments!![0]
            method.name == "hashCode" && method.declaringClass == Any::class.java -> System.identityHashCode(proxy)
            else -> try {
                method.invoke(source, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    } as WebBookDataSource
}
