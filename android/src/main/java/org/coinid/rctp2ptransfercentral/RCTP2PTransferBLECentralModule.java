package org.coinid.rctp2ptransfercentral;

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

/* Advertising (Peripheral) */
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;

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
  private BluetoothGattService mGattService;
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

  private BluetoothGattCharacteristic mCurrentSendingToCharacteristic;

  private int mFinalSendingBytes;
  private Callback mFinishedWritingCB;
  private int mChunkCount;

  private Callback mDidDisconnectPeripheralCB;

  private int mSentBytes;
  private int MAX_MTU = 512;


  public RCTP2PTransferBLECentralModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.mContext = reactContext;
    this.init();
  }

  public Integer init() {
    if (null == this.mManager) {
      this.mManager = (BluetoothManager) this.mContext.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    if (null == this.mManager) {
      return -1;
    }

    if (false == this.mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      return -2;
    }

    if (null == this.mAdapter) {
      this.mAdapter = this.mManager.getAdapter();
    }

    if (null == this.mAdapter) {
      return -3;
    }

    if (null == this.mLeScanner) {
      this.mLeScanner = this.mAdapter.getBluetoothLeScanner();
    }

    if (null == this.mLeScanner) {
      return -4;
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

    this.mLeScanner.startScan(filters, settings, mLeScanCallback);
  }

  @ReactMethod
  public void stopScan() {
    this.mLeScanner.stopScan(mLeScanCallback);
  }


  @ReactMethod
  public void connect(String peripheralUUID, Callback callback) {
    Log.d("connect", peripheralUUID);
    if (this.mGatt == null) {
      Log.d("connect", "trying to connect");
      this.mDidConnectPeripheralCB = callback;
      this.mGatt = this.mPeripheral.connectGatt(this.mContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);

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

    BluetoothGattCharacteristic characteristic = mGattService.getCharacteristic(UUID.fromString(characteristicUUID));

    if(characteristic == null) {
      return ;
    }

    sendValueInChunks(value.getBytes(Charset.forName("UTF-8")), characteristic, callback);
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

  void sendValueInChunks(byte[] value, BluetoothGattCharacteristic characteristic, Callback callback) {
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
        this.sendNextChunk(characteristic);
        return ;
      }
    }
  }

  void sendNextChunk(BluetoothGattCharacteristic characteristic) {
    if(this.mQueuedSendChunks.size() == 0) {
      // done sending...
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
  }

/*
- (void)sendValueInChunks:(NSData *)data forCharacteristic:(CBMutableCharacteristic *)forCharacteristic callback:(nonnull RCTResponseSenderBlock)callback{
  UInt32 size = [data length];
  NSData *startPayload = [NSData dataWithBytes:&size length:sizeof(size)];
  NSInteger chunkSize = 20; // [_connectedPeripheral maximumWriteValueLengthForType: CBCharacteristicWriteWithResponse]; <-- fails, too large? keeping it safe with 20; might need to do a manual negotiation for mtu size.

  _finalSendingBytes = [data length];
  
  [_callbacks setObject:callback forKey:@"finishedWritingCB"];

  _chunkCount = 0;
  _chunkCountTarget = (data.length + chunkSize - 1) / chunkSize; // rounds up

  for(NSInteger i = 0; 1; i++) {
    NSData *chunk = i ? [self getDataChunk:data size:chunkSize num:i-1] : startPayload;
    
    if(chunk != nil && [chunk length]) {  
      [_connectedPeripheral writeValue:chunk
        forCharacteristic:forCharacteristic
        type:CBCharacteristicWriteWithResponse
      ];
    }
    else {
      return ;
    }
  }
}
*/

  private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
      Log.i("onConnectionStateChange", "Status: " + status);
      switch (newState) {
        case BluetoothProfile.STATE_CONNECTED:
          Log.i("gattCallback", "STATE_CONNECTED");
          gatt.requestMtu(MAX_MTU);
        break;
        case BluetoothProfile.STATE_DISCONNECTED:
          Log.e("gattCallback", "STATE_DISCONNECTED");
          closeGatt();
        break;
        default:
          Log.e("gattCallback", "STATE_OTHER");
      }
    }

    @Override
    public void onServicesDiscovered (BluetoothGatt gatt, int status) {
      BluetoothGattService service = gatt.getService(UUID.fromString(mDiscoverServiceUUID));

      if(service != null) {
        mGattService = service;
        mDiscoverServicesCB.invoke(service.getUuid().toString());
      }
    }

    @Override
    public void onCharacteristicChanged (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
      String peripheralUUID = gatt.getDevice().toString();
      String serviceUUID = characteristic.getService().getUuid().toString();
      String characteristicUUID = characteristic.getUuid().toString();
      byte[] value = characteristic.getValue();

      if(mReceiveCharacteristicUUID == null || !mReceiveCharacteristicUUID.equals(characteristicUUID) ) {
        Log.e("onCharacteristicChanged", "receive characteristic not matching");
        return ;
      }

      if(mFinalBytes == 0) {
        mFinalBytes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
        mReceivedData = ByteBuffer.allocate(mFinalBytes);

        WritableMap startedRetObject = Arguments.createMap();
        startedRetObject.putString("peripheralUUID", peripheralUUID);
        startedRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
        startedRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
        startedRetObject.putInt("mFinalBytes", mFinalBytes);

        sendEvent("transferStarted", startedRetObject);
      }
      else {
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

      if(mReceivedBytes == mFinalBytes) {
        String stringFromData = new String( mReceivedData.array(), Charset.forName("UTF-8") );

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
    public void onDescriptorWrite (BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
      Log.d("onDescriptorWrite", "test");

      if(descriptor.getUuid().equals(DESCRIPTOR_CONFIG_UUID)) {

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
    public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      Log.d("onCharacteristicWrite", Integer.toString(characteristic.getValue().length));

      // if wrote to current active sending char.
      if( mCurrentSendingToCharacteristic.getUuid().toString().equals(characteristic.getUuid().toString()) ) {
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
          sendNextChunk(characteristic);
        }
      }
      else {
        Log.e("onCharacteristicWrite", mCurrentSendingToCharacteristic.getUuid().toString()+" != "+characteristic.getUuid().toString());
      }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
      Log.d("onMtuChanged", Integer.toString(mtu));

      if(status == BluetoothGatt.GATT_SUCCESS){
        super.onMtuChanged(gatt, mtu, status);

        WritableMap params = Arguments.createMap();
        params.putInt("mtu", mtu);
        sendEvent("onMtuChanged", params);

        mConnectionMtu = mtu;
      }
      else {
        Log.e("onMtuChanged", "changine mtu failed...");
      }


      if(mDidConnectPeripheralCB != null) {
        mDidConnectPeripheralCB.invoke(gatt.getDevice().toString());
        mDidConnectPeripheralCB = null;
      }
    }

/*
- (void)peripheral:(CBPeripheral *)peripheral didWriteValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  _chunkCount++;

  NSMutableDictionary *retObject = [NSMutableDictionary new];
  
  retObject[@"peripheralUUID"] = peripheral.identifier.UUIDString;
  retObject[@"serviceUUID"] = characteristic.service.UUID.UUIDString;
  retObject[@"characteristicUUID"] = characteristic.UUID.UUIDString;

  float progress = ((float)_chunkCount-1)/((float)_chunkCountTarget);
  int estimatesBytes = progress*((float)_finalSendingBytes);
    
  retObject[@"receivedBytes"] = [[NSNumber alloc] initWithUnsignedInteger:estimatesBytes];
  retObject[@"finalBytes"] = [[NSNumber alloc] initWithUnsignedInteger:_finalSendingBytes];

  [self sendEventWithName:@"didWriteValueForCharacteristic" body:retObject];
  
  if(_chunkCount == _chunkCountTarget) {
    RCTResponseSenderBlock callback = [_callbacks objectForKey:@"finishedWritingCB"];

    if (callback) {
      [_callbacks removeObjectForKey:@"finishedWritingCB"];
      callback(@[retObject]);
    }
  }
}
*/
  };


  private ScanCallback mLeScanCallback = new ScanCallback() {
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


/*
  @ReactMethod
  void unpublishService(String serviceUUID, Callback callback) {
    BluetoothGattService previousService = this.mGattService;

    if(null != previousService) {     
      this.mGattServer.removeService(previousService);
      callback.invoke(true);
      return ;
    }

    callback.invoke(false);
  }

  @ReactMethod
  void startAdvertising(String name, final Callback callback) {
    this.mAdapter.setName(name);

    AdvertiseSettings settings = (new AdvertiseSettings.Builder())
      .setConnectable(true)
      .setAdvertiseMode( AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY )
      .setTxPowerLevel( AdvertiseSettings.ADVERTISE_TX_POWER_HIGH )
      .build();

    ParcelUuid uuid = new ParcelUuid(this.mGattService.getUuid());

    AdvertiseData data = (new AdvertiseData.Builder())
      .setIncludeDeviceName(true)
      .addServiceUuid(uuid)
      .build();

    this.mLeAdvertiser.startAdvertising(settings, data, this.mAdvertiseCallback);
    callback.invoke(true);
  }

  @ReactMethod
  void stopAdvertising(Callback callback) {
    this.mLeAdvertiser.stopAdvertising(this.mAdvertiseCallback);
    callback.invoke(true);
  }


  @ReactMethod
  void addService(String serviceUUID, Callback callback) {
    serviceUUID = getUUIDStringFromSimple(serviceUUID);

    BluetoothGattService gattService = new BluetoothGattService(
      UUID.fromString(serviceUUID),
      BluetoothGattService.SERVICE_TYPE_PRIMARY
    );

    this.mGattService = gattService;

    callback.invoke(serviceUUID);
  }

  @ReactMethod
  void addCharacteristic(String serviceUUID, String characteristicUUID, Callback callback) {
    characteristicUUID = getUUIDStringFromSimple(characteristicUUID);

    BluetoothGattCharacteristic gattCharacteristic = new BluetoothGattCharacteristic(
      UUID.fromString(characteristicUUID),
      (BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_NOTIFY),
      (BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE)
    );

    // In order to support subsriptions this descriptor is needed
    BluetoothGattDescriptor gattCharacteristicConfig = new BluetoothGattDescriptor(DESCRIPTOR_CONFIG_UUID, (BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE));
    gattCharacteristic.addDescriptor(gattCharacteristicConfig);

    this.mGattCharacteristic = gattCharacteristic;

    this.mGattService.addCharacteristic(gattCharacteristic);
    callback.invoke(characteristicUUID);
  }


  @ReactMethod
  void publishService(String serviceUUID, Callback callback) {
    this.mGattServer.addService(this.mGattService);
    callback.invoke(serviceUUID);
  }

  private final AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
      super.onStartSuccess(settingsInEffect);
    }
 
    @Override
    public void onStartFailure(int errorCode) {
      super.onStartFailure(errorCode);
    }
  };

  private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
      Log.d("GattServer", "Our gatt server connection state changed, new state ");
      Log.d("GattServer", Integer.toString(newState));
      super.onConnectionStateChange(device, status, newState);

      WritableMap params = Arguments.createMap();
      params.putString("name", "dooley-doo");
      sendEvent("onConnectionStateChange", params);
    }

    @Override
    public void onServiceAdded(int status, BluetoothGattService service) {
      Log.d("GattServer", "Our gatt server service was added.");
      super.onServiceAdded(status, service);

      WritableMap params = Arguments.createMap();
      params.putString("name", "dooley-doo");
      sendEvent("onServiceAdded", params);
    }

    @Override
    public void onMtuChanged(BluetoothDevice device, int mtu) {
      super.onMtuChanged(device, mtu);

      WritableMap params = Arguments.createMap();
      params.putInt("mtu", mtu);
      sendEvent("onMtuChanged", params);

      mConnectionMtu = mtu;
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
      Log.d("GattServer", "Our gatt characteristic was read.");
      super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
      mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());

      WritableMap params = Arguments.createMap();
      params.putString("name", "dooley-doo");
      sendEvent("onCharacteristicReadRequest", params);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
      super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);

      WritableMap params = Arguments.createMap();
      params.putString("characteristic", mSendCharacteristicUUID);
      sendEvent("onCharacteristicWriteRequest", params);

      // if write is made on sendcharacteristic (= means it is a confirmation that the send is completed)
      if(mSendCharacteristicUUID != null && mSendCharacteristicUUID.equals(characteristic.getUuid().toString()) ) {
        if(mDidFinishSendCB != null) {
          mDidFinishSendCB.invoke();
          mDidFinishSendCB = null;
        }
      }

      // if write is made on receivecharacteristic (= means it is a write we are waiting for)
      if(mReceiveCharacteristicUUID != null && mReceiveCharacteristicUUID.equals(characteristic.getUuid().toString()) ) {
        String centralUUID = device.toString();
        String serviceUUID = characteristic.getService().getUuid().toString();
        String characteristicUUID = characteristic.getUuid().toString();

        if(mFinalBytes == 0) {
          mFinalBytes = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();

          WritableMap retObject = Arguments.createMap();
          retObject.putString("centralUUID", centralUUID);
          retObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
          retObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
          retObject.putInt("mFinalBytes", mFinalBytes);

          sendEvent("transferStarted", retObject);

          mReceivedData = ByteBuffer.allocate(mFinalBytes);
        }
        else {
          mReceivedData.put(value);
          mReceivedBytes += value.length;
        }

        WritableMap progressRetObject = Arguments.createMap();
        progressRetObject.putString("centralUUID", centralUUID);
        progressRetObject.putString("serviceUUID", removeBaseUUID(serviceUUID));
        progressRetObject.putString("characteristicUUID", removeBaseUUID(characteristicUUID));
        progressRetObject.putInt("receivedBytes", mReceivedBytes);
        progressRetObject.putInt("finalBytes", mFinalBytes);

        sendEvent("transferProgress", progressRetObject);

        if(mReceivedBytes == mFinalBytes) {
          String stringFromData = new String( mReceivedData.array(), Charset.forName("UTF-8") );

          WritableMap doneRetObject = Arguments.createMap();
          doneRetObject.putString("centralUUID", centralUUID);
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

      if (responseNeeded) {
        mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
      }
    }

    @Override
    public void onNotificationSent(BluetoothDevice device, int status) {
      Log.d("GattServer", "onNotificationSent");
      super.onNotificationSent(device, status);

      WritableMap params = Arguments.createMap();
      params.putString("name", "dooley-doo");
      sendEvent("onNotificationSent", params);
    }

    @Override
    public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
      Log.d("GattServer", "Our gatt server descriptor was read.");
      super.onDescriptorReadRequest(device, requestId, offset, descriptor);

      WritableMap params = Arguments.createMap();
      params.putString("name", "dooley-doo");
      sendEvent("onDescriptorReadRequest", params);
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
      super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value);

      WritableMap params = Arguments.createMap();
      params.putString("configuuid", DESCRIPTOR_CONFIG_UUID.toString());
      params.putString("descriptor", descriptor.getUuid().toString());

      params.putString("enable notification", BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.toString());
      params.putString("enable indication", BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.toString());
      params.putString("disable notification", BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.toString());
      params.putString("value", value.toString());
      sendEvent("onDescriptorWriteRequest", params);

      if(descriptor.getUuid().equals(DESCRIPTOR_CONFIG_UUID)) {

        String centralUUID = device.toString();
        String serviceUUID = descriptor.getCharacteristic().getService().getUuid().toString();
        String characteristicUUID = descriptor.getCharacteristic().getUuid().toString();

        WritableMap subscriber = Arguments.createMap();
        subscriber.putString("centralUUID", centralUUID);
        subscriber.putString("serviceUUID", removeBaseUUID(serviceUUID));
        subscriber.putString("characteristicUUID", removeBaseUUID(characteristicUUID));

        if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
          mCentral = device;
          sendEvent("didSubscribeToCharacteristic", subscriber);
        }

        if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
          sendEvent("didUnsubscribeFromCharacteristic", subscriber);
        }

        if (responseNeeded) {
          mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
        }
      } else {

        if (responseNeeded) {
          mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
        }
      }

    }

    @Override
    public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
      Log.d("GattServer", "Our gatt server on execute write.");
      super.onExecuteWrite(device, requestId, execute);

      WritableMap params = Arguments.createMap();
      params.putString("name", "dooley-doo");
      sendEvent("onExecuteWrite", params);
    }

  };
  */

  


}