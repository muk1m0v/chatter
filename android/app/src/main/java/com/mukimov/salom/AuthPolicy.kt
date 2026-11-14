package com.mukimov.salom

internal object AuthPolicy {
    fun validUsername(value: String) = value.matches(Regex("[A-Za-z][A-Za-z0-9_]{3,23}"))
}
