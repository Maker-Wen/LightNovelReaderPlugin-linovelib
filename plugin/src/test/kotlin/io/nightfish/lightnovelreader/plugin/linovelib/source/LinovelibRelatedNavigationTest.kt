package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LinovelibRelatedNavigationTest {
    @Test
    fun createsExpandedRouteFromCompileTimeApi3() {
        val route = LinovelibRelatedNavigation.createExpandedRoute(javaClass.classLoader, "related-id")

        assertEquals("related-id", route.javaClass.getMethod("getExpandedPageDataSourceId").invoke(route))
    }

    @Test
    fun `missing api 3 route does not fall back to legacy host navigation`() {
        val requestedClasses = mutableListOf<String>()
        val missingHost = object : ClassLoader(null) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                requestedClasses += name
                throw ClassNotFoundException(name)
            }
        }

        assertThrows(ClassNotFoundException::class.java) {
            LinovelibRelatedNavigation.createExpandedRoute(missingHost, "related-id")
        }
        assertEquals(listOf("io.nightfish.lightnovelreader.api.Route\$Main\$Explore\$Expanded"), requestedClasses)
    }
}
