/**
 * Copyright (c) 2008 Andrew Rapp. All rights reserved.
 *
 * This file is part of XBee-API.
 *
 * XBee-API is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * XBee-API is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with XBee-API.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.arduino2fa.xbee;

import com.rapplogic.xbee.api.*;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import com.rapplogic.xbee.api.wpan.TxRequest16;
import com.rapplogic.xbee.api.wpan.TxStatusResponse;

/**
 * Sends a TX Request every 5000 ms and waits for TX status packet.
 * If the radio is sending samples it will continue to wait for tx status.
 *
 * @author andrew
 *
 */
public class TransmitPacket {

    private final static Logger log = Logger.getLogger(TransmitPacket.class);

    private TransmitPacket(String xbeePort, int xbeeBaudRate, boolean ledState) throws Exception {

        XBee xbee = new XBee();

        final int timeout = 5000;

        int count = 0;
        int errors = 0;
        int ackErrors = 0;
        int ccaErrors = 0;
        int purgeErrors = 0;

        long now;

        try {
            // replace with port and baud rate of your XBee
            xbee.open(xbeePort, xbeeBaudRate);


            int[] payload = new int[] { ledState ? 0x00 : 0xFF };

            // specify the remote XBee 16-bit MY address
            XBeeAddress16 destination = new XBeeAddress16(0xFF, 0xFF);
            TxRequest16 tx = new TxRequest16(destination, payload);

            now = System.currentTimeMillis();

            try {
                XBeeResponse response = xbee.sendSynchronous(tx, timeout);

                if (response.getApiId() != ApiId.TX_STATUS_RESPONSE) {
                    log.debug("expected tx status but received " + response);
                } else {
                    log.debug("got tx status");

                    if (((TxStatusResponse) response).getFrameId() != tx.getFrameId()) {
                        throw new RuntimeException("frame id does not match");
                    }

                    if (((TxStatusResponse) response).getStatus() != TxStatusResponse.Status.SUCCESS) {
                        errors++;

                        if (((TxStatusResponse) response).isAckError()) {
                            ackErrors++;
                        } else if (((TxStatusResponse) response).isCcaError()) {
                            ccaErrors++;
                        } else if (((TxStatusResponse) response).isPurged()) {
                            purgeErrors++;
                        }

                        log.debug("Tx status failure with status: " + ((TxStatusResponse) response).getStatus());
                    } else {
                        // success
                        log.debug("Success.  count is " + count + ", errors is " + errors + ", in " + (System.currentTimeMillis() - now) + ", ack errors "
                                + ackErrors + ", ccaErrors " + ccaErrors + ", purge errors " + purgeErrors);
                    }

                    count++;

                }
            } catch (XBeeTimeoutException e) {
                e.printStackTrace();
            }
        } finally {
            xbee.close();
        }
    }

    public static void main(String[] args) throws Exception {
        boolean ledAction = true; // turn LED on (implies LED should currently be off)

        // init log4j
        PropertyConfigurator.configure("log4j.properties");

        // get command-line arguments
        if (args.length > 0) {
            for (String argument : args) {
                if (argument.equals("-ledOn")) {
                    ledAction = true;
                }

                else if (argument.equals("-ledOff")) {
                    ledAction = false;
                }
            }
        }

        new TransmitPacket(XbeeConfig.PORT, XbeeConfig.BAUD_RATE, !ledAction);
    }
}