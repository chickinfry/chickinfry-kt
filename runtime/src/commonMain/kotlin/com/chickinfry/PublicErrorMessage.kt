package com.chickinfry

internal fun publicErrorMessage(value: String): String? {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val code = value[index++].code
        bytes += when (code) {
            in 0..31, in 0x7f..0x9f, in 0x2028..0x2029 -> return null
            in 0..0x7f -> 1
            in 0x80..0x7ff -> 2
            in 0xd800..0xdbff -> {
                if (index == value.length || value[index++].code !in 0xdc00..0xdfff) return null
                4
            }
            in 0xdc00..0xdfff -> return null
            else -> 3
        }
        if (bytes > 1024) return null
    }
    return value
}
