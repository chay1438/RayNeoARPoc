package com.example.indoorar

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

class IndoorARApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}
