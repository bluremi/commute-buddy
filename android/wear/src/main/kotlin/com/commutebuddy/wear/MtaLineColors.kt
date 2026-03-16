package com.commutebuddy.wear

object MtaLineColors {

    fun lineColor(line: String): Int = when (line) {
        "1", "2", "3"      -> 0xFFD82233.toInt()
        "4", "5", "6"      -> 0xFF009952.toInt()
        "7"                -> 0xFF9A38A1.toInt()
        "A", "C", "E"      -> 0xFF0062CF.toInt()
        "B", "D", "F", "M" -> 0xFFEB6800.toInt()
        "G"                -> 0xFF799534.toInt()
        "J", "Z"           -> 0xFF8E5C33.toInt()
        "L", "S"           -> 0xFF7C858C.toInt()
        "N", "Q", "R", "W" -> 0xFFF6BC26.toInt()
        else               -> 0xFF888888.toInt()
    }

    // Yellow lines (#F6BC26) need black text for contrast; all others use white
    fun isLightBackground(line: String): Boolean = line in setOf("N", "Q", "R", "W")
}
