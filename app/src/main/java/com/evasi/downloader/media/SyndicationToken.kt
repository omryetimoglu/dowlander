package com.evasi.downloader.media

// X's embed script uses ((Number(id) / 1e15) * Math.PI).toString(36), then
// drops zeros and the radix point. Convert the fraction at JS Double precision.
internal fun syndicationToken(id: String): String {
    val value = id.toDouble() / 1e15 * Math.PI
    var digits = value.toLong()
    var remainder = value - digits
    var tolerance = Math.ulp(value) / 2
    while (remainder >= tolerance && remainder > 0) {
        val scaled = remainder * 36
        val digit = scaled.toInt()
        digits = digits * 36 + digit
        remainder = scaled - digit
        tolerance *= 36
        if (remainder + tolerance > 1 &&
            (remainder > 0.5 || (remainder == 0.5 && digit % 2 == 1))
        ) {
            digits++
            break
        }
    }
    return digits.toString(36).replace("0", "")
}
