package com.hivirtus.zygiskmode.overlay;

final class MenuBridgeActions implements JsBridge.MenuActions {

    private final FloatMenu menu;

    MenuBridgeActions(FloatMenu menu) {
        this.menu = menu;
    }

    @Override
    public void onClose() {
        menu.hide();
    }

    @Override
    public void onSaved() {
        // config synced to local json
    }
}
