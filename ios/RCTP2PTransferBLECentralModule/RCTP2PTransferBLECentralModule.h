//
//  RCTP2PTransferBLECentralModule.h
//  RCTP2PTransferBLECentralModule
//
//  Created by Rikard Wissing on 2018-04-01.
//

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTP2PTransferBLECentralModule : RCTEventEmitter <RCTBridgeModule, CBCentralManagerDelegate, CBPeripheralDelegate>{
    CBCentralManager *_manager;
    CBPeripheral *_connectedPeripheral;
    CBPeripheral *_connectingPeripheral;
    CBPeripheral *_disconnectedPeripheral;
    NSMutableDictionary *_callbacks;
    
    NSMutableData *_receivedData;
    NSNumber *_finalBytes;

    BOOL _isPoweredOn;
    NSDictionary *_scanFilter;

    NSInteger _chunkCount;
    NSInteger _chunkCountTarget;
}

@end
