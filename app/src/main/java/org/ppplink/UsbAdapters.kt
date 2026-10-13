package org.ppplink

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/** Finds supported USB serial adapters (default prober plus explicit PL2303 IDs). */
object UsbAdapters {
    private const val PROLIFIC_VID = 0x067B

    private val customProber: UsbSerialProber by lazy {
        val table = ProbeTable()
        table.addProduct(PROLIFIC_VID, 0x2303, ProlificSerialDriver::class.java)
        table.addProduct(PROLIFIC_VID, 0x23A3, ProlificSerialDriver::class.java)
        UsbSerialProber(table)
    }

    fun findDriver(context: Context): UsbSerialDriver? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
            ?: customProber.findAllDrivers(usbManager).firstOrNull()
    }

    fun isSupported(device: UsbDevice): Boolean =
        UsbSerialProber.getDefaultProber().probeDevice(device) != null ||
            customProber.probeDevice(device) != null

    fun describe(device: UsbDevice): String {
        val name = device.productName?.takeIf { it.isNotBlank() } ?: "USB serial"
        return String.format("%s (%04X:%04X)", name, device.vendorId, device.productId)
    }
}
