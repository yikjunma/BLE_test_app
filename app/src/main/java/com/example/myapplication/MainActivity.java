package com.example.myapplication;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.example.myapplication.databinding.ActivityMainBinding;
import com.example.myapplication.databinding.ViewGattServerBinding;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ClientActivity";

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_FINE_LOCATION = 2;
    private static final int SCAN_PERIOD = 7000;
    private ActivityMainBinding mBinding;

    private byte[] ConfigByte;
    private boolean mScanning;
    private Handler mHandler;
    private Handler mLogHandler;
    private Map<String, BluetoothDevice> mScanResults;

    private boolean mConnected;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ScanCallback mScanCallback;
    private BluetoothGatt mGatt;

    private final static UUID DescriptorUUid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothGattCharacteristic IRConfig;
    private BluetoothGattCharacteristic IRData;
    private final static String IRService = "F000aa00-0451-4000-B000-000000000000";
    private final static String IRDataUUID = "F000aa01-0451-4000-B000-000000000000";
    private final static String IRConfigUUID = "F000aa02-0451-4000-B000-000000000000";

    private BluetoothGattCharacteristic HMConfig;
    private BluetoothGattCharacteristic HMData;
    private final static String HMService = "F000aa20-0451-4000-B000-000000000000";
    private final static String HMDataUUID = "F000aa21-0451-4000-B000-000000000000";
    private final static String HMConfigUUID = "F000aa22-0451-4000-B000-000000000000";
    // Lifecycle

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLogHandler = new Handler(Looper.getMainLooper());

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        mBinding.startScanningButton.setOnClickListener(v -> startScan());
        //mBinding.stopScanningButton.setOnClickListener(v -> stopScan());
        mBinding.disconnectButton.setOnClickListener(v -> disconnectGattServer());
        mBinding.viewClientLog.clearLogButton.setOnClickListener(v -> clearLogs());
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check low energy support
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            // Get a newer device
            logError("No LE Support.");
            finish();
        }
    }

    // Scanning
    @TargetApi(23)
    private void startScan() {
        if (!hasPermissions() || mScanning) {
            return;
        }

        disconnectGattServer();

        mBinding.serverListContainer.removeAllViews();

        mScanResults = new HashMap<>();
        mScanCallback = new BtleScanCallback(mScanResults);

        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        List<ScanFilter> filters = new ArrayList<>();

        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();

        mBluetoothLeScanner.startScan(filters, settings, mScanCallback);

        mHandler = new Handler();
        mHandler.postDelayed(this::stopScan, SCAN_PERIOD);

        mScanning = true;
        log("Started scanning.");
    }

    @TargetApi(23)
    private void stopScan() {
        if (mScanning && mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mBluetoothLeScanner != null) {
            mBluetoothLeScanner.stopScan(mScanCallback);
            scanComplete();
        }
        mScanCallback = null;
        mScanning = false;
        mHandler = null;
        log("Stopped scanning.");
    }

    private void scanComplete() {
        if (mScanResults.isEmpty()) {
            return;
        }

        for (String deviceAddress : mScanResults.keySet())
        {
            BluetoothDevice device = mScanResults.get(deviceAddress);
            GattViewModel viewModel = new GattViewModel(device);

            ViewGattServerBinding binding = DataBindingUtil.inflate(LayoutInflater.from(this),R.layout.view_gatt_server, mBinding.serverListContainer,true);
            binding.setViewModel(viewModel);
            binding.connectGattServerButton.setOnClickListener(v -> connectDevice(device));
        }
    }

    private boolean hasPermissions() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            requestBluetoothEnable();
            return false;
        } else if (!hasLocationPermissions()) {
            requestLocationPermission();
            return false;
        }
        return true;
    }

    private void requestBluetoothEnable() {
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        log("Requested user enables Bluetooth. Try starting the scan again.");
    }

    @TargetApi(23)
    private boolean hasLocationPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    @TargetApi(23)
    private void requestLocationPermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
        log("Requested user enable Location. Try starting the scan again.");
    }

    // Gatt connection

    private void connectDevice(BluetoothDevice device)
    {
        if( device.getName() != null)
        {
            log("Connected to device " + device.getAddress() + " Name: " + device.getName());
        }
        else
        {
            log("Connecting to " + device.getAddress());
        }
        GattClientCallback gattClientCallback = new GattClientCallback();
        mGatt = device.connectGatt(this, false, gattClientCallback);
        mBinding.serverListContainer.removeAllViews();
    }

    // Logging

    private void clearLogs() {
        mLogHandler.post(() -> mBinding.viewClientLog.logTextView.setText(""));
    }

    // Gat Client Actions

    public void log(String msg) {
        Log.d(TAG, msg);
        mLogHandler.post(() -> {
            mBinding.viewClientLog.logTextView.append(msg + "\n");
            mBinding.viewClientLog.logScrollView.post(() -> mBinding.viewClientLog.logScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    public void logError(String msg) {
        log("Error: " + msg);
    }

    public void setConnected(boolean connected)
    {
        mConnected = connected;
    }

    public void disconnectGattServer()
    {
        log("Closing Gatt connection");
        clearLogs();
        mConnected = false;
        if (mGatt != null) {
            mGatt.disconnect();
            mGatt.close();
        }
    }

    // Callbacks
    @TargetApi(23)
    private class BtleScanCallback extends ScanCallback {

        private Map<String, BluetoothDevice> mScanResults;

        BtleScanCallback(Map<String, BluetoothDevice> scanResults) {
            mScanResults = scanResults;
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            addScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                addScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            logError("BLE Scan Failed with code " + errorCode);
        }

        private void addScanResult(ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            mScanResults.put(deviceAddress, device);
        }
    }

    private class GattClientCallback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);

            if (status == BluetoothGatt.GATT_FAILURE) {
                logError("Connection Gatt failure status " + status);
                disconnectGattServer();
                return;
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                // handle anything not SUCCESS as failure
                logError("Connection not GATT sucess status " + status);
                disconnectGattServer();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if( gatt.getDevice().getName() != null)
                {
                    log("Connected to device " + gatt.getDevice().getAddress() + " Name: " + gatt.getDevice().getName());
                }
                else
                {
                    log("Connected to device " + gatt.getDevice().getAddress());
                }
                setConnected(true);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected from device");
                disconnectGattServer();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Device service discovery unsuccessful, status " + status);
                return;
            }
            List<BluetoothGattService> BTServices = gatt.getServices();
            log("Initializing services");

            if(BTServices.isEmpty()) {
                logError("Unable to find services.");
                return;
            }


            for(BluetoothGattService BTservice : BTServices)
            {
                /*if(BTservice.getUuid().toString().equalsIgnoreCase(IRService))
                {
                    for(int i = 0; i < BTservice.getCharacteristics().size(); i++)
                    {
                        if(BTservice.getCharacteristics().get(i).getUuid().toString().equalsIgnoreCase(IRConfigUUID))
                        {
                            log("Device IR config ");
                            IRConfig = BTservice.getCharacteristics().get(i);
                        }
                        if ((BTservice.getCharacteristics().get(i).getUuid().toString().equalsIgnoreCase(IRDataUUID)))
                        {
                            log("Device IR data ");
                            IRData = BTservice.getCharacteristics().get(i);
                        }
                    }
                    gatt.setCharacteristicNotification(IRData, true);
                    BluetoothGattDescriptor descriptor = IRData.getDescriptor(DescriptorUUid);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);
                }
                else*/ if(BTservice.getUuid().toString().equalsIgnoreCase(HMService))
                {
                    for(int i = 0; i < BTservice.getCharacteristics().size(); i++)
                    {
                        if(BTservice.getCharacteristics().get(i).getUuid().toString().equalsIgnoreCase(HMConfigUUID))
                        {
                            log("Device HM config ");
                            HMConfig = BTservice.getCharacteristics().get(i);
                        }
                        if ((BTservice.getCharacteristics().get(i).getUuid().toString().equalsIgnoreCase(HMDataUUID)))
                        {
                            log("Device HM data ");
                            HMData = BTservice.getCharacteristics().get(i);
                        }
                    }
                    gatt.setCharacteristicNotification(HMData, true);
                    BluetoothGattDescriptor descriptor = HMData.getDescriptor(DescriptorUUid);
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(descriptor);

                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            //if(descriptor.getCharacteristic().getUuid().toString().equalsIgnoreCase(IRDataUUID))
           // {
                //ConfigByte = new byte[1];
                //ConfigByte[0] = 0x01;
                //IRConfig.setValue(ConfigByte);
                //gatt.writeCharacteristic(IRConfig);
            //}
           // else  if(descriptor.getCharacteristic().getUuid().toString().equalsIgnoreCase(HMDataUUID))
            //{
                ConfigByte = new byte[1];
                ConfigByte[0] = 0x01;
                HMConfig.setValue(ConfigByte);
                gatt.writeCharacteristic(HMConfig);
           // }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            /*if(characteristic.getUuid().toString().equalsIgnoreCase(IRDataUUID))
            {
                processIRData(characteristic.getValue());
            }
            else*/ if(characteristic.getUuid().toString().equalsIgnoreCase(HMDataUUID))
            {
                processHMData(characteristic.getValue());
            }
        }

    }

    public void processIRData(byte[] a)
    {
        DecimalFormat df = new DecimalFormat("0.00");
        float object = (float)shortUnsignedAtOffset(a, 0)/128;
        float ambient = (float)shortUnsignedAtOffset(a, 2)/128;
        log("IR Sensor: Obj: " + df.format(object) +" Amb: " + df.format(ambient) );
    }
    public void processHMData(byte[] a)
    {
        DecimalFormat df = new DecimalFormat("0.00");
        float temp = ((float)shortUnsignedAtOffset(a, 0)/65536)*165 - 40;
        float hum = ((float)shortUnsignedAtOffset(a, 2)/65536)*100;
        log("HM Sensor: Temp: " + df.format(temp) +" Hum: " + df.format(hum) );
    }
    private static Integer shortUnsignedAtOffset(byte[] c, int offset) {
        Integer lowerByte = (int) c[offset] & 0xFF;
        Integer upperByte = (int) c[offset+1] & 0xFF;
        return (upperByte << 8) + lowerByte;
    }
}
