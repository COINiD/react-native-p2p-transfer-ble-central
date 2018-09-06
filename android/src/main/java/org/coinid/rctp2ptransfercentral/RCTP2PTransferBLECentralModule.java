package org.coinid.rctp2ptransfercentral;

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
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;

/* Scanning (Central) */
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

/* React Native */
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;

import android.os.Build;
import android.support.annotation.Nullable;
import android.os.ParcelUuid;

import android.util.Log;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

import java.lang.Thread;

public class RCTP2PTransferBLECentralModule extends ReactContextBaseJavaModule {

  private static final int REQUEST_ENABLE_BT = 1;

  private ReactApplicationContext mContext;

  private BluetoothManager mManager;
  private BluetoothAdapter mAdapter;
  private BluetoothLeScanner mLeScanner;
  private BluetoothGatt mGatt;
  public BluetoothGattService mGattService;
  private BluetoothGattCharacteristic mGattCharacteristic;
  private BluetoothDevice mPeripheral;

  private String mSendCharacteristicUUID;
  private String mReceiveCharacteristicUUID;
  private int mConnectionMtu;

  private Callback mDidConnectPeripheralCB;

  private boolean mScanning;
  private Callback mFoundPeripheralCallback;

  private Callback mDidFinishSendCB;

  private UUID DESCRIPTOR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private int mFinalBytes;
  private int mReceivedBytes;
  private ByteBuffer mReceivedData;


  private Callback mDiscoverServicesCB;
  private String mDiscoverServiceUUID;

  private Callback mSubscribeToCharacteristicCB;
  private Callback mUnSubscribeToCharacteristicCB;

  public BluetoothGattCharacteristic mCurrentSendingToCharacteristic;

  private int mFinalSendingBytes;
  private Callback mFinishedWritingCB;
  private int mChunkCount;

  private Callback mDidDisconnectPeripheralCB;

  private int mSentBytes;
  private int MAX_MTU = 512;

  private BluetoothGattCallback mGattCallback;
  private ScanCallback mLeScanCallback;


  public RCTP2PTransferBLECentralModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.mContext = reactContext;

    if(this.checkSupport() == 0) {
      this.setupBLECallbacks();
    }
  };

  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  private void setupBLECallbacks() {
    this.mLeScanCallback = new ScanCallback() {
      @Override
      public void onScanResult(int callbackType, ScanResult result) {
        Log.i("callbackType", String.valueOf(callbackType));
        Log.i("result", result.toString());

        BluetoothDevice device = result.getDevice();
        mPeripheral = device;
        String peripheralUUID = device.toString();

        WritableMap retObject = Arguments.createMap();
        retObject.putString("peripheralUUID", peripheralUUID);

        if(mFoundPeripheralCallback != null) {
          mFoundPeripheralCallback.invoke(retObject);
          mFoundPeripheralCallback = null;
        }
      }

      @Override
      public void onBatchScanResults(List<ScanResult> results) {
        ScanResult result = results.get(0);
        Log.i("ScanResult - Results", result.toString());

        BluetoothDevice device = result.getDevice();
        mPeripheral = device;
        String peripheralUUID = device.toString();

        WritableMap retObject = Arguments.createMap();
        retObject.putString("peripheralUUID", peripheralUUID);

        if(mFoundPeripheralCallback != null) {
          mFoundPeripheralCallback.invoke(retObject);
          mFoundPeripheralCallback = null;
        }
      }

      @Override
      public void onScanFailed(int errorCode) {
        Log.e("Scan Failed", "Error Code: " + errorCode);
      }
    };

    this.mGattCallback = new BluetoothGattCallback() {
      @Override
      public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        Log.i("onConnectionStateChange", "Status: " + status);
        switch (newState) {
          case BluetoothProfile.STATE_CONNECTED:
            Log.i("mGattCallback", "STATE_CONNECTED");
            gatt.requestMtu(MAX_MTU);
          break;
          case BluetoothProfile.STATE_DISCONNECTED:
            Log.e("mGattCallback", "STATE_DISCONNECTED");
            closeGatt();
          break;
          default:
            Log.e("mGattCallback", "STATE_OTHER");
        }
      }


      @Override
      public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        BluetoothGattService service = gatt.getService(UUID.fromString(mDiscoverServiceUUID));

        if (service != null) {
          mGattService = service;
          mDiscoverServicesCB.invoke(service.getUuid().toString());
        }
      }

      @Override
      public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        Log.d("onDescriptorWrite", "test");

        if (descriptor.getUuid().equals(DESCRIPTOR_CONFIG_UUID)) {

          BluetoothDevice device = gatt.getDevice();
          String peripheralUUID = device.toString();
          String serviceUUID = descriptor.getCharacteristic().getService().getUuid().toString();
          String characteristicUUID = descriptor.getCharacteristic().getUuid().toString();
          byte[] value = descriptor.getValue();

          WritableMap subscriber = Arguments.createMap();
          subscriber.putString("peripheralUUID", peripheralUUID);
          subscriber.putString("serviceUUID", removeBaseUUID(serviceUUID));
          subscriber.putString("characteristicUUID", removeBaseUUID(characteristicUUID));

          Log.d("onDescriptorWrite", "test2");
          if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
            mPeripheral = device;
            mSubscribeToCharacteristicCB.invoke(subscriber);
            Log.d("onDescriptorWrite", "test3");
          }

          if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
            mUnSubscribeToCharacteristicCB.invoke(subscriber);
          }
        }
      }

      @Override
      public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        Log.d("onMtuChanged", Integer.toString(mtu));

        if (status == BluetoothGatt.GATT_SUCCESS) {
          super.onMtuChanged(gatt, mtu, status);

          WritableMap params = Arguments.createMap();
          params.putInt("mtu", mtu);
          sendEvent("onMtuChanged", params);

          mConnectionMtu = mtu;
        } else {
          Log.e("onMtuChanged", "changine mtu failed...");
        }

        if (mDidConnectPeripheralCB != null) {
          mDidConnectPeripheralCB.invoke(gatt.getDevice().toString());
          mDidConnectPeripheralCB = null;
        }
      }

      @Override
      public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        String peripheralUUID = gatt.getDevice().toString();
        String serviceUUID = characteristic.getService().getUuid().toString();
        String characteristicUUID = characteristic.getUuid().toString();
        byte[] value = characteristic.getValue();

        if (mReceiveCharacteristicUUID == null || !mReceiveCharacteristicUUID.equals(characteristicUUID)) {
          Log.e("onCharacteristicChanged", "receive characteristic not matching");
          return;
        }

        if (mFinalBytes == 0) {
          mFinalBytes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
          mReceivedData = ByteBuffer.allocate(mFinalBytes);

          WritableMap startedRetObject = Arguments.createMap();
          startedRetObject.putString("peripheralUUID", peripheralUUID);
          startedRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
          startedRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
          startedRetObject.putInt("mFinalBytes", mFinalBytes);

          sendEvent("transferStarted", startedRetObject);
        } else {
          mReceivedData.put(value);
          mReceivedBytes += value.length;
        }

        WritableMap progressRetObject = Arguments.createMap();
        progressRetObject.putString("peripheralUUID", peripheralUUID);
        progressRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
        progressRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
        progressRetObject.putInt("receivedBytes", mReceivedBytes);
        progressRetObject.putInt("finalBytes", mFinalBytes);

        sendEvent("transferProgress", progressRetObject);

        if (mReceivedBytes == mFinalBytes) {
          String stringFromData = new String(mReceivedData.array(), Charset.forName("UTF-8"));

          WritableMap doneRetObject = Arguments.createMap();
          doneRetObject.putString("peripheralUUID", peripheralUUID);
          doneRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
          doneRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
          doneRetObject.putInt("receivedBytes", mReceivedBytes);
          doneRetObject.putInt("finalBytes", mFinalBytes);
          doneRetObject.putString("value", stringFromData);

          sendEvent("transferDone", doneRetObject);

          mFinalBytes = 0;
          mReceivedData = null;
          mReceivedBytes = 0;
        }
      }

      @Override
      public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        Log.d("onCharacteristicWrite", Integer.toString(characteristic.getValue().length));

        // if wrote to current active sending char.
        if( mCurrentSendingToCharacteristic.getUuid().toString().equals(characteristic.getUuid().toString() ) ) {
          
          if(mChunkCount > 0) {
            // first chunk is startpayload..
            mSentBytes += characteristic.getValue().length;
          }

          mChunkCount++;

          String peripheralUUID = gatt.getDevice().toString();
          String serviceUUID = characteristic.getService().getUuid().toString();
          String characteristicUUID = characteristic.getUuid().toString();

          WritableMap retObject = Arguments.createMap();

          retObject.putString("peripheralUUID", peripheralUUID);
          retObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
          retObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
          retObject.putInt("receivedBytes", mSentBytes);
          retObject.putInt("finalBytes", mFinalSendingBytes);

          sendEvent("didWriteValueForCharacteristic", retObject);

          if(mSentBytes == mFinalSendingBytes) {
            Callback callback = mFinishedWritingCB;

            if (callback != null) {
              mFinishedWritingCB = null;
              mCurrentSendingToCharacteristic = null;

              WritableMap finishedRetObject = Arguments.createMap();
              finishedRetObject.putString("peripheralUUID", peripheralUUID);
              finishedRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
              finishedRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
              finishedRetObject.putInt("receivedBytes", mSentBytes);
              finishedRetObject.putInt("finalBytes", mFinalSendingBytes);

              callback.invoke(finishedRetObject);
            }
          }
          else {
            sendNextChunk(characteristicUUID);
          }
          
        }
        else {
          Log.e("onCharacteristicWrite", mCurrentSendingToCharacteristic.getUuid().toString()+" != "+characteristic.getUuid().toString());
        }
      }
    };
  }

  public Integer checkSupport() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return -1;
    }

    if (null == this.mContext.getSystemService(Context.BLUETOOTH_SERVICE)) {
      return -2;
    }

    if (false == this.mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      return -3;
    }

    return 0;
  }

  public Integer setupModule() {
    if(this.checkSupport() != 0) {
      return -1;
    }

    if (null == this.mManager) {
      this.mManager = (BluetoothManager) this.mContext.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    if (null == this.mAdapter) {
      this.mAdapter = this.mManager.getAdapter();
    }

    if (null == this.mAdapter) {
      return -2;
    }

    if (null == this.mLeScanner) {
      this.mLeScanner = this.mAdapter.getBluetoothLeScanner();
    }

    if (null == this.mLeScanner) {
      return -3;
    }

    if(!this.mAdapter.isEnabled()) {
      return -4;
    }

    return 0;
  }

  public Integer init() {
    if(this.setupModule() != 0) {
      return -1;
    }

    this.mConnectionMtu = 23;
    this.mFinalBytes = 0;
    this.mReceivedData = null;
    this.mReceivedBytes = 0;

    return 0;
  }

  @Override
  public String getName() {
    return "P2PTransferBLECentralModule";
  }

  @ReactMethod
  public void isSupported(Callback callback) {
    if(this.checkSupport() != 0) {
      callback.invoke(false);
      return;
    }
    callback.invoke(true);
  }

  @ReactMethod
  public void setSendCharacteristic(String characteristicUUID) {
    this.mSendCharacteristicUUID = getUUIDStringFromSimple(characteristicUUID);
  }

  @ReactMethod
  public void setReceiveCharacteristic(String characteristicUUID) {
    this.mReceiveCharacteristicUUID = getUUIDStringFromSimple(characteristicUUID);
  }

  @ReactMethod
  public void isBluetoothEnabled(final Promise promise) {
    if (null == this.mAdapter || !this.mAdapter.isEnabled()) {
      promise.resolve(false);
      return;
    }
  
    promise.resolve(this.mAdapter.isEnabled());
  }

  @ReactMethod
  public void start(Callback callback) {
    if(this.init() != 0) {
      callback.invoke(false);
      return;
    };

    callback.invoke(true);
  }

  @ReactMethod
  public void scanForPeripheralsWithServices(ReadableMap scanFilter, Callback callback) {
    String serviceUUID = null;

    if(scanFilter != null) {
      serviceUUID = scanFilter.getString("serviceUUID");
    }

    this.mScanning = true;
    this.mFoundPeripheralCallback = callback;

    ScanSettings settings = new ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .build();

    ArrayList<ScanFilter> filters = new ArrayList<ScanFilter>();

    if(serviceUUID != null) {
      serviceUUID = getUUIDStringFromSimple(serviceUUID);

      ParcelUuid uuid = new ParcelUuid(UUID.fromString(serviceUUID));

      ScanFilter filter = new ScanFilter.Builder().
        setServiceUuid(uuid).
        build();

      filters.add(filter);
    }

    this.mLeScanner.stopScan(mLeScanCallback);
    this.mLeScanner.startScan(filters, settings, mLeScanCallback);
  }

  @ReactMethod
  public void stopScan() {
    if (null == this.mLeScanner) {
      return ;
    }

    if (null == this.mAdapter || !this.mAdapter.isEnabled()) {
      return ;
    }

    this.mLeScanner.stopScan(mLeScanCallback);
  }


  @ReactMethod
  public void connect(String peripheralUUID, Callback callback) {
    Log.d("connect", peripheralUUID);
    if (this.mGatt == null) {
      Log.d("connect", "trying to connect");
      this.mDidConnectPeripheralCB = callback;
      this.mGatt = this.mPeripheral.connectGatt(this.mContext, false, this.mGattCallback, BluetoothDevice.TRANSPORT_LE);

      final Callback finalCb = callback;
      final RCTP2PTransferBLECentralModule self = this;

      new android.os.Handler().postDelayed(new Runnable() { public void run() {
        if(self.mDidConnectPeripheralCB == finalCb) {
          self.connectionTimeout();
        }
      } }, 6000);
    }
    else {
      Log.e("connect", "already connected...");
      callback.invoke(this.mGatt.getDevice().toString());
    }
  }

  private void connectionTimeout() {
    Log.e("connectionTimeout", "took to long...");
    if(this.mDidConnectPeripheralCB != null) {
      this.mDidConnectPeripheralCB.invoke();
      this.mDidConnectPeripheralCB = null;
      this.closeGatt();
    }
  }

  @ReactMethod
  public void disconnect(String peripheralUUID, Callback callback) {
    if(this.mGatt != null) {
      this.mDidDisconnectPeripheralCB = callback;

      this.mGatt.disconnect();

      final Callback finalCb = callback;
      final RCTP2PTransferBLECentralModule self = this;

      new android.os.Handler().postDelayed(new Runnable() { public void run() {
        if(self.mDidDisconnectPeripheralCB == finalCb) {
          self.closeGatt();
        }
      } }, 6000);
    }
    else {
      callback.invoke();
    }
  }

  @ReactMethod
  public void discoverServices(String serviceUUID, Callback callback) {
    if (this.mGatt == null) {
      callback.invoke(false);
      return ;
    }

    if(serviceUUID == null) {
      callback.invoke(false);
      return ;
    }

    serviceUUID = getUUIDStringFromSimple(serviceUUID);

    this.mDiscoverServicesCB = callback;
    this.mDiscoverServiceUUID = serviceUUID;

    this.mGatt.discoverServices();

    // timout to reject???
  }

  @ReactMethod
  public void discoverCharacteristics(String serviceUUID, String characteristicUUID, Callback callback) {
    if(mGattService == null) {
      callback.invoke(false);
      return ;
    }

    serviceUUID = getUUIDStringFromSimple(serviceUUID);
    characteristicUUID = getUUIDStringFromSimple(characteristicUUID);

    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(UUID.fromString(characteristicUUID));

    if(characteristic == null) {
      callback.invoke(false);
      return ;
    }

    this.mGattCharacteristic = characteristic;
    callback.invoke(characteristic.getUuid().toString());
  }

  @ReactMethod
  public void subscribeToCharacteristic(String serviceUUID, String characteristicUUID, Callback callback) {
    if(mGattService == null) {
      callback.invoke(false);
      return ;
    }

    serviceUUID = getUUIDStringFromSimple(serviceUUID);
    characteristicUUID = getUUIDStringFromSimple(characteristicUUID);

    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(UUID.fromString(characteristicUUID));

    if(characteristic == null) {
      callback.invoke(false);
      return ;
    }
  
    this.mGattCharacteristic = characteristic;

    // Enable to receive notifications locally
    Boolean setLocalNotification = this.mGatt.setCharacteristicNotification(characteristic, true);

    if(setLocalNotification != true) {
      // error
      callback.invoke(false);
      return ;
    }

    // Let remote device know we are ready to receive notifications.
    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_CONFIG_UUID);
    if(descriptor == null) {
      callback.invoke(false);
      return ;
    }

    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
    Boolean setRemoteNotification = this.mGatt.writeDescriptor(descriptor);

    if(setRemoteNotification != true) {
      // error
      callback.invoke(false);
      return ;
    }

    this.mSubscribeToCharacteristicCB = callback;
  }


  @ReactMethod
  public void unSubscribeToCharacteristic(String serviceUUID, String characteristicUUID, Callback callback) {
    if(mGattService == null) {
      return ;
    }

    serviceUUID = getUUIDStringFromSimple(serviceUUID);
    characteristicUUID = getUUIDStringFromSimple(characteristicUUID);

    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(UUID.fromString(characteristicUUID));

    if(characteristic == null) {
      return ;
    }

    this.mGattCharacteristic = null;

    if(this.mGatt.setCharacteristicNotification(characteristic, false) != true) {
      callback.invoke();
      return ;
    }

    // Let remote device know we are ready to receive notifications.
    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(DESCRIPTOR_CONFIG_UUID);
    descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
    Boolean setRemoteNotification = this.mGatt.writeDescriptor(descriptor);

    if(setRemoteNotification != true) {
      // error
      callback.invoke();
      return ;
    }

    this.mUnSubscribeToCharacteristicCB = callback;
  }

  // Send to peripheral
  @ReactMethod
  void writeValueForCharacteristic(String serviceUUID, String characteristicUUID, String value, Callback callback) {
    if(mGattService == null) {
      return ;
    }

    serviceUUID = getUUIDStringFromSimple(serviceUUID);
    characteristicUUID = getUUIDStringFromSimple(characteristicUUID);

    sendValueInChunks(value.getBytes(Charset.forName("UTF-8")), characteristicUUID, callback);
  }

  void closeGatt() {
    if(this.mGatt != null) {
      this.mGatt.close();
    }

    this.mGatt = null;
    this.mGattService = null;
    this.mGattCharacteristic = null;
    this.mPeripheral = null;

    if(this.mDidDisconnectPeripheralCB != null) {
      this.mDidDisconnectPeripheralCB.invoke();
      this.mDidDisconnectPeripheralCB = null;
    }
  }

  private ArrayList<byte[]> mQueuedSendChunks;

  void sendValueInChunks(byte[] value, String characteristicUUID, Callback callback) {
    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(UUID.fromString(characteristicUUID));

    if(characteristic == null) {
      return ;
    }

    int size = value.length;
    byte[] startPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array();
    int chunkSize = this.mConnectionMtu-3; // 3 bytes is reserved for other data

    this.mFinalSendingBytes = value.length;
    this.mFinishedWritingCB = callback;
    this.mCurrentSendingToCharacteristic = characteristic;

    this.mChunkCount = 0;
    this.mSentBytes = 0;

    this.mQueuedSendChunks = new ArrayList<byte[]>();

    for(int i = 0; true; i++) {
      byte[] chunk = i > 0 ? getDataChunk(value, chunkSize, i-1) : startPayload;

      if(chunk != null && chunk.length > 0) {
        Log.d("sendValueInChunks", Integer.toString(i));
        this.mQueuedSendChunks.add(chunk);
      }
      else {
        this.sendNextChunk(null);
        return ;
      }
    }
  }

  void sendNextChunk(String characteristicUUID) {
    if(this.mQueuedSendChunks.size() == 0) {
      // done sending...
      return ;
    }

    BluetoothGattCharacteristic characteristic;

    if(characteristicUUID == null) {
      characteristic = this.mCurrentSendingToCharacteristic;
    }
    else {
      characteristic = this.mGattService.getCharacteristic(UUID.fromString(characteristicUUID));
    }

    if(characteristic == null) {
      return ;
    }
    
    byte[] chunk = this.mQueuedSendChunks.get(0);

    characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); // requires response
    characteristic.setValue(chunk);
    Boolean isQueued = this.mGatt.writeCharacteristic(characteristic);

    if(isQueued == true) {
      // succeded in queuing write
      this.mQueuedSendChunks.remove(0);
    }
    else {
      // If write queue fails.. sleep for a while and try again
      Log.e("sendNextChunk", "Could not send next chunk...");
      //Thread.sleep(150);
      //this.sendNextChunk(characteristic);
    }
  };

  String getUUIDStringFromSimple(String stringUUID) {
    // base uuid: 0000xxxx-0000-1000-8000-00805F9B34FB
    if(stringUUID.length() == 4) {
      return "0000" + stringUUID + "-0000-1000-8000-00805f9b34fb";
    }
    if(stringUUID.length() == 8) {
      return stringUUID + "-0000-1000-8000-00805f9b34fb";
    }
    return stringUUID;
  }

  String removeBaseUUID(String stringUUID) {
    // base uuid: 0000xxxx-0000-1000-8000-00805F9B34FB
    if(stringUUID.substring(0,4).equals("0000") &&
       stringUUID.substring(8).equals("-0000-1000-8000-00805f9b34fb")) {
      return stringUUID.substring(4,8);
    }
    if(stringUUID.substring(8).equals("-0000-1000-8000-00805f9b34fb")) {
      return stringUUID.substring(0,8);
    }
    return stringUUID;
  }

  byte[] getDataChunk(byte[] value, int size, int num) {
    int start = size * num;

    if(start >= value.length) {
      return null;
    }

    if(start+size > value.length) {
      size = value.length-start;
    }

    return Arrays.copyOfRange(value, start, start+size);
  }

  private void sendEvent( String eventName, @Nullable WritableMap params ) {
    Log.d("sendEvent", eventName);
    
    this.mContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
      .emit(eventName, params);
  }
}