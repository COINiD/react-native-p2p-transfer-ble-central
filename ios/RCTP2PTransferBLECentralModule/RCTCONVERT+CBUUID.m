//
//  RCTCONVERT+CBUUID.m
//  RCTP2PTransferBLECentralModule
//
//  Created by Jacob Rosenthal on 9/7/15.
//  Copyright (c) 2015 Facebook. All rights reserved.
//

#import "RCTCONVERT+CBUUID.h"

@implementation RCTConvert(CBUUID)

+ (CBUUID *)CBUUID:(id)json
{
    return [CBUUID UUIDWithString:json];
}

RCT_ARRAY_CONVERTER(CBUUID)

@end
