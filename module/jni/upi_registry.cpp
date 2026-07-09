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
    nullptr,
};

}  // namespace

bool is_known_upi(const std::string& package) {
    if (package.empty()) return false;
    for (const char* const* p = kPackages; *p; ++p) {
        if (package == *p) return true;
    }
    return false;
}

bool is_denied_hook_package(const std::string& package) {
    if (package.empty()) return true;
    static const char* kDenyExact[] = {
        "com.android.phone",
        "com.android.providers.telephony",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.keychain",
        "zygote",
        "zygote64",
        "system_server",
        nullptr,
    };
    for (const char** name = kDenyExact; *name; ++name) {
        if (package == *name) return true;
    }
    if (package.rfind("com.android.", 0) == 0) return true;
    if (package.rfind("android.", 0) == 0) return true;
    if (package.find("launcher") != std::string::npos) return true;
    if (package.find("inputmethod") != std::string::npos) return true;
    return false;
}

bool is_hookable_user_app(const std::string& package) {
    if (is_denied_hook_package(package)) return false;
    return true;
}

}  // namespace upi_registry
