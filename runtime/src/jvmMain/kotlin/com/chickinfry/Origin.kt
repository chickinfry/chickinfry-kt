package com.chickinfry

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun validateOrigin(origin: String): HttpUrl {
    fun invalid(): Nothing = throw ClientException("invalid_origin")
    if (origin.any { it <= ' ' || it == '\u007f' || it == '\\' || it.isWhitespace() || Character.isSpaceChar(it) }) invalid()
    val uri = try {
        URI(origin)
    } catch (_: Exception) {
        invalid()
    }
    val scheme = uri.scheme ?: invalid()
    if (!scheme.equals("https", true) && !scheme.equals("http", true)) invalid()
    val authority = uri.rawAuthority ?: invalid()
    if (authority.isEmpty() || '@' in authority || '%' in authority) invalid()
    if (uri.rawPath != "" && uri.rawPath != "/") invalid()
    if (uri.rawQuery != null || uri.rawFragment != null) invalid()

    val host: String
    val port: String?
    if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        if (end < 0) invalid()
        host = authority.substring(0, end + 1)
        val suffix = authority.substring(end + 1)
        if (suffix.isNotEmpty() && !suffix.startsWith(":")) invalid()
        port = if (suffix.isEmpty()) null else suffix.substring(1)
    } else {
        if (authority.count { it == ':' } > 1) invalid()
        host = authority.substringBefore(':')
        port = if (':' in authority) authority.substringAfter(':') else null
    }
    if (port != null && (port.isEmpty() || port.any { it !in '0'..'9' } || port.toIntOrNull() !in 1..65535)) invalid()
    if (scheme.equals("http", true) && host != "[::1]") {
        val octets = host.split('.')
        if (octets.size != 4 || octets[0] != "127" || octets.any {
                it.isEmpty() || it.any { digit -> digit !in '0'..'9' } ||
                    (it.length > 1 && it[0] == '0') || it.toIntOrNull() !in 0..255
            }) invalid()
    }
    return origin.toHttpUrlOrNull() ?: invalid()
}
