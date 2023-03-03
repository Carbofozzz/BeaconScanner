package kama.atom.scanner

import android.content.Context
import android.content.Intent

class BeaconScanner {

    companion object {

        fun init(ctx: Context) {
            ctx.startService(Intent(ctx, BeaconScannerService::class.java))
        }

        fun stop() {
            BeaconScannerService.instance?.stopScan()
        }

        fun setListener(listener: BeaconScannerListener?) {
            BeaconScannerService.instance?.setListener(listener)
        }
    }
}