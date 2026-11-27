package com.aistudio.santrirank.mcdfbx

import android.app.Application
import com.aistudio.santrirank.mcdfbx.security.AppCheckHelper

class SantriRankApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Inicializa Firebase App Check (Play Integrity) com fallback seguro em debug
        AppCheckHelper.initialize(this)
    }
}
