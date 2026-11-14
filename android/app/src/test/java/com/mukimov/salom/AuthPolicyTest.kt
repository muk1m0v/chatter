package com.mukimov.salom
import org.junit.Assert.*
import org.junit.Test
class AuthPolicyTest {
    @Test fun usernameStartsWithLetterAndHasBoundedLength() {
        assertTrue(AuthPolicy.validUsername("muk1mov"))
        assertTrue(AuthPolicy.validUsername("User_name123"))
        assertFalse(AuthPolicy.validUsername("123user"))
        assertFalse(AuthPolicy.validUsername("abc"))
        assertFalse(AuthPolicy.validUsername("a".repeat(25)))
        assertFalse(AuthPolicy.validUsername("user name"))
    }
}
