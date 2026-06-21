package com.qrscanner.app.util

private val RD_NUMBER_REGEX = Regex("^\\d{9,15}$")

fun isValidRdNumber(number: String): Boolean =
    RD_NUMBER_REGEX.matches(number.trim())
