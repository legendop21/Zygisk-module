package com.hivirtus.zygiskmode

/**
 * Zygisk Next → Enforce list ke liye exact Play Store package IDs.
 * System packages hamesha enforce karo; UPI wale jo app use karte ho.
 */
object ZygiskEnforceRegistry {

  /** Radio / SMS stack — bina inke real SIM se SMS chala jayega */
  val SYSTEM_REQUIRED: List<String> = listOf(
      "com.android.phone",
      "com.android.providers.telephony",
      "com.google.android.apps.messaging",
      "com.android.mms",
      "com.android.mms.service",
      "com.samsung.android.messaging",
      "com.google.android.gms",
      "com.google.android.gms.persistent",
  )

  /** Popular UPI / loan — exact package names (Play Store) */
  val POPULAR_UPI: List<String> = listOf(
      "net.one97.paytm",
      "com.phonepe.app",
      "com.google.android.apps.nbu.paisa.user",
      "com.fampay.in",
      "com.herofincorp.diyjourneys",
      "com.herofincorp.simplycash",
      "com.customer.herofincorp",
      "com.yespay.next",
      "com.yesbank.yespay",
      "com.yesbank.yespaynext",
      "com.kreditbee.android",
      "com.groww.app",
      "com.nextbillion.groww",
      "com.mobikwik_new",
      "com.dreamplug.androidapp",
      "com.bharatpe.app",
      "com.slice.app",
      "com.amazon.mShop.android.shopping",
      "com.csam.icici.bank.imobile",
      "com.hdfcbank.payzapp",
      "com.axis.mobile",
      "com.jio.myjio",
      "com.snapmint.customerapp",
      "com.stashfin.android",
      "com.mpokket.app",
  )

  fun enforceListForHookedApps(hookedPackages: Collection<String>): List<String> {
    val merged = LinkedHashSet<String>()
    merged.addAll(SYSTEM_REQUIRED)
    hookedPackages.filter { it.isNotBlank() }.forEach { merged.add(it) }
    return merged.toList()
  }

  fun enforceListForPackage(pkg: String): List<String> =
      enforceListForHookedApps(listOf(pkg))

  /** Clipboard / log ke liye — ek line per package */
  fun formatForCopy(packages: List<String>): String =
      packages.distinct().joinToString("\n")

  fun missingFromPopular(pkg: String): String? {
    val known = POPULAR_UPI.any { it == pkg } || UpiAppRegistry.findByPackage(pkg) != null
    return if (known) null else "Unknown package: $pkg"
  }
}
