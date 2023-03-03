package kama.atom.scanner

interface BeaconScannerListener {
    fun onEvent(event: Int, distance: Double)
}