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
import com.rapplogic.xbee.api.wpan.TxRequest16;
import com.rapplogic.xbee.api.wpan.TxStatusResponse;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Receives IO samples from remote radio
 * I have a photoresistor connected to analog0 and a thermistor is connected to analog1
 * Also there is a breadboard switch connected to digital2 with change detect configured
 * 
 * @author andrew
 * 
 */
public class ReceivePacket {

	private final static Logger log = Logger.getLogger(ReceivePacket.class);

	private long last = System.currentTimeMillis();

	private ReceivePacket() throws Exception {
		XBee xbee = new XBee();		
		
		int count = 0;
		int errors = 0;

        // Transmit stuff
        final int timeout = 5000;

        int ackErrors = 0;
        int ccaErrors = 0;
        int purgeErrors = 0;
        long now;

        // HTTP stuff
        CloseableHttpClient httpClient = HttpClientBuilder.create().build();
        HttpHost httpHost = new HttpHost("ec2-54-186-213-97.us-west-2.compute.amazonaws.com", 3000, "http");

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
                new AuthScope(httpHost.getHostName(), httpHost.getPort()),
                new UsernamePasswordCredentials("admin", "admin") // TODO get from command line
        );

        // Create AuthCache instance
        AuthCache authCache = new BasicAuthCache();
        BasicScheme basicScheme = new BasicScheme();
        authCache.put(httpHost, basicScheme);

        // Add AuthCache to the execution context
        HttpClientContext httpClientContext = HttpClientContext.create();
        httpClientContext.setCredentialsProvider(credentialsProvider);
        httpClientContext.setAuthCache(authCache);

        HttpGet httpGet = new HttpGet("/token-requests/1");

        CloseableHttpResponse httpResponse;

        BufferedReader br;
        StringBuffer result;
        String line;

		try {
            // Connect to the XBee
			xbee.open(XbeeConfig.PORT, XbeeConfig.BAUD_RATE);

            now = System.currentTimeMillis();

			// Loop indefinitely; sleeps for a few seconds at the end of every iteration
            while (true) {

                // Check if there are queued tx requests on the server
                httpResponse = httpClient.execute(httpHost, httpGet, httpClientContext);
                br = new BufferedReader(new InputStreamReader(httpResponse.getEntity().getContent()));

                result = new StringBuffer();

                while ((line = br.readLine()) != null) {
                    result.append(line);
                }

                // Check if the result is a JSON object
                if (result.charAt(0) != '{') {
                    log.error("Result " + result.toString() + " is not a JSON object");
                    continue;
                }

                JSONObject object = (JSONObject) JSONValue.parse(result.toString());

                if (object != null) {
                    Long time = (Long) object.get("time");

                    // Check if the request is a new one (created after last check)
                    if (time > last) {
                        String token = (String) object.get("token");
                        byte[] tokenHex = SimpleCrypto.toByte(token);

                        int[] payload = new int[] { tokenHex[0], tokenHex[1], tokenHex[2] };

                        XBeeAddress16 destination = new XBeeAddress16(0xFF, 0xFF);
                        TxRequest16 tx = new TxRequest16(destination, payload);

                        try {
                            log.info("sending tx request with payload: " + token);
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
                    }
                }

                last = System.currentTimeMillis();
                httpGet.releaseConnection();

                // Delay
                Thread.sleep(2000);
			}
		} finally {
			xbee.close();
		}
	}

	public static void main(String[] args) throws Exception {
		// init log4j
		PropertyConfigurator.configure("log4j.properties");
		new ReceivePacket();
	}
}
