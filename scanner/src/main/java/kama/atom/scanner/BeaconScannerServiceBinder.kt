package kama.atom.scanner

import android.os.Binder

class BeaconScannerServiceBinder(private val beaconService: BeaconScannerDelegate) : Binder() {
    internal val service: BeaconScannerDelegate
        get() = beaconService
}