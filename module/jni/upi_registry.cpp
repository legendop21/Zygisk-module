#include "upi_registry.hpp"

#include <cstdio>
#include <cstring>

namespace upi_registry {

namespace {

constexpr const char* kPackages[] = {
    "com.phonepe.app",
    "net.one97.paytm",
    "com.google.android.apps.nbu.paisa.user",
    "com.yespay.next",
    "com.yesbank.yespay",
    "com.yesbank.yespaynext",
    "com.snapmint.customerapp",
    "com.tataneu",
    "com.stashfin.android",
    "com.kreditbee.android",
    "com.dreamplug.androidapp",
    "com.mobikwik_new",
    "com.freecharge.android",
    "in.org.npci.upiapp",
    "com.popclub.android",
    "com.myairtelapp",
    "com.jio.myjio",
    "com.csam.icici.bank.imobile",
    "com.sbi.lotusintouch",
    "com.axis.mobile",
    "com.hdfcbank.payzapp",
    "com.bankofbaroda.mpassbook",
    "com.idfcfirstbank.mobile",
    "com.kotak811mobilebankingapp",
    "com.whizdm.moneyview.loans",
    "com.naviapp",
    "com.bharatpe.app",
    "com.rapipay",
    "com.fampay.in",
    "com.slice.app",
    "com.postpe.app",
    "com.olacabs.customer",
    "com.application.zomato",
    "in.swiggy.android",
    "com.meesho.supply",
    "com.flipkart.android",
    "com.lazypay.app",
    "com.earlysalary.android",
    "com.whiz.credit",
    "com.zestmoney.android",
    "com.payu.india",
    "com.msf.angelmobile",
    "com.groww.app",
    "com.nextbillion.groww",
    "com.herofincorp.diyjourneys",
    "com.herofincorp.simplycash",
    "com.customer.herofincorp",
    "com.mmt.mmtpay",
    "com.irctc.air",
    "com.samsung.android.spay",
    "com.navi.moneymanager",
    "com.loan.front",
    "com.buddyloan.app",
    "com.rupilo.android",
    "com.moneytap.app",
    "com.paysense.android",
    "com.branch_international.branch.branch_demo_android",
    "com.cashfree",
    "com.razorpay.payments",
    "com.mpokket.app",
    "com.cashe.android",
    "com.rupeeredee.app",
    "com.loan.tap",
    "com.smartcoin",
    "com.kissht.android",
    "com.flexsalary",
    "com.nira.finance",
    "com.availfinance",
    "com.indialends.android",
    "com.homecredit",
    "com.bajajfinserv",
    "com.hdbfs.hdbfsl",
    "in.medibuddy",
    // BHIM / bank UPI
    "com.fss.pnbupi",
    "com.fss.unbipnb",
    "com.fss.vijaya",
    "com.bankofbaroda.upi",
    "com.snapwork.IDBI",
    "com.infrasofttech.CentralBankUPI",
    "com.YesBank",
    "com.sbi.SBIFreedomPlus",
    "com.phonepe.app.br",
    "com.google.android.apps.nbu.paisa.merchant",
    "money.jupiter",
    "com.upi.axispay",
    "com.enstage.wibmo.hdfc",
    "com.mycompany.kbl",
    "com.fi.mbanking",
    "com.jump.app",
    "in.jploft.jump",
    "com.naviapp",
    "com.fisglobal.esafupi.app",
    "com.sbi.upi",
    "com.pnb.mobile",
    // --- expanded UPI / fintech (correct Play Store package IDs) ---
    "com.paytmmoney",
    "com.jar.app",
    "com.supermoney",
    "com.epifi.paisa",
    "com.angelbroking.angelone",
    "com.axis.acquiring",
    "com.yesbank.mobile",
    "com.balancehero.truebalance",
    "com.onecode.hyper",
    "com.uniclaret.app",
    "com.smallcase.android",
    "com.paisabazaar",
    "com.snapwork.hdfc",
    "com.niyo.equitassavingsaccount",
    "com.fisglobal.bandhanupi",
    "com.upi.federalbank.org.ifsc.upiapp",
    "com.canarabank.mobility",
    "com.unionbank.ebanking",
    "com.indusind.indie",
    "com.rbl.rblimobile",
    "com.fincarebank",
    "com.au.bank.android",
    "com.fedmobile",
    "com.southindianbank.eremit",
    "com.kvb.mobilebanking",
    "com.dbs.in.digitalbank",
    "com.tatadigital.tcp",
    "com.shriram.One",
    "com.msf.sbi",
    "com.msf.bob",
    "com.msf.axis",
    // more UPI / fintech / wallets
    "club.bharatpe.app",
    "com.bhimupi.app",
    "com.npci.upi",
    "com.ultracash.ultrapay",
    "com.paytm.business",
    "com.phonepe.app.business",
    "in.amazon.mShop.android.shopping",
    "com.indwealth",
    "com.jupiter.customers",
    "com.fi.money",
    "money.jupiter",
    "com.onecode.bharatx",
    "com.unity.finance",
    "com.loanfront.app",
    "com.stashfin",
    "com.kreditbee",
    "com.whizdm.moneyview",
    "com.moneyview.android",
    "com.bajajfinservmarkets",
    "com.bajajfinserv.in",
    "com.tatacapital.moneyfy",
    "com.indusind.indusmobile",
    "com.pnb.mobile",
    "com.bob.mobilebanking",
    "com.iob.mobilebanking",
    "com.ucobank.mobilebanking",
    "com.bandhan.mobilebanking",
    "com.ab.abmbanking",
    "com.csb.mobilebanking",
    // --- user home-screen UPI / fintech (Zygisk Next targets) ---
    "com.fisglobal.esafupi.app",
    "com.sbi.upi",
    "com.freecharge.business",
    "com.freecharge.merchant",
    "com.freecharge.android.business",
    "com.herofincorp.android",
    "com.herofincorp.lending",
    "com.herofincorp.upi",
    "money.super.app",
    "money.super.payments",
    "com.supermoney.app",
    "com.supermoney",
    "in.fampay.app",
    "com.fampay.android",
    "com.postpe",
    "com.bharatpe.merchant",
    "com.yesbank.yespay.uat",
    "com.yespay",
    "com.tataneu.android",
    "com.tatadigital",
    "com.popclub",
    "com.kiwi.mobile",
    "in.gokiwi.app",
    "com.navi.insurance",
    "com.navi",
    "com.phonepe.merchant",
    "com.google.android.apps.nbu.paisa.merchant",
    nullptr,
};

}  // namespace

bool is_known_upi(const std::string& package) {
    if (package.empty()) return false;
    for (const char* const* p = kPackages; *p; ++p) {
        if (package == *p) return true;
    }
    if (package.rfind("com.herofincorp", 0) == 0) return true;
    if (package.rfind("com.hero", 0) == 0 && package.find("fincorp") != std::string::npos) {
        return true;
    }
    // Broad UPI/fintech name heuristics — more apps get bubble
    if (package.find("upi") != std::string::npos) return true;
    if (package.find("paytm") != std::string::npos) return true;
    if (package.find("phonepe") != std::string::npos) return true;
    if (package.find("bhim") != std::string::npos) return true;
    if (package.find("kredit") != std::string::npos) return true;
    if (package.find("loan") != std::string::npos) return true;
    if (package.find("wallet") != std::string::npos) return true;
    if (package.find("fintech") != std::string::npos) return true;
    if (package.find("banking") != std::string::npos) return true;
    if (package.find("moneyview") != std::string::npos) return true;
    if (package.find("mobikwik") != std::string::npos) return true;
    if (package.find("freecharge") != std::string::npos) return true;
    if (package.find("payzapp") != std::string::npos) return true;
    if (package.find("groww") != std::string::npos) return true;
    if (package.find("fampay") != std::string::npos) return true;
    if (package.find("stashfin") != std::string::npos) return true;
    if (package.find("snapmint") != std::string::npos) return true;
    if (package.find("supermoney") != std::string::npos) return true;
    if (package.rfind("money.super.", 0) == 0) return true;
    if (package.find("bharatpe") != std::string::npos) return true;
    if (package.find("postpe") != std::string::npos) return true;
    if (package.find("esaf") != std::string::npos) return true;
    if (package.find("airtel") != std::string::npos) return true;
    if (package.find("paisa") != std::string::npos) return true;
    if (package.find("nbu.paisa") != std::string::npos) return true;
    if (package.find("dreamplug") != std::string::npos) return true;
    if (package.find("slice") != std::string::npos) return true;
    if (package.find("jupiter") != std::string::npos) return true;
    if (package.find("navi") != std::string::npos) return true;
    if (package.find("esaf") != std::string::npos) return true;
    if (package.find("yesbank") != std::string::npos) return true;
    if (package.find("yespay") != std::string::npos) return true;
    if (package.find("herofincorp") != std::string::npos) return true;
    if (package.find("fincorp") != std::string::npos) return true;
    if (package.find("jump") != std::string::npos) return true;  // jUMPP
    if (package.find("snapmint") != std::string::npos) return true;
    if (package.find("kreditbee") != std::string::npos) return true;
    if (package.find("flipkart") != std::string::npos) return true;
    if (package.find("phonepe") != std::string::npos) return true;
    if (package.find("paytm") != std::string::npos) return true;
    // Safer bank patterns — avoid broad "pay"/"cash" (crashes random apps)
    if (package.find("bank") != std::string::npos) return true;
    if (package.find("upi") != std::string::npos) return true;
    if (package.find("bhim") != std::string::npos) return true;
    if (package.find("wallet") != std::string::npos) return true;
    if (package.find("axis") != std::string::npos) return true;
    if (package.find("hdfc") != std::string::npos) return true;
    if (package.find("icici") != std::string::npos) return true;
    if (package.find("kotak") != std::string::npos) return true;
    return false;
}

bool is_module_own_app(const std::string& package) {
    return package == "com.hivirtus.zygiskmode";
}

bool is_launcher_package(const std::string& package) {
    if (package.empty()) return false;
    static const char* kLaunchers[] = {
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.android.launcher",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.sec.android.app.launcher",
        "com.oppo.launcher",
        "com.realme.launcher",
        "com.nothing.launcher",
        "com.transsion.hilauncher",
        "com.bbk.launcher2",
        "com.huawei.android.launcher",
        "com.tblenovo.launcher",
        "com.motorola.launcher3",
        "com.asus.launcher",
        "com.sonymobile.home",
        "com.oneplus.launcher",
        nullptr,
    };
    for (const char** name = kLaunchers; *name; ++name) {
        if (package == *name) return true;
    }
    if (package.find("launcher") != std::string::npos) return true;
    if (package == "com.miui.home") return true;
    return false;
}

bool is_default_sms_app(const std::string& package) {
    if (package.empty()) return false;
    static const char* kSms[] = {
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.messaging",
        "com.android.mms",  // A11 stock Messages — SENDTO real SIM path
        "com.google.android.apps.messaging.auto",
        "com.motorola.messaging",
        "com.oneplus.mms",
        "com.coloros.mms",
        // Platform service that actually talks to radio ISms on many OEMs
        "com.android.mms.service",
        nullptr,
    };
    for (const char** p = kSms; *p; ++p) {
        if (package == *p) return true;
    }
    return false;
}

bool is_denied_hook_package(const std::string& package) {
    if (package.empty()) return true;

    // EXCEPTION: default SMS apps + mms.service — real SIM send path
    if (is_default_sms_app(package)) return false;

    // NEVER phone/telephony/settings — SIM + Settings crash
    static const char* kDenyExact[] = {
        "com.android.phone",
        "com.android.providers.telephony",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.keychain",
        // mms.service ALLOWED via is_default_sms_app (v1.0.46)
        "com.samsung.android.settings",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.dialer",
        "zygote",
        "zygote64",
        "system_server",
        nullptr,
    };
    for (const char** name = kDenyExact; *name; ++name) {
        if (package == *name) return true;
    }
    if (is_launcher_package(package)) return true;
    if (package.rfind("com.android.", 0) == 0) return true;
    if (package.rfind("android.", 0) == 0) return true;
    if (package.find("telephony") != std::string::npos) return true;
    if (package.find("inputmethod") != std::string::npos) return true;
    return false;
}

bool is_whitelisted_hook_process(const std::string& package) {
    if (package.empty()) return false;
    if (package == "zygote" || package == "zygote64" || package == "system_server") return false;
    if (is_module_own_app(package)) return false;
    if (is_denied_hook_package(package)) return false;
    if (is_launcher_package(package)) return false;
    return is_sms_hook_target(package);
}

bool is_hookable_user_app(const std::string& package) {
    if (is_denied_hook_package(package)) return false;
    return true;
}

// Banking/UPI + default SMS apps (SENDTO → Messages real SIM catch)
bool is_sms_hook_target(const std::string& package) {
    if (package.empty()) return false;
    if (is_default_sms_app(package)) return true;
    if (is_denied_hook_package(package)) return false;
    static const char* kExcludeCrash[] = {
        "com.application.zomato",
        "in.swiggy.android",
        "com.olacabs.customer",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.meesho.supply",
        "com.truecaller",
        "com.amazon.mShop.android.shopping",
        "in.amazon.mShop.android.shopping",
        "com.amazon.avod.thirdpartyclient",
        // Flipkart UPI allowed (delayed hooks) — user needs FKARTUPI
        "com.instagram.android",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.google.android.gm",
        "com.google.android.youtube",
        "com.android.chrome",
        "com.android.vending",
        nullptr,
    };
    for (const char** p = kExcludeCrash; *p; ++p) {
        if (package == *p) return false;
    }
    return is_known_upi(package);
}

bool is_fragile_banking_app(const std::string& package) {
    if (package.empty()) return false;
    // ALL non-Messages UPI/banking targets are fragile — heavy Binder/phone hooks crash on open.
    // Messages stays non-fragile for full ISms intercept.
    if (is_default_sms_app(package)) return false;
    if (is_sms_hook_target(package)) return true;
    return false;
}

namespace {

bool file_lists_pkg(const char* path, const std::string& package) {
    FILE* f = fopen(path, "r");
    if (!f) return false;
    char line[256];
    bool hit = false;
    while (fgets(line, sizeof(line), f)) {
        // trim
        char* s = line;
        while (*s == ' ' || *s == '\t') ++s;
        size_t n = strlen(s);
        while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r' || s[n - 1] == ' ')) {
            s[--n] = 0;
        }
        if (n == 0 || s[0] == '#') continue;
        if (package == s) {
            hit = true;
            break;
        }
    }
    fclose(f);
    return hit;
}

}  // namespace

bool is_no_inline_hook_pkg(const std::string& package) {
    if (package.empty() || is_default_sms_app(package)) return false;
    // Built-in ultra-crashy apps (LSPosed "Invalidate inline hooks" equivalent)
    static const char* kNoInline[] = {
        "money.super.payments",
        "money.super.app",
        "com.supermoney.app",
        "com.supermoney",
        "com.snapmint.customerapp",
        "com.kreditbee.android",
        "com.stashfin.android",
        "com.naviapp",
        "com.whizdm.moneyview.loans",
        "com.mpokket.app",
        "com.cashe.android",
        "com.kissht.android",
        "com.earlysalary.android",
        "com.zestmoney.android",
        "com.branch_international.branch.branch_demo_android",
        nullptr,
    };
    for (const char* const* p = kNoInline; *p; ++p) {
        if (package == *p) return true;
    }
    if (package.rfind("money.super.", 0) == 0) return true;
    if (package.find("supermoney") != std::string::npos) return true;
    // User / module override lists (one package per line)
    if (file_lists_pkg("/data/local/tmp/hivirtus_no_inline_packages.txt", package)) return true;
    if (file_lists_pkg("/data/adb/modules/hivirtus_zygisk_mode/no_inline_packages.txt", package)) {
        return true;
    }
    return false;
}

int hook_startup_delay_sec(const std::string& package) {
    if (is_no_inline_hook_pkg(package)) return 8;
    if (is_fragile_banking_app(package)) return 5;
    if (package.find("bank") != std::string::npos) return 3;
    return 1;
}

}  // namespace upi_registry
