import { NativeModules, NativeEventEmitter, Platform, PermissionsAndroid } from 'react-native';

import EventEmitter from 'react-native/Libraries/vendor/emitter/EventEmitter';

const bleCentralModule = NativeModules.P2PTransferBLECentralModule;
const bleCentralEmitter = new NativeEventEmitter(bleCentralModule);

class BLECentral extends EventEmitter {
  constructor() {
    super();

    bleCentralEmitter.addListener('didUpdateState', (data) => {
      console.log('didUpdateState', data);
    });

    bleCentralEmitter.addListener('didDiscoverPeripheral', (data) => {
      console.log('didDiscoverPeripheral', data);
    });

    bleCentralEmitter.addListener('didFailToConnectPeripheral', (data) => {
      console.log('didFailToConnectPeripheral', data);
    });

    bleCentralEmitter.addListener('didUpdateValueForCharacteristic', (data) => {
      console.log('didUpdateValueForCharacteristic', data);
    });

    bleCentralEmitter.addListener('didConnectPeripheral', (data) => {
      console.log('didConnectPeripheral', data);
    });
    
    bleCentralEmitter.addListener('didDisconnectPeripheral', (data) => {
      console.log('didDisconnectPeripheral', data);
    });
  }

  isSupported = () => {
    return new Promise((resolve, reject) => {
      bleCentralModule.isSupported((supported) => {
        resolve(supported);
      });
    });
  }

  stop = () => {
    this.disconnect();
    bleCentralModule.stopScan();
  }

  disconnect = () => {
    return new Promise((resolve, reject) => {
        if(this.connectedPeripheralUUID === undefined) {
          return resolve();
        }

        this.unsubscribe().then(() => {
          console.log("trying to disconnect");
          bleCentralModule.disconnect(this.connectedPeripheralUUID, () => {
            this.connectedPeripheralUUID = undefined;
            return resolve();
          });
        });
    });
  }

  unsubscribe = () => {
    return new Promise((resolve, reject) => {
      if(this.subscribedCharacteristic === undefined) {
        return resolve();
      }

      const { serviceUUID, characteristicUUID } = this.subscribedCharacteristic;

      bleCentralEmitter.removeAllListeners('transferStarted');
      bleCentralEmitter.removeAllListeners('transferProgress');
      bleCentralEmitter.removeAllListeners('transferDone');

      console.log('trying to unsubscribed');
      bleCentralModule.unSubscribeToCharacteristic(serviceUUID, characteristicUUID, () => {
        console.log('unsubscribed...')
        this.subscribedCharacteristic = undefined;
        return resolve();
      });
    });
    }

  connect = (filter) => {
    const {serviceUUID, localName} = filter;

    return new Promise((resolve, reject) => {
      this.isSupported().then((supported) => {
        if(!supported) {
          return reject("BLE is not supported on your device.");
        }

        bleCentralModule.start(started => {

          console.log("module started and powered on", started);

          if(!started) {
            return reject('Unable to start. Make sure bluetooth is enabled.');
          }

          this.disconnect().then(() => {
            console.log("disconnected... starting scanning");

            bleCentralModule.scanForPeripheralsWithServices({ serviceUUID, localName }, ({peripheralUUID}) => {
              console.log("found peripheral", peripheralUUID);
              bleCentralModule.stopScan();

              bleCentralModule.connect(peripheralUUID, connectedPeripheralUUID => {
                console.log('connection?');
                if(peripheralUUID !== connectedPeripheralUUID) {
                  this.emit('connectionFailed');
                  return reject('Connection failed');
                }

                console.log('connected', connectedPeripheralUUID);

                this.connectedPeripheralUUID = connectedPeripheralUUID;
                this.emit('connected', connectedPeripheralUUID);
                return resolve(connectedPeripheralUUID);
              });
            });
          });
        });
      });
    });
  }

  discover = (serviceUUID, characteristicUUID) => {
    console.log("start discover serv...", serviceUUID);

    return new Promise((resolve, reject) => {
      bleCentralModule.discoverServices(serviceUUID, data => {
        if(!data) {
          reject('No services discovered');
          return ;
        }
        console.log("discovered serv...", data);
        
        bleCentralModule.discoverCharacteristics(serviceUUID, characteristicUUID, (data) => {
          if(!data) {
            reject('No characterictics discovered');
            return ;
          }
          console.log("discover char...", data);
          return resolve(data);
        });
      });
    });
  }

  connectAndDiscover = (filter) => {
    const {serviceUUID, localName, characteristicUUID} = filter;

    return new Promise((resolve, reject) => {
      this.connect({serviceUUID, localName})
      .then(peripheralUUID => {
        console.log("connected:", peripheralUUID);

        this.discover(serviceUUID, characteristicUUID)
        .then(data => {
          return resolve(data);
        }).catch(reject);
      }).catch(reject);
    });
  }

  sendData = (value, filter, sendCharacteristicUUID) => {
    return new Promise((resolve, reject) => {
      this.requestPermission().then(granted => {
        if(!granted) {
          return reject("Did not get users permission");
        }

        const {serviceUUID, localName} = filter;
        const characteristicUUID = sendCharacteristicUUID ? sendCharacteristicUUID : '3333'; // Special characteristic for sending data. Peripheral listens to this.

        if(!serviceUUID) {
          return reject("serviceUUID required filter");
        }

        bleCentralModule.setSendCharacteristic(characteristicUUID);

        var doConnection = (retryNum) => {

          this.connectAndDiscover({serviceUUID, localName, characteristicUUID})
          .then(data => {
            console.log("sendingStarted", data);
            this.emit('sendingStarted', data);

            bleCentralEmitter.addListener('didWriteValueForCharacteristic', (data) => {
              console.log('sendingProgress', data);
              this.emit('sendingProgress', data);
            });

            bleCentralModule.writeValueForCharacteristic(serviceUUID, characteristicUUID, value, () => {
              console.log('sendingDone', data);

              bleCentralEmitter.removeAllListeners('didWriteValueForCharacteristic');

              this.emit('sendingDone', data);
              return resolve();
            });
          })
          .catch(error => {
            console.log("Caught an error", error);
            if(retryNum < 6) {
              console.log("Retrying...", retryNum+1);
              doConnection(retryNum+1);
            }
            else {
              return reject(error);
            }
          });

        }

        doConnection(0);
      });
    });
  }

  requestPermission = async () => {
    if (Platform.OS === 'android' && Platform.Version >= 23) {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
          {
            'title': 'Location permission needed for BLE',
            'message': 'In order to use bluetooth low energy Android apps needs the location permission.'
          }
        );

        if (granted === PermissionsAndroid.RESULTS.GRANTED) {
          return true;
        }
      } catch (err) { }
      
      return false;
    }

    return true;
  }

  receiveData = (filter, receiveCharacteristicUUID) => {
    return new Promise((resolve, reject) => {
      this.requestPermission().then(granted => {
        if(!granted) {
          return reject("Did not get users permission");
        }

        const {serviceUUID, localName} = filter;
        const characteristicUUID = receiveCharacteristicUUID ? receiveCharacteristicUUID : '2222'; // Special characteristic for receiving data. Peripheral starts sending once Central subscribes.

        if(!serviceUUID) {
          return reject("serviceUUID required filter");
        }
        
        bleCentralModule.setReceiveCharacteristic(characteristicUUID);

        var doConnection = (retryNum) => {
          this.connectAndDiscover({serviceUUID, localName, characteristicUUID})
          .then(data => {
            bleCentralModule.subscribeToCharacteristic(serviceUUID, characteristicUUID, (data) => {
              console.log("subscribed...", data);
              this.subscribedCharacteristic = { serviceUUID, characteristicUUID };

              if(!data) {
                reject('Error when subscribing to characteristic');
                return ;
              }

              bleCentralEmitter.addListener('transferStarted', (data) => {
                console.log('transferStarted', data);
                bleCentralEmitter.removeAllListeners('transferStarted');

                this.emit('receivingStarted', data);
              });

              bleCentralEmitter.addListener('transferProgress', (data) => {
                console.log('transferProgress', data);

                this.emit('receivingProgress', data);
              });

              bleCentralEmitter.addListener('transferDone', (data) => {
                console.log('transferDone', data);

                this.unsubscribe()
                .then(() => {
                  bleCentralModule.writeValueForCharacteristic(serviceUUID, characteristicUUID, 'finished', () => {
                    this.disconnect().then(() => {
                      this.emit('receivingDone', data);
                      return resolve(data);
                    });
                  });
                })
              });
            });
          })
          .catch(error => {
            console.log("Caught an error", error);
            if(retryNum < 6) {
              console.log("Retrying...", retryNum+1);
              doConnection(retryNum+1);
            }
            else {
              return reject(error);
            }
          });
        }

        doConnection(0);
      })
    });
  }
}

module.exports = new BLECentral();