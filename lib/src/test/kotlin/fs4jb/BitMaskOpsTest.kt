package fs4jb

import fs4jb.BitMaskOps.Companion.check
import fs4jb.BitMaskOps.Companion.set
import fs4jb.BitMaskOps.Companion.set0
import fs4jb.BitMaskOps.Companion.set1
import fs4jb.BitMaskOps.Companion.toggle
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitMaskOpsTest {
    @Test
    fun testOps() {
        for (n in 0..31) {
            var i = 0
            assertFalse { i.check(n) }
            i = i.set1(n)
            assertTrue { i.check(n) }
            i = i.set0(n)
            assertFalse { i.check(n) }
            i = i.toggle(n)
            assertTrue { i.check(n) }
            i = i.toggle(n)
            assertFalse { i.check(n) }
            i = i.set(n, true)
            assertTrue { i.check(n) }
            i = i.set(n, false)
            assertFalse { i.check(n) }
        }
    }
}