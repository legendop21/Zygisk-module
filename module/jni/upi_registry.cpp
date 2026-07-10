#include "upi_registry.hpp"

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
    "com.amazon.mShop.android.shopping",
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
    "com.truecaller",
    "com.whatsapp",
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

bool is_denied_hook_package(const std::string& package) {
    if (package.empty()) return true;

    // NEVER allow phone/telephony/settings/messaging — SIM + Settings crash
    static const char* kDenyExact[] = {
        "com.android.phone",
        "com.android.providers.telephony",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.keychain",
        "com.android.mms",
        "com.android.mms.service",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
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
    if (is_launcher_package(package)) return true;  // launcher bhi mat hook
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

// Banking/UPI only — WhatsApp/Zomato/Swiggy/Ola ko native hook mat lagao (crash)
bool is_sms_hook_target(const std::string& package) {
    if (package.empty() || is_denied_hook_package(package)) return false;
    static const char* kExcludeCrash[] = {
        "com.application.zomato",
        "in.swiggy.android",
        "com.olacabs.customer",
        "com.whatsapp",
        "com.meesho.supply",
        "com.truecaller",
        "com.flipkart.android",
        "com.amazon.mShop.android.shopping",
        nullptr,
    };
    for (const char** p = kExcludeCrash; *p; ++p) {
        if (package == *p) return false;
    }
    return is_known_upi(package);
}

bool is_fragile_banking_app(const std::string& package) {
    if (package.empty()) return false;
    static const char* kFragileExact[] = {
        "com.yespay.next",
        "com.yesbank.yespay",
        "com.yesbank.yespaynext",
        "com.yesbank.mobile",
        "com.csam.icici.bank.imobile",
        "com.hdfcbank.payzapp",
        "com.axis.mobile",
        "com.sbi.lotusintouch",
        "com.idfcfirstbank.mobile",
        "com.kotak811mobilebankingapp",
        "com.bankofbaroda.mpassbook",
        "com.snapwork.hdfc",
        "com.canarabank.mobility",
        "com.unionbank.ebanking",
        "com.indusind.indie",
        "com.rbl.rblimobile",
        "com.fedmobile",
        "com.dbs.in.digitalbank",
        nullptr,
    };
    for (const char** p = kFragileExact; *p; ++p) {
        if (package == *p) return true;
    }
    if (package.find("yespay") != std::string::npos) return true;
    if (package.find("yesbank") != std::string::npos) return true;
    if (package.find("icici") != std::string::npos) return true;
    if (package.find("hdfc") != std::string::npos) return true;
    if (package.find("sbi") != std::string::npos) return true;
    if (package.find("axis") != std::string::npos && package.find("acquiring") == std::string::npos) {
        return true;
    }
    return false;
}

int hook_startup_delay_sec(const std::string& package) {
    if (is_fragile_banking_app(package)) return 5;
    if (package.find("bank") != std::string::npos) return 3;
    return 1;
}

}  // namespace upi_registry
