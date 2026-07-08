package com.hivirtus.zygiskmode

import android.content.Context

object LicenseManager {

    /** Educational build — open access, no VIP gate. */
    fun isLicensed(context: Context): Boolean = true

    fun activate(context: Context, key: String): Boolean = true

    fun getSavedKey(context: Context): String? = "EDU-OPEN-ACCESS"

    fun validateKey(key: String): Boolean = true
}
