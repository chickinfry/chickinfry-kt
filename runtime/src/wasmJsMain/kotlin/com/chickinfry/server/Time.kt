package com.chickinfry.server

private fun leap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
private fun monthDays(year: Int, month: Int): Int = when (month) {
    2 -> if (leap(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}
internal fun validateDate(value: String) {
    require(value.length == 10 && value[4] == '-' && value[7] == '-') { "Invalid date" }
    val year = decimal(value, 0, 4)
    val month = decimal(value, 5, 7)
    val day = decimal(value, 8, 10)
    require(year in 1..9999 && month in 1..12 && day in 1..monthDays(year, month)) { "Invalid date" }
}

private fun decimal(value: String, start: Int, end: Int): Int {
    val digits = value.substring(start, end)
    require(digits.all { it in '0'..'9' })
    return digits.toInt()
}

internal fun normalizeTimestamp(value: String): String {
    require(value.length >= 20 && value[10] in "T " && value[13] == ':' && value[16] == ':') { "Invalid timestamp" }
    val date = value.substring(0, 10)
    val hour = decimal(value, 11, 13)
    val minute = decimal(value, 14, 16)
    val second = decimal(value, 17, 19)
    var zoneStart = 19
    var fraction = ""
    if (value[zoneStart] == '.') {
        val start = ++zoneStart
        while (zoneStart < value.length && value[zoneStart] in '0'..'9') zoneStart++
        require(zoneStart - start in 1..6) { "Invalid timestamp fraction" }
        fraction = value.substring(start, zoneStart)
    }
    val zone = value.substring(zoneStart)
    validateDate(date)
    require(hour < 24 && minute < 60 && second < 60)
    var year = date.substring(0, 4).toInt()
    var month = date.substring(5, 7).toInt()
    var day = date.substring(8, 10).toInt()
    val offset = if (zone == "Z") 0 else {
        require(zone.length in listOf(3, 5, 6) && zone[0] in "+-") { "Invalid timestamp offset" }
        val hours = decimal(zone, 1, 3)
        val minutes = when (zone.length) {
            3 -> 0
            5 -> decimal(zone, 3, 5)
            else -> { require(zone[3] == ':'); decimal(zone, 4, 6) }
        }
        require(hours <= 23 && minutes < 60)
        (hours * 60 + minutes) * if (zone[0] == '-') -1 else 1
    }
    var seconds = hour * 3600 + minute * 60 + second - offset * 60
    while (seconds < 0) {
        seconds += 86400
        day--
        if (day == 0) { month--; if (month == 0) { month = 12; year-- }; day = monthDays(year, month) }
    }
    while (seconds >= 86400) {
        seconds -= 86400
        day++
        if (day > monthDays(year, month)) { day = 1; month++; if (month == 13) { month = 1; year++ } }
    }
    require(year in 1..9999) { "Timestamp outside supported calendar" }
    while (fraction.endsWith("0")) fraction = fraction.substring(0, fraction.length - 1)
    while (fraction.length < 3) fraction += "0"
    val micros = fraction
    fun part(value: Int, width: Int = 2): String {
        var text = value.toString()
        while (text.length < width) text = "0" + text
        return text
    }
    return "${part(year, 4)}-${part(month)}-${part(day)}T${part(seconds / 3600)}:${part(seconds / 60 % 60)}:${part(seconds % 60)}.${micros}Z"
}
