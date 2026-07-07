package com.hivirtus.zygiskmode

object UpiAppRegistry {

    data class UpiApp(
        val packageName: String,
        val displayName: String,
        val smsKeywords: List<String> = emptyList()
    )

    val ALL: List<UpiApp> = listOf(
        UpiApp("com.phonepe.app", "PhonePe", listOf("PHONEPE", "PPAY")),
        UpiApp("net.one97.paytm", "Paytm", listOf("PAYTM", "PYTM")),
        UpiApp("com.google.android.apps.nbu.paisa.user", "Google Pay", listOf("GPAY", "GOOGLEPAY")),
        UpiApp("com.yespay.next", "YesPay Next", listOf("YESPRO", "YESPAY", "YESBANK")),
        UpiApp("com.yesbank.yespay", "YesPay", listOf("YESPRO", "YESPAY")),
        UpiApp("com.snapmint.customerapp", "Snapmint", listOf("SNAPMINT", "SNAP")),
        UpiApp("com.tataneu", "Tata Neu", listOf("TATANEU", "TATA")),
        UpiApp("com.stashfin.android", "Stashfin", listOf("STASHFIN", "STASH")),
        UpiApp("com.kreditbee.android", "KreditBee", listOf("KREDITBEE", "KREDIT")),
        UpiApp("com.dreamplug.androidapp", "CRED", listOf("CRED")),
        UpiApp("com.mobikwik_new", "Mobikwik", listOf("MOBIKWIK")),
        UpiApp("com.freecharge.android", "Freecharge", listOf("FREECHARGE", "FCASH")),
        UpiApp("in.org.npci.upiapp", "BHIM UPI", listOf("BHIM", "NPCI")),
        UpiApp("com.amazon.mShop.android.shopping", "Amazon Pay", listOf("AMAZON", "AMZPAY")),
        UpiApp("com.popclub.android", "POP UPI", listOf("POPUPI", "POP")),
        UpiApp("com.myairtelapp", "Airtel Thanks", listOf("AIRTEL")),
        UpiApp("com.jio.myjio", "MyJio", listOf("JIO")),
        UpiApp("com.csam.icici.bank.imobile", "iMobile ICICI", listOf("ICICI", "IMOBILE")),
        UpiApp("com.sbi.lotusintouch", "YONO SBI", listOf("SBI", "YONO")),
        UpiApp("com.axis.mobile", "Axis Mobile", listOf("AXIS")),
        UpiApp("com.hdfcbank.payzapp", "PayZapp HDFC", listOf("HDFC", "PAYZAPP")),
        UpiApp("com.bankofbaroda.mpassbook", "BOB World", listOf("BOB", "BARODA")),
        UpiApp("com.idfcfirstbank.mobile", "IDFC First", listOf("IDFC")),
        UpiApp("com.kotak811mobilebankingapp", "Kotak 811", listOf("KOTAK")),
        UpiApp("com.whizdm.moneyview.loans", "MoneyView", listOf("MONEYVIEW")),
        UpiApp("com.naviapp", "Navi", listOf("NAVI")),
        UpiApp("com.bharatpe.app", "BharatPe", listOf("BHARATPE")),
        UpiApp("com.rapipay", "Rapipay", listOf("RAPIPAY")),
        UpiApp("com.fampay.in", "FamPay", listOf("FAMPAY")),
        UpiApp("com.slice.app", "Slice", listOf("SLICE")),
        UpiApp("com.postpe.app", "PostPe", listOf("POSTPE")),
        UpiApp("com.olacabs.customer", "Ola", listOf("OLA")),
        UpiApp("com.application.zomato", "Zomato", listOf("ZOMATO")),
        UpiApp("in.swiggy.android", "Swiggy", listOf("SWIGGY")),
        UpiApp("com.meesho.supply", "Meesho", listOf("MEESHO")),
        UpiApp("com.flipkart.android", "Flipkart", listOf("FLIPKART")),
        UpiApp("com.lazypay.app", "LazyPay", listOf("LAZYPAY")),
        UpiApp("com.earlysalary.android", "Fibe", listOf("FIBE", "EARLYSALARY")),
        UpiApp("com.whiz.credit", "WhizCredit", listOf("WHIZ")),
        UpiApp("com.zestmoney.android", "ZestMoney", listOf("ZEST")),
        UpiApp("com.payu.india", "PayU", listOf("PAYU")),
        UpiApp("com.msf.angelmobile", "Angel One", listOf("ANGEL")),
        UpiApp("com.groww.app", "Groww", listOf("GROWW")),
        UpiApp("com.mmt.mmtpay", "MakeMyTrip", listOf("MMT")),
        UpiApp("com.irctc.air", "IRCTC", listOf("IRCTC")),
        UpiApp("com.truecaller", "Truecaller Pay", listOf("TRUECALLER")),
        UpiApp("com.whatsapp", "WhatsApp Pay", listOf("WHATSAPP")),
        UpiApp("com.samsung.android.spay", "Samsung Pay", listOf("SAMSUNG")),
        UpiApp("com.navi.moneymanager", "Navi Pay", listOf("NAVI")),
        UpiApp("com.loan.front", "Loan Front", listOf("LOAN")),
        UpiApp("com.buddyloan.app", "Buddy Loan", listOf("BUDDY")),
        UpiApp("com.rupilo.android", "Rupilo", listOf("RUPILO")),
        UpiApp("com.moneytap.app", "MoneyTap", listOf("MONEYTAP")),
        UpiApp("com.paysense.android", "PaySense", listOf("PAYSENSE")),
        UpiApp("com.branch_international.branch.branch_demo_android", "Branch", listOf("BRANCH")),
        UpiApp("com.cashfree", "Cashfree", listOf("CASHFREE")),
        UpiApp("com.razorpay.payments", "Razorpay", listOf("RAZORPAY")),
    )

    fun defaultHookMap(): Map<String, Boolean> =
        ALL.associate { it.packageName to true }

    fun matchAppBySender(sender: String): UpiApp? {
        val upper = sender.uppercase()
        return ALL.firstOrNull { app ->
            app.smsKeywords.any { upper.contains(it) }
        }
    }
}
