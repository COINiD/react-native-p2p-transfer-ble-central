import { NativeModules, NativeEventEmitter } from 'react-native';

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
			bleCentralModule.start(started => {

				console.log("module started and powered on");

				if(!started) {
					return reject('Failed to start BLE module');
				}

				this.disconnect().then(() => {
					console.log("disconnected...");

				  bleCentralModule.scanForPeripheralsWithServices({ serviceUUID, localName }, ({peripheralUUID}) => {
				  	console.log("found peripheral", peripheralUUID);
				  	bleCentralModule.stopScan();

				  	bleCentralModule.connect(peripheralUUID, connectedPeripheralUUID => {
				  		if(peripheralUUID !== connectedPeripheralUUID) {
				  			this.emit('connectionFailed');
				  			return reject('Connection failed');
				  		}

				  		this.connectedPeripheralUUID = connectedPeripheralUUID;
				  		
				  		this.emit('connected', connectedPeripheralUUID);
				  		return resolve(connectedPeripheralUUID);
				  	});
				  });

				});
			});
		});
	}

	discover = (serviceUUID, characteristicUUID) => {
		return new Promise((resolve, reject) => {
			bleCentralModule.discoverServices(serviceUUID, data => {
				console.log("discovered serv...", data);
		        
				bleCentralModule.discoverCharacteristics(serviceUUID, characteristicUUID, (data) => {
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
				});
			});
		});
	}

	sendData = (value, filter) => {
		const {serviceUUID, localName} = filter;
		const characteristicUUID = '3333'; // Special characteristic for sending data. Peripheral listens to this.

		return new Promise((resolve, reject) => {
			if(!serviceUUID) {
				return reject("serviceUUID required filter");
			}

			this.connectAndDiscover({serviceUUID, localName, characteristicUUID})
			.then(data => {
				console.log("discovered serv...", data);
				this.emit('sendingStarted', data);

				bleCentralEmitter.addListener('didWriteValueForCharacteristic', (data) => {
					this.emit('sendingProgress', data);
				});

        bleCentralModule.writeValueForCharacteristic(serviceUUID, characteristicUUID, value, () => {
        	bleCentralEmitter.removeAllListeners('didWriteValueForCharacteristic');

        	this.emit('sendingDone', data);
          resolve();
        });
			});
		});
	}

	receiveData = (filter) => {
		const {serviceUUID, localName} = filter;
		const characteristicUUID = '2222'; // Special characteristic for receiving data. Peripheral starts sending once Central subscribes.

		return new Promise((resolve, reject) => {
			if(!serviceUUID) {
				return reject("serviceUUID required filter");
			}
			
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
			    	bleCentralEmitter.removeAllListeners('transferStarted');
			    	this.emit('receivingStarted', data);
			    });

			    bleCentralEmitter.addListener('transferProgress', (data) => {
			    	this.emit('receivingProgress', data);
			    });

			    bleCentralEmitter.addListener('transferDone', (data) => {
			    	this.unsubscribe().then(() => {
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
			.catch(() => {
				return reject('Error when connecting to characteristic');
			});
		});
	}

}

module.exports = new BLECentral();