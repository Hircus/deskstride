package io.github.deskstride

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

enum class BleState { IDLE, SEARCHING, CONNECTING, CONNECTED, ERROR }

/**
 * Treadmill connection. Sequence: connect -> MTU 247 -> discover -> notifications on 0xFBA2 ->
 * status request [TreadmillCommands.POLL] on 0xFBA1 every [pollMs] (same as the PitPat app).
 * Commands (start, stop, speed) are only sent on explicit request via [send].
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity before start()
class TreadmillBle(
    private val ctx: Context,
    private val onPacket: (TreadmillPacket) -> Unit,
    private val onStatus: (BleState, String) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var wanted = false
    private val pollMs = 500L

    private val svc = uuid16(0xFBA0)
    private val cmdUuid = uuid16(0xFBA1)
    private val notifyUuid = uuid16(0xFBA2)
    private val cccd = uuid16(0x2902)

    val active get() = wanted
    /** address of the treadmill picked in the scanner; null = first PitPat found */
    private var address: String? = null

    private fun report(state: BleState, res: Int, vararg args: Any) = onStatus(state, ctx.getString(res, *args))

    fun start(address: String? = null) { this.address = address; wanted = true; scan() }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        stopDiscover()
        adapter.bluetoothLeScanner?.stopScan(scanCb)
        gatt?.close(); gatt = null; cmdChar = null
        commands.clear(); inFlightSince = 0L
        report(BleState.IDLE, R.string.status_disconnected)
    }

    private fun scan() {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { report(BleState.ERROR, R.string.status_bt_off); wanted = false; return }
        report(BleState.SEARCHING, R.string.status_searching)
        // with a saved treadmill we filter by address: filtered scans keep running with the screen off
        val filters = address?.let { listOf(ScanFilter.Builder().setDeviceAddress(it).build()) }
        scanner.startScan(filters, ScanSettings.Builder().build(), scanCb)
    }

    /** true when the connection is ready to accept commands */
    val ready get() = gatt != null && cmdChar != null

    // One GATT write at a time: user commands take priority over status requests.
    private val commands = ArrayDeque<ByteArray>()
    private var inFlightSince = 0L

    /** Queues a command for the treadmill (call from the main thread). */
    fun send(cmd: ByteArray) {
        if (!ready) return
        commands.addLast(cmd)
        pump()
    }

    private fun pump(poll: Boolean = false) {
        val g = gatt ?: return; val c = cmdChar ?: return
        val now = System.currentTimeMillis()
        if (inFlightSince != 0L && now - inFlightSince < 2000) return // lost callback? retry after 2 s
        val next = commands.removeFirstOrNull() ?: if (poll) TreadmillCommands.POLL else return
        val rc = g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc == BluetoothStatusCodes.SUCCESS) {
            inFlightSince = now
            if (next !== TreadmillCommands.POLL) Log.d(TAG, "cmd ${next.joinToString("") { "%02x".format(it) }}")
        } else if (next !== TreadmillCommands.POLL) {
            commands.addFirst(next) // GATT busy: the command goes out on the next round
        }
    }

    private val poller = object : Runnable {
        override fun run() {
            pump(poll = true)
            main.postDelayed(this, pollMs)
        }
    }

    /** Compatible treadmill: PitPat name or 0xFBA0 service in the advertising data. */
    private fun isTreadmill(r: ScanResult): Boolean {
        val name = r.scanRecord?.deviceName ?: r.device.name
        return name?.startsWith("PitPat", ignoreCase = true) == true ||
            r.scanRecord?.serviceUuids?.any { it.uuid == svc } == true
    }

    // --- scanner: list treadmills without connecting ------------------------------------

    private var onFound: ((ScanResult) -> Unit)? = null

    /** Scan for the scanner dialog; results arrive on the main thread. A connected treadmill doesn't advertise. */
    fun discover(onFound: (ScanResult) -> Unit) {
        stopDiscover()
        this.onFound = onFound
        adapter.bluetoothLeScanner?.startScan(discoverCb)
    }

    fun stopDiscover() {
        if (onFound == null) return
        onFound = null
        adapter.bluetoothLeScanner?.stopScan(discoverCb)
    }

    private val discoverCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) { if (isTreadmill(r)) onFound?.invoke(r) }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "discover failed $errorCode") }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (address?.let { r.device.address != it } ?: !isTreadmill(r)) return
            adapter.bluetoothLeScanner?.stopScan(this)
            report(BleState.CONNECTING, R.string.status_connecting)
            gatt = r.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            main.post { report(BleState.ERROR, R.string.status_scan_failed, errorCode) }
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // status replies are 60 bytes: with the default MTU (23) they would be truncated
                if (!g.requestMtu(247)) g.discoverServices()
            } else {
                g.close(); gatt = null; cmdChar = null
                main.post {
                    main.removeCallbacks(poller)
                    commands.clear(); inFlightSince = 0L
                    onDisconnected()
                    if (wanted) report(BleState.SEARCHING, R.string.status_reconnecting)
                }
                // Same treadmill, no scans (Android throttles repeated scans and stops them with the screen off):
                // with autoConnect the system waits for it and reconnects as soon as it's reachable again.
                val cb = this
                if (wanted) main.postDelayed({
                    if (wanted && gatt == null) gatt = g.device.connectGatt(ctx, true, cb, BluetoothDevice.TRANSPORT_LE)
                }, 1000)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "mtu=$mtu status=$status")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val s = g.getService(svc)
            val notify = s?.getCharacteristic(notifyUuid)
            if (notify == null) { main.post { report(BleState.ERROR, R.string.status_service_not_found) }; return }
            cmdChar = s.getCharacteristic(cmdUuid)
            g.setCharacteristicNotification(notify, true)
            g.writeDescriptor(notify.getDescriptor(cccd), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            main.post { report(BleState.CONNECTED, R.string.status_connected) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // notifications enabled: start requesting the full status
            main.post { main.removeCallbacks(poller); main.post(poller) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post { inFlightSince = 0L; if (commands.isNotEmpty()) pump() }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            TreadmillPacket.parse(value)?.let { p -> main.post { onPacket(p) } }
        }
    }

    private fun uuid16(v: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v))

    companion object { private const val TAG = "DeskStrideBle" }
}
