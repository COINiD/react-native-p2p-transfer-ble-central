//
//  RCTP2PTransferBLECentralModule.m
//  RCTP2PTransferBLECentralModule
//
//  Created by Rikard Wissing on 2018-04-01.
//

#import "RCTP2PTransferBLECentralModule.h"
#import <React/RCTBridge.h>
#import <React/RCTConvert.h>
#import <React/RCTEventDispatcher.h>

@implementation RCTP2PTransferBLECentralModule

RCT_EXPORT_MODULE();

- (instancetype)init
{
    if (self = [super init]) {
      NSLog(@"RCTP2PTransferBLECentralModule created");
      _callbacks = [NSMutableDictionary dictionary];
      _isPoweredOn = NO;
      _startedSend = NO;
    }
    
    return self;
}

+(BOOL)requiresMainQueueSetup
{
    return YES;
}

/* Exported Methods */

RCT_EXPORT_METHOD(start:(nonnull RCTResponseSenderBlock)callback)
{
  if(_manager) {
    callback(@[@(_isPoweredOn)]);
    return ;
  }

  [_callbacks setObject:callback forKey:@"startCB"];
  _manager = [[CBCentralManager alloc] initWithDelegate:self queue:dispatch_get_main_queue()];
}

RCT_EXPORT_METHOD(scanForPeripheralsWithServices:(NSDictionary *)filter callback:(nonnull RCTResponseSenderBlock)callback)
{
  NSLog(@"scanForPeripheralsWithServices");
  _scanFilter = filter;
  NSString *serviceUUID;

  if(_scanFilter) {
    serviceUUID = [_scanFilter objectForKey:@"serviceUUID"];
  }

  if(serviceUUID) {
    CBUUID *cbUUID = [CBUUID UUIDWithString:serviceUUID];

    // Otherwise start scan...
    [_callbacks setObject:callback forKey:@"discoverPeripheralCB"];
    [_manager scanForPeripheralsWithServices:@[cbUUID] options:nil];
  }
  else {
    [_callbacks setObject:callback forKey:@"discoverPeripheralCB"];
    [_manager scanForPeripheralsWithServices:nil options:nil];
  }
}

RCT_EXPORT_METHOD(stopScan)
{
    [_manager stopScan];
}

RCT_EXPORT_METHOD(connect:(NSString *)peripheralUUID callback:(nonnull RCTResponseSenderBlock)callback)
{
  if(_connectedPeripheral) {
    callback(@[_connectedPeripheral.identifier.UUIDString]);
    return ;
  }

  if(_connectingPeripheral) {
    callback(@[]);
    return ;
  }

  NSUUID *nsUUID = [[NSUUID UUID] initWithUUIDString:peripheralUUID];

  if(!nsUUID) {
    callback(@[]);
    return ;
  }

  NSArray *peripheralArray = [_manager retrievePeripheralsWithIdentifiers:@[nsUUID]];
  CBPeripheral *peripheral = [peripheralArray firstObject];

  if(!peripheral) {
    callback(@[]);
    return ;
  }

  [_callbacks setObject:callback forKey:@"didConnectPeripheralCB"];

  _connectingPeripheral = peripheral;
  [_manager connectPeripheral:_connectingPeripheral options:nil];
}

RCT_EXPORT_METHOD(disconnect:(NSString *)peripheralUUID callback:(nonnull RCTResponseSenderBlock)callback)
{
  _startedSend = NO;

  if(_connectedPeripheral || _connectingPeripheral) {
    [_callbacks setObject:callback forKey:@"didDisconnectPeripheralCB"];

    if(_connectedPeripheral) {
      [_manager cancelPeripheralConnection:_connectedPeripheral];
    }

    if(_connectingPeripheral) {
      [_manager cancelPeripheralConnection:_connectingPeripheral];
    }
  }
  else {
    callback(@[]);
  }
}

RCT_EXPORT_METHOD(discoverServices:(NSString *)serviceUUID callback:(RCTResponseSenderBlock)callback) {
  if(!_connectedPeripheral) {
    return ;
  }

  if(serviceUUID) { 
    CBUUID *cbUUID = [CBUUID UUIDWithString:serviceUUID];

    [_callbacks setObject:callback forKey:@"discoverServicesCB"];
    [_connectedPeripheral discoverServices:@[cbUUID] ];
  }
}

RCT_EXPORT_METHOD(discoverCharacteristics:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID callback:(RCTResponseSenderBlock)callback) {
  if(!_connectedPeripheral) {
    return ;
  }

  NSLog(@"discoverCharacteristics");

  if(characteristicUUID) {
    CBUUID *cbUUID = [CBUUID UUIDWithString:characteristicUUID];
      NSLog(@"_connectedPeripheral discoverCharacteristics 0");

    for (CBService *service in _connectedPeripheral.services) {
        NSLog(@"_connectedPeripheral discoverCharacteristics 1");
      if([serviceUUID isEqualToString: service.UUID.UUIDString]) {
        [_callbacks setObject:callback forKey:@"discoverCharacteristicsCB"];
        
        NSLog(@"_connectedPeripheral discoverCharacteristics");
        [_connectedPeripheral discoverCharacteristics:@[cbUUID] forService:service];
        return ;
      }
    }
  }
}

RCT_EXPORT_METHOD(writeValueForCharacteristic:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID value:(NSString *)value callback:(RCTResponseSenderBlock)callback)
{
  CBCharacteristic *characteristic = [self findCharacteristic:serviceUUID characteristicUUID:characteristicUUID];
  
  if(characteristic) {
    [self sendValueInChunks:
      [value dataUsingEncoding:NSUTF8StringEncoding]
      forCharacteristic:characteristic
      callback: callback
    ];
  }
}


- (NSData *)getDataChunk:(NSData *)data size:(NSInteger)size num:(NSInteger)num {
  NSInteger start = size * num;

  if(start >= data.length) {
    return nil;
  }

  if(start+size > data.length) {
    size = data.length-start;
  }

  return [data subdataWithRange: NSMakeRange(start, size)];
}

- (void)sendValueInChunks:(NSData *)data forCharacteristic:(CBMutableCharacteristic *)forCharacteristic callback:(nonnull RCTResponseSenderBlock)callback{
  UInt32 size = [data length];
  NSData *startPayload = [NSData dataWithBytes:&size length:sizeof(size)];
  NSInteger chunkSize = 20;

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

RCT_EXPORT_METHOD(readValueForCharacteristic:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID callback:(RCTResponseSenderBlock)callback)
{
  CBCharacteristic *characteristic = [self findCharacteristic:serviceUUID characteristicUUID:characteristicUUID];
  
  if(characteristic) {
    [_connectedPeripheral readValueForCharacteristic:characteristic];
    callback(@[]);
  }
}

RCT_EXPORT_METHOD(subscribeToCharacteristic:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID callback:(RCTResponseSenderBlock)callback)
{
  CBCharacteristic *characteristic = [self findCharacteristic:serviceUUID characteristicUUID:characteristicUUID];
  
  if(characteristic) {
    [_callbacks setObject:callback forKey:@"subscribeToCharacteristicCB"];
    [_connectedPeripheral setNotifyValue:true forCharacteristic:characteristic];
  }
}

RCT_EXPORT_METHOD(unSubscribeToCharacteristic:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID callback:(RCTResponseSenderBlock)callback)
{
  CBCharacteristic *characteristic = [self findCharacteristic:serviceUUID characteristicUUID:characteristicUUID];
  
  if(characteristic) {
    [_callbacks setObject:callback forKey:@"subscribeToCharacteristicCB"];
    [_connectedPeripheral setNotifyValue:false forCharacteristic:characteristic];
  }
  else {
    callback(@[]);
  }
}

 
- (CBCharacteristic *)findCharacteristic:(NSString *)serviceUUID characteristicUUID:(NSString *)characteristicUUID {
  if(_connectedPeripheral) {
    for (CBService *service in _connectedPeripheral.services) {
      if([serviceUUID isEqualToString: service.UUID.UUIDString]) {
        for (CBCharacteristic *characteristic in service.characteristics) {
          if([characteristicUUID isEqualToString: characteristic.UUID.UUIDString]) {
            return characteristic;
          }
        }
      }
    }
  }
  return nil;
} 


/* Events */

- (NSArray<NSString *> *)supportedEvents
{
    return @[
        @"didUpdateState",
        @"didDiscoverPeripheral",
        @"didConnectPeripheral",
        @"didDisconnectPeripheral",
        @"didFailToConnectPeripheral",
        @"didUpdateValueForCharacteristic",
        @"didWriteValueForCharacteristic",
        @"transferProgress",
        @"transferDone",
        @"transferStarted",
    ];
}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central
{
  NSString *state = [self NSStringForCBManagerState:[central state]];

  if([state isEqualToString: @"poweredOn"]) {
    _isPoweredOn = YES;

    RCTResponseSenderBlock callback = [_callbacks objectForKey:@"startCB"];
    if (callback) {
      callback(@[@(_isPoweredOn)]);
      [_callbacks removeObjectForKey:@"startCB"];
    }
  }

  [self sendEventWithName:@"didUpdateState" body:state];
}


- (void) centralManager:(CBCentralManager *)central didDiscoverPeripheral:(CBPeripheral *)peripheral advertisementData:(NSDictionary *)advertisementData RSSI:(NSNumber *)RSSI 
{
  NSLog(@"didDiscoverPeripheral");
  
  /*
  // Reject any where outisde reasonable range
  if (RSSI.integerValue > -15 || RSSI.integerValue < -35) {
    NSLog(@"reject because of RSSI");
    return;
  }
  */

  // Reject if not connectable
  if (advertisementData[CBAdvertisementDataIsConnectable] == nil || ![advertisementData[CBAdvertisementDataIsConnectable] boolValue]) {
    NSLog(@"reject because not connectable");
    return ;
  }
 
  NSString *localName = [_scanFilter objectForKey:@"localName"];
  NSString *serviceUUID = [_scanFilter objectForKey:@"serviceUUID"];

  // Filter on localname
  if (localName) {
    if(advertisementData[CBAdvertisementDataLocalNameKey] == nil || ![localName isEqualToString:advertisementData[CBAdvertisementDataLocalNameKey]]) {
      NSLog(@"reject because localname");
      return ;
    }
  }

  // Filter on serviceUUID
  if (serviceUUID) {
    BOOL found = NO;

    if (advertisementData[CBAdvertisementDataServiceUUIDsKey] != nil) {
      for (CBUUID *uuid in advertisementData[CBAdvertisementDataServiceUUIDsKey]) {
        if ([serviceUUID isEqualToString: uuid.UUIDString]) {
          found = YES;
        }
      }
    }

    if(!found) {
      NSLog(@"reject because serviceUUID");
      return ;
    }
  }

  NSDictionary *returnData = @{
    @"peripheralUUID": peripheral.identifier.UUIDString,
    @"advertisement": [self dictionaryForAdvertisementData:advertisementData fromPeripheral:peripheral],
    @"connectable": @([advertisementData[CBAdvertisementDataIsConnectable] boolValue]),
    @"rssi": RSSI
  };

  [self sendEventWithName:@"didDiscoverPeripheral" body:returnData];

  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"discoverPeripheralCB"];

  if (callback) {
    callback(@[returnData]);
    [_callbacks removeObjectForKey:@"discoverPeripheralCB"];
  }
}

- (void) centralManager:(CBCentralManager *)central didConnectPeripheral:(CBPeripheral *)peripheral 
{  
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"didConnectPeripheralCB"];

  _connectedPeripheral = peripheral;
  _connectingPeripheral = nil;
  _connectedPeripheral.delegate = self;

  [self sendEventWithName:@"didConnectPeripheral" body:peripheral.identifier.UUIDString];

  if(callback) {
    callback(@[peripheral.identifier.UUIDString]);
    [_callbacks removeObjectForKey:@"didConnectPeripheralCB"];
  }
}

- (void) centralManager:(CBCentralManager *)central didDisconnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error
{
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"didDisconnectPeripheralCB"];

  _connectingPeripheral = nil;
  _connectedPeripheral = nil;
  _disconnectedPeripheral = peripheral;

  [self sendEventWithName:@"didDisconnectPeripheral" body:peripheral.identifier.UUIDString];

  if(callback) {
    callback(@[peripheral.identifier.UUIDString]);
    [_callbacks removeObjectForKey:@"didDisconnectPeripheralCB"];
  }
}

- (void) centralManager:(CBCentralManager *)central didFailToConnectPeripheral:(CBPeripheral *)peripheral error:(NSError *)error
{
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"didConnectPeripheralCB"];

  _connectingPeripheral = nil;
  _connectedPeripheral = nil;
  [self sendEventWithName:@"didFailToConnectPeripheral" body:peripheral.identifier.UUIDString];

  if(callback) {
    callback(@[]);
    [_callbacks removeObjectForKey:@"didConnectPeripheralCB"];
  }
}


/* Helpers */

- (NSDictionary *)dictionaryForAdvertisementData:(NSDictionary *)advertisementData fromPeripheral:(CBPeripheral *)peripheral
{
  NSMutableDictionary *advertisement = [NSMutableDictionary new];

  if (advertisementData[CBAdvertisementDataLocalNameKey] != nil) {
    advertisement[@"localName"] = advertisementData[CBAdvertisementDataLocalNameKey];
  }

  if (advertisementData[CBAdvertisementDataManufacturerDataKey] != nil) {
    advertisement[@"manufacturerData"] = [advertisementData[CBAdvertisementDataManufacturerDataKey] base64EncodedStringWithOptions:0];
  }

  if (advertisementData[CBAdvertisementDataServiceDataKey] != nil) {
    advertisement[@"serviceData"] = [NSMutableArray new];
    for (CBUUID *uuid in advertisementData[CBAdvertisementDataServiceDataKey]) {
      [advertisement[@"serviceData"] addObject:@{
        @"uuid": uuid.UUIDString,
        @"data": [advertisementData[CBAdvertisementDataServiceDataKey][uuid] base64EncodedStringWithOptions:0]
      }];
    }
  }

  if (advertisementData[CBAdvertisementDataServiceUUIDsKey] != nil) {
    advertisement[@"serviceUuids"] = [NSMutableArray new];
    for (CBUUID *uuid in advertisementData[CBAdvertisementDataServiceUUIDsKey]) {
      [advertisement[@"serviceUuids"] addObject:uuid.UUIDString];
    }
  }

  if (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] != nil) {
    advertisement[@"overflowServiceUuids"] = [NSMutableArray new];
    for (CBUUID *uuid in advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey]) {
      [advertisement[@"overflowServiceUuids"] addObject:uuid.UUIDString];
    }
  }

  if (advertisementData[CBAdvertisementDataTxPowerLevelKey] != nil) {
    advertisement[@"txPowerLevel"] = advertisementData[CBAdvertisementDataTxPowerLevelKey];
  }

  if (advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey] != nil) {
    advertisement[@"solicitedServiceUuids"] = [NSMutableArray new];
    for (CBUUID *uuid in advertisementData[CBAdvertisementDataSolicitedServiceUUIDsKey]) {
      [advertisement[@"solicitedServiceUuids"] addObject:uuid.UUIDString];
    }
  }

  return advertisement;
}

- (NSString *)NSStringForCBManagerState:(CBManagerState)state
{
  switch (state) {
    case CBManagerStateResetting:
      return @"resetting";
    case CBManagerStateUnsupported:
      return @"unsupported";
    case CBManagerStateUnauthorized:
      return @"unauthorized";
    case CBManagerStatePoweredOff:
      return @"poweredOff";
    case CBManagerStatePoweredOn:
      return @"poweredOn";
    case CBManagerStateUnknown:
    default:
      return @"unknown";
  }
}

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"discoverServicesCB"];

  if (callback) {
    NSMutableArray *services = [NSMutableArray new];

    for (CBService *service in peripheral.services) {
      NSMutableDictionary *data = [NSMutableDictionary new];
      data[@"peripheralUUID"] = peripheral.identifier.UUIDString;
      data[@"serviceUUID"] = service.UUID.UUIDString;
      [services addObject:data];
    }

    callback(@[services]);
    [_callbacks removeObjectForKey:@"discoverServicesCB"];
  }

}


- (void)peripheral:(CBPeripheral *)peripheral didDiscoverCharacteristicsForService:(CBService *)service error:(NSError *)error {
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"discoverCharacteristicsCB"];

  if (callback) {
    NSMutableArray *characteristics = [NSMutableArray new];

    for (CBCharacteristic *characteristic in service.characteristics) {
      NSMutableDictionary *data = [NSMutableDictionary new];

      data[@"peripheralUUID"] = peripheral.identifier.UUIDString;
      data[@"serviceUUID"] = service.UUID.UUIDString;
      data[@"characteristicUUID"] = characteristic.UUID.UUIDString;

      [characteristics addObject:data];
    }

    callback(@[characteristics]);
    [_callbacks removeObjectForKey:@"discoverCharacteristicsCB"];
  }
}


- (void)peripheral:(CBPeripheral *)peripheral didUpdateNotificationStateForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  RCTResponseSenderBlock callback = [_callbacks objectForKey:@"subscribeToCharacteristicCB"];

  NSMutableDictionary *retObject = [NSMutableDictionary new];
  retObject[@"peripheralUUID"] = peripheral.identifier.UUIDString;
  retObject[@"serviceUUID"] = characteristic.service.UUID.UUIDString;
  retObject[@"characteristicUUID"] = characteristic.UUID.UUIDString;

  if (callback) {
    [_callbacks removeObjectForKey:@"subscribeToCharacteristicCB"];

    if(error) {
      callback(@[]);
      return;
    }

    callback(@[retObject]);
  }
}

- (void)peripheral:(CBPeripheral *)peripheral didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
  NSMutableDictionary *retObject = [NSMutableDictionary new];
  retObject[@"peripheralUUID"] = peripheral.identifier.UUIDString;
  retObject[@"serviceUUID"] = characteristic.service.UUID.UUIDString;
  retObject[@"characteristicUUID"] = characteristic.UUID.UUIDString;

  if(_startedSend == NO) {
    _startedSend = YES;
    UInt32 size;
    [characteristic.value getBytes:&size length:sizeof(size)];

    _finalBytes = [[NSNumber alloc] initWithUnsignedInteger:size];
    _receivedData = [[NSMutableData alloc] initWithLength:0];

    [self sendEventWithName:@"transferStarted" body:retObject];
  }
  else {
    [_receivedData appendData:characteristic.value];
  }

  NSNumber *receivedBytes = [[NSNumber alloc] initWithUnsignedInteger:[_receivedData length]];
  retObject[@"receivedBytes"] = receivedBytes;
  retObject[@"finalBytes"] = _finalBytes;
  [self sendEventWithName:@"transferProgress" body:retObject];

  if([receivedBytes isEqualToNumber:_finalBytes]) {
    _startedSend = NO;

    NSString *stringFromData = [[NSString alloc] initWithData:_receivedData encoding:NSUTF8StringEncoding];
    retObject[@"value"] = stringFromData;

    [self sendEventWithName:@"transferDone" body:retObject];
  }
}


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
  
  if(_chunkCount-1 == _chunkCountTarget) {
    RCTResponseSenderBlock callback = [_callbacks objectForKey:@"finishedWritingCB"];

    if (callback) {
      [_callbacks removeObjectForKey:@"finishedWritingCB"];
      callback(@[retObject]);
    }
  }
}


@end
