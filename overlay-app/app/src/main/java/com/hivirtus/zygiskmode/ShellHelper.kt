package com.hivirtus.zygiskmode

object ShellHelper {

    fun runSu(command: String): Boolean {
        val shells = listOf(
            arrayOf("su", "-c", command),
            arrayOf("ksud", "shell", command),
            arrayOf("/data/adb/ksu/bin/ksud", "shell", command)
        )
        for (cmd in shells) {
            try {
                val process = Runtime.getRuntime().exec(cmd)
                if (process.waitFor() == 0) return true
            } catch (_: Exception) {}
        }
        return false
    }
}
