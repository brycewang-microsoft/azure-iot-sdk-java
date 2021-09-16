/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.service;

import com.azure.core.credential.AzureSasCredential;
import com.azure.core.credential.TokenCredential;
import com.microsoft.azure.sdk.iot.service.transport.amqps.AmqpFileUploadNotificationReceive;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class FileUploadNotificationReceiver
{
    private final long DEFAULT_TIMEOUT_MS = 60000;
    private final AmqpFileUploadNotificationReceive amqpFileUploadNotificationReceive;

    /**
     * Constructor to verify initialization parameters
     * Create instance of AmqpReceive
     * @param hostName The iot hub host name
     * @param userName The iot hub user name
     * @param sasToken The iot hub SAS token for the given device
     * @param iotHubServiceClientProtocol The iot hub protocol name
     * @param proxyOptions the proxy options to tunnel through, if a proxy should be used.
     * @param sslContext the SSL context to use during the TLS handshake when opening the connection. If null, a default
     *                   SSL context will be generated. This default SSLContext trusts the IoT Hub public certificates.
     */
    FileUploadNotificationReceiver(
            String hostName,
            String userName,
            String sasToken,
            IotHubServiceClientProtocol iotHubServiceClientProtocol,
            ProxyOptions proxyOptions,
            SSLContext sslContext)
    {
        if (Tools.isNullOrEmpty(hostName))
        {
            throw new IllegalArgumentException("hostName cannot be null or empty");
        }
        if (Tools.isNullOrEmpty(userName))
        {
            throw new IllegalArgumentException("userName cannot be null or empty");
        }
        if (Tools.isNullOrEmpty(sasToken))
        {
            throw new IllegalArgumentException("sasToken cannot be null or empty");
        }
        if (iotHubServiceClientProtocol == null)
        {
            throw new IllegalArgumentException("iotHubServiceClientProtocol cannot be null");
        }

        this.amqpFileUploadNotificationReceive = new AmqpFileUploadNotificationReceive(hostName, userName, sasToken, iotHubServiceClientProtocol, proxyOptions, sslContext);
    }

    FileUploadNotificationReceiver(
            String hostName,
            TokenCredential credential,
            IotHubServiceClientProtocol iotHubServiceClientProtocol,
            ProxyOptions proxyOptions,
            SSLContext sslContext)
    {
        if (Tools.isNullOrEmpty(hostName))
        {
            throw new IllegalArgumentException("hostName cannot be null or empty");
        }

        Objects.requireNonNull(credential);
        Objects.requireNonNull(iotHubServiceClientProtocol);

        this.amqpFileUploadNotificationReceive =
                new AmqpFileUploadNotificationReceive(
                        hostName,
                        credential,
                        iotHubServiceClientProtocol,
                        proxyOptions,
                        sslContext);
    }

    FileUploadNotificationReceiver(
            String hostName,
            AzureSasCredential sasTokenProvider,
            IotHubServiceClientProtocol iotHubServiceClientProtocol,
            ProxyOptions proxyOptions,
            SSLContext sslContext)
    {
        if (Tools.isNullOrEmpty(hostName))
        {
            throw new IllegalArgumentException("hostName cannot be null or empty");
        }

        Objects.requireNonNull(sasTokenProvider);
        Objects.requireNonNull(iotHubServiceClientProtocol);

        this.amqpFileUploadNotificationReceive =
                new AmqpFileUploadNotificationReceive(
                        hostName,
                        sasTokenProvider,
                        iotHubServiceClientProtocol,
                        proxyOptions,
                        sslContext);
    }

    /**
     * Open AmqpReceive object
     *
     */
    public void open()
    {
        log.info("Opening file upload notification receiver");

        this.amqpFileUploadNotificationReceive.open();

        log.info("Opened file upload notification receiver");
    }

    /**
     * Close AmqpReceive object
     *
     */
    public void close()
    {
        log.info("Closing file upload notification receiver");

        this.amqpFileUploadNotificationReceive.close();

        log.info("Closed file upload notification receiver");
    }

    /**
     * Receive FileUploadNotification with default timeout
     *
     * QoS for receiving file upload notifications is at least once
     *
     * This function is synchronized internally so that only one receive operation is allowed at a time.
     * In order to do more receive operations at a time, you will need to instantiate another FileUploadNotificationReceiver instance.
     *
     * @return The received FileUploadNotification object
     * @throws IOException This exception is thrown if the input AmqpReceive object is null
     */
    public FileUploadNotification receive() throws IOException
    {
        return receive(DEFAULT_TIMEOUT_MS);
    }

    /**
     * Receive FileUploadNotification with specific timeout
     *
     * QoS for receiving file upload notifications is at least once
     *
     * This function is synchronized internally so that only one receive operation is allowed at a time.
     * In order to do more receive operations at a time, you will need to instantiate another FileUploadNotificationReceiver instance.
     *
     * @param timeoutMs The timeout in milliseconds
     * @return The received FileUploadNotification object
     * @throws IOException This exception is thrown if the input AmqpReceive object is null
     */
    public FileUploadNotification receive(long timeoutMs) throws IOException
    {
        if (this.amqpFileUploadNotificationReceive == null)
        {
            throw new IOException("AMQP receiver is not initialized");
        }

        return this.amqpFileUploadNotificationReceive.receive(timeoutMs);
    }
}
