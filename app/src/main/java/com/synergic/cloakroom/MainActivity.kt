package com.synergic.cloakroom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.synergic.cloakroom.ui.AppRoot
import com.synergic.cloakroom.ui.theme.CloakRoomTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // The answer is read when printing (BluetoothPrinterPort), so nothing to do here.
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askBluetoothPermission(firstStart = savedInstanceState == null)
        setContent {
            CloakRoomTheme {
                AppRoot()
            }
        }
    }

    /**
     * Android 12+ needs "Nearby devices" to print on a paired Bluetooth printer. Asked once when
     * the app opens, so a receipt printed right after check-in does not wait for a dialog.
     */
    private fun askBluetoothPermission(firstStart: Boolean) {
        if (!firstStart || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val permission = Manifest.permission.BLUETOOTH_CONNECT
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(permission)
        }
    }
}
