package com.amurcanov.tgwsproxy

import androidx.annotation.StringRes

enum class SettingsTab(@StringRes val titleRes: Int) {
    CONNECTION(R.string.settings_tab_connection),
    ROUTES(R.string.settings_tab_routes),
    CLOUDFLARE(R.string.settings_tab_cloudflare),
    APP(R.string.settings_tab_app),
}
