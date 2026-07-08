package com.hivirtus.zygiskmode

object UpiAppRegistry {

    data class UpiApp(
        val packageName: String,
        val displayName: String,
        val smsKeywords: List<String> = emptyList(),
        /** OTP / token verify timer bonus (seconds) for this app */
        val timerBonusSeconds: Int = 20,
        /** Needs extra 2FA step timer */
        val needs2faBonus: Boolean = false
    )

    val ALL: List<UpiApp> = listOf(
        UpiApp("com.phonepe.app", "PhonePe", listOf("PHONEPE", "PPAY"), 25),
        UpiApp("net.one97.paytm", "Paytm", listOf("PAYTM", "PYTM"), 30, needs2faBonus = true),
        UpiApp("com.google.android.apps.nbu.paisa.user", "Google Pay", listOf("GPAY", "GOOGLEPAY"), 25),
        UpiApp("com.yespay.next", "YesPay Next", listOf("YESPRO", "YESPROUPI", "YESPAY", "YESBANK", "YESBNK"), 35, needs2faBonus = true),
        UpiApp("com.yesbank.yespay", "YesPay", listOf("YESPRO", "YESPROUPI", "YESPAY", "YESBNK"), 35, needs2faBonus = true),
        UpiApp("com.snapmint.customerapp", "Snapmint", listOf("SNAPMINT", "SNAP", "SMINT", "AD-SNAPMINT", "SNAPMT"), 30),
        UpiApp("com.tataneu", "Tata Neu", listOf("TATANEU", "TATA"), 25),
        UpiApp("com.stashfin.android", "Stashfin", listOf("STASHFIN", "STASH"), 40, needs2faBonus = true),
        UpiApp("com.kreditbee.android", "KreditBee", listOf("KREDITBEE", "KREDIT"), 40, needs2faBonus = true),
        UpiApp("com.dreamplug.androidapp", "CRED", listOf("CRED"), 25, needs2faBonus = true),
        UpiApp("com.mobikwik_new", "Mobikwik", listOf("MOBIKWIK"), 25),
        UpiApp("com.freecharge.android", "Freecharge", listOf("FREECHARGE", "FCASH"), 25),
        UpiApp("in.org.npci.upiapp", "BHIM UPI", listOf("BHIM", "NPCI"), 20),
        UpiApp("com.amazon.mShop.android.shopping", "Amazon Pay", listOf("AMAZON", "AMZPAY"), 25),
        UpiApp("com.popclub.android", "POP UPI", listOf("POPUPI", "POP"), 25),
        UpiApp("com.myairtelapp", "Airtel Thanks", listOf("AIRTEL"), 25),
        UpiApp("com.jio.myjio", "MyJio", listOf("JIO"), 25),
        UpiApp("com.csam.icici.bank.imobile", "iMobile ICICI", listOf("ICICI", "IMOBILE"), 30, needs2faBonus = true),
        UpiApp("com.sbi.lotusintouch", "YONO SBI", listOf("SBI", "YONO"), 30, needs2faBonus = true),
        UpiApp("com.axis.mobile", "Axis Mobile", listOf("AXIS"), 30, needs2faBonus = true),
        UpiApp("com.hdfcbank.payzapp", "PayZapp HDFC", listOf("HDFC", "PAYZAPP"), 30, needs2faBonus = true),
        UpiApp("com.bankofbaroda.mpassbook", "BOB World", listOf("BOB", "BARODA"), 30),
        UpiApp("com.idfcfirstbank.mobile", "IDFC First", listOf("IDFC"), 30),
        UpiApp("com.kotak811mobilebankingapp", "Kotak 811", listOf("KOTAK"), 30),
        UpiApp("com.whizdm.moneyview.loans", "MoneyView", listOf("MONEYVIEW"), 35),
        UpiApp("com.naviapp", "Navi", listOf("NAVI"), 30),
        UpiApp("com.bharatpe.app", "BharatPe", listOf("BHARATPE"), 25),
        UpiApp("com.rapipay", "Rapipay", listOf("RAPIPAY"), 25),
        UpiApp("com.fampay.in", "FamPay", listOf("FAMPAY"), 25),
        UpiApp("com.slice.app", "Slice", listOf("SLICE"), 25),
        UpiApp("com.postpe.app", "PostPe", listOf("POSTPE"), 25),
        UpiApp("com.olacabs.customer", "Ola", listOf("OLA"), 20),
        UpiApp("com.application.zomato", "Zomato", listOf("ZOMATO"), 20),
        UpiApp("in.swiggy.android", "Swiggy", listOf("SWIGGY"), 20),
        UpiApp("com.meesho.supply", "Meesho", listOf("MEESHO"), 20),
        UpiApp("com.flipkart.android", "Flipkart", listOf("FLIPKART"), 25),
        UpiApp("com.lazypay.app", "LazyPay", listOf("LAZYPAY"), 30),
        UpiApp("com.earlysalary.android", "Fibe", listOf("FIBE", "EARLYSALARY"), 40),
        UpiApp("com.whiz.credit", "WhizCredit", listOf("WHIZ"), 35),
        UpiApp("com.zestmoney.android", "ZestMoney", listOf("ZEST"), 35),
        UpiApp("com.payu.india", "PayU", listOf("PAYU"), 25),
        UpiApp("com.msf.angelmobile", "Angel One", listOf("ANGEL"), 25),
        UpiApp("com.groww.app", "Groww", listOf("GROWW"), 25),
        UpiApp("com.mmt.mmtpay", "MakeMyTrip", listOf("MMT"), 20),
        UpiApp("com.irctc.air", "IRCTC", listOf("IRCTC"), 20),
        UpiApp("com.truecaller", "Truecaller Pay", listOf("TRUECALLER"), 25),
        UpiApp("com.whatsapp", "WhatsApp Pay", listOf("WHATSAPP"), 25),
        UpiApp("com.samsung.android.spay", "Samsung Pay", listOf("SAMSUNG"), 25),
        UpiApp("com.navi.moneymanager", "Navi Pay", listOf("NAVI"), 30),
        UpiApp("com.loan.front", "Loan Front", listOf("LOAN"), 40),
        UpiApp("com.buddyloan.app", "Buddy Loan", listOf("BUDDY"), 40),
        UpiApp("com.rupilo.android", "Rupilo", listOf("RUPILO"), 35),
        UpiApp("com.moneytap.app", "MoneyTap", listOf("MONEYTAP"), 35),
        UpiApp("com.paysense.android", "PaySense", listOf("PAYSENSE"), 35),
        UpiApp("com.branch_international.branch.branch_demo_android", "Branch", listOf("BRANCH"), 40),
        UpiApp("com.cashfree", "Cashfree", listOf("CASHFREE"), 25),
        UpiApp("com.razorpay.payments", "Razorpay", listOf("RAZORPAY"), 25),
    )

    fun defaultHookMap(): Map<String, Boolean> =
        ALL.associate { it.packageName to false }

    fun findByPackage(packageName: String): UpiApp? =
        ALL.firstOrNull { it.packageName == packageName }

    fun displayNameFor(packageName: String): String {
        return findByPackage(packageName)?.displayName
            ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    fun enabledAppsFromConfig(hooked: Map<String, Boolean>): List<UpiApp> {
        val active = hooked.filter { it.value }.keys
        return active.map { pkg ->
            findByPackage(pkg) ?: UpiApp(pkg, displayNameFor(pkg), listOf("OTP", "UPI", "VERIFY"))
        }
    }

    fun matchAmong(apps: List<UpiApp>, sender: String, body: String): UpiApp? {
        if (apps.isEmpty()) return null
        matchAmongBySender(apps, sender)?.let { return it }
        matchAmongByBody(apps, body)?.let { return it }
        if (SmsMatcher.isEncryptedToken(body)) {
            return matchAmongBySender(apps, sender)
        }
        return null
    }

    fun matchAmongBySender(apps: List<UpiApp>, sender: String): UpiApp? {
        val upper = sender.uppercase().replace("\\s".toRegex(), "")
        if (upper.isBlank()) return null
        return apps.firstOrNull { app ->
            app.smsKeywords.any { kw -> upper.contains(kw) }
        }
    }

    fun matchAmongByBody(apps: List<UpiApp>, body: String): UpiApp? {
        val upper = body.uppercase()
        if (upper.isBlank()) return null
        return apps.firstOrNull { app ->
            app.smsKeywords.any { kw -> upper.contains(kw) }
        }
    }

    fun defaultTimerMap(): Map<String, Int> =
        ALL.associate { app ->
            val bonus = if (app.needs2faBonus) app.timerBonusSeconds + 10 else app.timerBonusSeconds
            app.packageName to bonus
        }

    fun timerForPackage(packageName: String, overrides: Map<String, Int>): Int {
        overrides[packageName]?.let { return it }
        return ALL.firstOrNull { it.packageName == packageName }?.let { app ->
            if (app.needs2faBonus) app.timerBonusSeconds + 10 else app.timerBonusSeconds
        } ?: 20
    }

    fun matchApp(sender: String, body: String): UpiApp? {
        matchAppBySender(sender)?.let { return it }
        return matchAppByBody(body)
    }

    fun matchAppBySender(sender: String): UpiApp? {
        val upper = sender.uppercase().replace("\\s".toRegex(), "")
        return ALL.firstOrNull { app ->
            app.smsKeywords.any { kw -> upper.contains(kw) }
        }
    }

    fun matchAppByBody(body: String): UpiApp? {
        val upper = body.uppercase()
        return ALL.firstOrNull { app ->
            app.smsKeywords.any { upper.contains(it) }
        }
    }
}
