package com.example.myapplication;
import android.bluetooth.BluetoothDevice;
import android.databinding.BaseObservable;
import android.databinding.Bindable;

public class GattViewModel extends BaseObservable{
    private BluetoothDevice BTDevice;

    private BluetoothDevice mBluetoothDevice;

    public GattViewModel(BluetoothDevice bluetoothDevice) {
        mBluetoothDevice = bluetoothDevice;
    }

    @Bindable
    public String getServerName() {
        if (mBluetoothDevice == null) {
            return "";
        }

        if(mBluetoothDevice.getName() != null)
        {
            return mBluetoothDevice.getName();
        }
        else
        {
            return mBluetoothDevice.getAddress();
        }
    }
}
