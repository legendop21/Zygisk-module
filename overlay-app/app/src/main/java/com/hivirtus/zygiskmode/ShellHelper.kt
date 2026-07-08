package com.hivirtus.zygiskmode

object ShellHelper {

    fun runSu(command: String): Boolean {
        return runSuOutput(command) != null
    }

    fun runSuOutput(command: String): String? {
        val shells = listOf(
            arrayOf("su", "-c", command),
            arrayOf("ksud", "shell", command),
            arrayOf("/data/adb/ksu/bin/ksud", "shell", command)
        )
        for (cmd in shells) {
            try {
                val process = Runtime.getRuntime().exec(cmd)
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) return output
            } catch (_: Exception) {}
        }
        return null
    }
}
