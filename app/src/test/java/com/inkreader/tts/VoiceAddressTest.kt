package com.inkreader.tts

import org.junit.Assert.*
import org.junit.Test

class VoiceAddressTest {
    @Test fun acceptsOnlyNumericLocalAddresses() {
        listOf("192.168.1.2", "10.2.3.4", "172.16.1.2", "172.31.3.4", "127.0.0.1").forEach { assertTrue(it, VoiceAddress.valid(it)) }
        listOf("8.8.8.8", "172.32.1.1", "192.169.1.1", "localhost", "192.168.1.2.evil.com", "192.168.01.2",
            "0x7f.0.0.1", "192.168.1.999", "192.168.1.255", "192.168.1.2:80", "192.168.1.2/", "127.1", "::1", "").forEach {
            assertFalse(it, VoiceAddress.valid(it))
        }
    }
}
