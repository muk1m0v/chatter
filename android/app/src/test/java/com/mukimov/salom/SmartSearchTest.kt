package com.mukimov.salom

import org.junit.Assert.*
import org.junit.Test

class SmartSearchTest {
    @Test fun usernameNarrowsWithEveryLetterWithoutAt() {
        val entries = listOf("Khayrulloev", "Kamil", "Karim")
        fun find(q: String) = entries.filter { smartSearchScore(q, "", it) != null }
        assertEquals(3, find("K").size)
        assertEquals(listOf("Khayrulloev"), find("Kh"))
        assertEquals(listOf("Khayrulloev"), find("khayr"))
        assertTrue(find("Khayz").isEmpty())
        assertEquals(find("Kh"), find("@Kh"))
    }
    @Test fun doesNotMatchUsernameInMiddle() { assertNull(smartSearchScore("rullo", "", "Khayrulloev")) }
    @Test fun contactSearchHandlesTransliterationAndPhoneFormatting() {
        assertNotNull(smartSearchScore("Khay", "Хайруллоев"))
        assertNotNull(smartSearchScore("800", "Контакт", phone = "+992 (800) 123-456"))
        assertNull(smartSearchScore("abc800", "Контакт", phone = "+992800123456"))
        assertEquals(canonicalPhone("+992800123456"), canonicalPhone("800 123 456"))
    }
    @Test fun onlyKnownActiveAccountOpensChat() {
        val account = SalomAccount("fixture", "Example", "Khayrulloev", "", is_active = true)
        assertEquals(ContactDestination.CHAT, contactDestination(account))
        assertEquals(ContactDestination.INVITE, contactDestination(account.copy(is_active = false)))
        assertEquals(ContactDestination.UNKNOWN, contactDestination(null))
    }
}
