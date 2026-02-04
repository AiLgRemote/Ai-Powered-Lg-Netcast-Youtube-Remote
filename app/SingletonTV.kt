package com.tatilacratita.lgcast.sampler

import com.connectsdk.device.ConnectableDevice

object SingletonTV {
    private var tv: ConnectableDevice? = null

    fun getInstance(): SingletonTV {
        return this
    }

    fun getTV(): ConnectableDevice? {
        return tv
    }

    fun setTV(tv: ConnectableDevice) {
        this.tv = tv
    }
}