package com.inkreader.data.repo

import org.junit.Test
import org.junit.Assert.*
import java.nio.file.Files

class PageCountCacheTest {
    @Test fun exactLayoutAndSourceIdentityRequiredWithCorruptionFallbackAndBoundedStorage() {
        val root = Files.createTempDirectory("pages-test").toFile()
        try {
            val cache = PageCountCache(root); val prefix = intArrayOf(0, 1, 4, 20)
            cache.save("layout1/source1", prefix)
            assertArrayEquals(prefix, PageCountCache(root).load("layout1/source1", 3))
            assertNull(cache.load("layout2/source1", 3)); assertNull(cache.load("layout1/source2", 3))
            assertNull(cache.load("layout1/source1", 2))
            root.listFiles()!!.single().writeBytes(ByteArray(24))
            assertNull(cache.load("layout1/source1", 3))
            for (i in 0..50) cache.save("key$i", prefix)
            assertTrue(root.listFiles()!!.size <= 32)
            cache.save("bad", intArrayOf(0, 0, 1)); assertNull(cache.load("bad", 2))
        } finally { root.deleteRecursively() }
    }
}
