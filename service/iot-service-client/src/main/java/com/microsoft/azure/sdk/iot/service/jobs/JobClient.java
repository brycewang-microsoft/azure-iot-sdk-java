// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.sdk.iot.service.jobs;

import com.azure.core.credential.AzureSasCredential;
import com.azure.core.credential.TokenCredential;
import com.microsoft.azure.sdk.iot.service.serializers.MethodParser;
import com.microsoft.azure.sdk.iot.service.twin.TwinCollection;
import com.microsoft.azure.sdk.iot.service.twin.TwinState;
import com.microsoft.azure.sdk.iot.service.IotHubConnectionString;
import com.microsoft.azure.sdk.iot.service.IotHubConnectionStringBuilder;
import com.microsoft.azure.sdk.iot.service.ProxyOptions;
import com.microsoft.azure.sdk.iot.service.auth.IotHubServiceSasToken;
import com.microsoft.azure.sdk.iot.service.auth.TokenCredentialCache;
import com.microsoft.azure.sdk.iot.service.twin.Twin;
import com.microsoft.azure.sdk.iot.service.twin.Pair;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import com.microsoft.azure.sdk.iot.service.transport.TransportUtils;
import com.microsoft.azure.sdk.iot.service.transport.http.HttpMethod;
import com.microsoft.azure.sdk.iot.service.transport.http.HttpRequest;
import com.microsoft.azure.sdk.iot.service.transport.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

/**
 * Use the JobClient to schedule and cancel jobs for a group of devices using IoT hub.
 */
@Slf4j
public class JobClient
{
    private final static byte[] EMPTY_JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private String hostName;
    private TokenCredentialCache credentialCache;
    private AzureSasCredential azureSasCredential;
    private IotHubConnectionString iotHubConnectionString;
    private JobClientOptions clientOptions;

    /**
     * Constructor to create instance from connection string
     *
     * @param connectionString The iot hub connection string
     */
    public JobClient(String connectionString)
    {
        this(connectionString, JobClientOptions.builder().build());
    }

    /**
     * Constructor to create instance from connection string
     *
     * @param connectionString The iot hub connection string
     * @param clientOptions The connection clientOptions to use when connecting to the service.
     */
    public JobClient(String connectionString, JobClientOptions clientOptions)
    {
        Objects.requireNonNull(clientOptions);
        if (connectionString == null || connectionString.isEmpty())
        {
            throw new IllegalArgumentException("connection string cannot be null or empty");
        }

        this.iotHubConnectionString = IotHubConnectionStringBuilder.createIotHubConnectionString(connectionString);
        this.hostName = this.iotHubConnectionString.getHostName();
        this.clientOptions = clientOptions;
        commonConstructorSetup();
    }

    /**
     * Create a new JobClient instance.
     *
     * @param hostName The hostname of your IoT Hub instance (For instance, "your-iot-hub.azure-devices.net")
     * @param credential The custom {@link TokenCredential} that will provide authentication tokens to
     * this library when they are needed. The provided tokens must be Json Web Tokens.
     */
    public JobClient(String hostName, TokenCredential credential)
    {
        this(hostName, credential, JobClientOptions.builder().build());
    }

    /**
     * Create a new JobClient instance.
     *
     * @param hostName The hostname of your IoT Hub instance (For instance, "your-iot-hub.azure-devices.net")
     * @param credential The custom {@link TokenCredential} that will provide authentication tokens to
     * this library when they are needed. The provided tokens must be Json Web Tokens.
     * @param clientOptions The connection clientOptions to use when connecting to the service.
     */
    public JobClient(String hostName, TokenCredential credential, JobClientOptions clientOptions)
    {
        Objects.requireNonNull(credential);
        Objects.requireNonNull(clientOptions);

        if (hostName == null || hostName.isEmpty())
        {
            throw new IllegalArgumentException("hostName cannot be null or empty");
        }

        this.hostName = hostName;
        this.credentialCache = new TokenCredentialCache(credential);
        this.clientOptions = clientOptions;
        commonConstructorSetup();
    }

    /**
     * Create a new JobClient instance.
     *
     * @param hostName The hostname of your IoT Hub instance (For instance, "your-iot-hub.azure-devices.net")
     * @param azureSasCredential The SAS token provider that will be used for authentication.
     */
    public JobClient(String hostName, AzureSasCredential azureSasCredential)
    {
        this(hostName, azureSasCredential, JobClientOptions.builder().build());
    }

    /**
     * Create a new JobClient instance.
     *
     * @param hostName The hostname of your IoT Hub instance (For instance, "your-iot-hub.azure-devices.net")
     * @param azureSasCredential The SAS token provider that will be used for authentication.
     * @param clientOptions The connection clientOptions to use when connecting to the service.
     */
    public JobClient(String hostName, AzureSasCredential azureSasCredential, JobClientOptions clientOptions)
    {
        Objects.requireNonNull(azureSasCredential);
        Objects.requireNonNull(clientOptions);

        if (hostName == null || hostName.isEmpty())
        {
            throw new IllegalArgumentException("hostName cannot be null or empty");
        }

        this.hostName = hostName;
        this.azureSasCredential = azureSasCredential;
        this.clientOptions = clientOptions;
        commonConstructorSetup();
    }

    private static void commonConstructorSetup()
    {
        log.debug("Initialized a JobClient instance using SDK version {}", TransportUtils.serviceVersion);
    }

    /**
     * Creates a new Job to update twin tags and desired properties on one or multiple devices
     *
     * @param jobId Unique Job Id for this job
     * @param queryCondition Query condition to evaluate which devices to run the job on. It can be {@code null} or empty
     * @param updateTwin Twin object to use for the update
     * @param startTimeUtc Date time in Utc to start the job
     * @param maxExecutionTimeInSeconds Max execution time in seconds, i.e., ttl duration the job can run
     * @return a jobResult object
     * @throws IllegalArgumentException if one of the provided parameters is invalid
     * @throws IOException if the function cannot create a URL for the job
     * @throws IotHubException if the http request failed
     */
    public JobResult scheduleUpdateTwin(
        String jobId,
        String queryCondition,
        Twin updateTwin,
        Date startTimeUtc,
        long maxExecutionTimeInSeconds)
        throws IllegalArgumentException, IOException, IotHubException
    {
        URL url;

        if (jobId == null || jobId.isEmpty())
        {
            throw new IllegalArgumentException("jobId cannot be null or empty");
        }

        if (updateTwin == null)
        {
            throw new IllegalArgumentException("updateTwin cannot be null");
        }

        if (startTimeUtc == null)
        {
            throw new IllegalArgumentException("startTimeUtc cannot be null");
        }

        if (maxExecutionTimeInSeconds < 0)
        {
            throw new IllegalArgumentException("maxExecutionTimeInSeconds cannot be negative");
        }

        JobsParser jobsParser =
            new JobsParser(
                jobId,
                getParserFromDevice(updateTwin),
                queryCondition,
                startTimeUtc,
                maxExecutionTimeInSeconds);

        String json = jobsParser.toJson();

        try
        {
            url = IotHubConnectionString.getUrlJobs(this.hostName, jobId);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalArgumentException("Invalid JobId to create url");
        }

        ProxyOptions proxyOptions = clientOptions.getProxyOptions();
        Proxy proxy = proxyOptions != null ? proxyOptions.getProxy() : null;

        HttpRequest httpRequest = new HttpRequest(
            url,
            HttpMethod.PUT,
            json.getBytes(StandardCharsets.UTF_8),
            this.getAuthenticationToken(),
            proxy);

        httpRequest.setReadTimeoutMillis(clientOptions.getHttpReadTimeout());
        httpRequest.setConnectTimeoutMillis(clientOptions.getHttpConnectTimeout());

        HttpResponse response = httpRequest.send();

        return new JobResult(new String(response.getBody()));
    }

    /**
     * Creates a new Job to invoke method on one or multiple devices
     *
     * @param jobId Unique Job Id for this job
     * @param queryCondition Query condition to evaluate which devices to run the job on. It can be {@code null} or empty
     * @param methodName Method name to be invoked
     * @param startTimeUtc Date time in Utc to start the job
     * @return a jobResult object
     * @throws IOException if the function cannot create a URL for the job, or the IO failed on request
     * @throws IotHubException if the http request failed
     */
    public JobResult scheduleDirectMethod(
        String jobId,
        String queryCondition,
        String methodName,
        Date startTimeUtc)
            throws IOException, IotHubException
    {
        return scheduleDirectMethod(jobId, queryCondition, methodName, startTimeUtc, ScheduleDirectMethodOptions.builder().build());
    }

    /**
     * Creates a new Job to invoke method on one or multiple devices
     *
     * @param jobId Unique Job Id for this job
     * @param queryCondition Query condition to evaluate which devices to run the job on. It can be {@code null} or empty
     * @param methodName Method name to be invoked
     * @param startTimeUtc Date time in Utc to start the job
     * @param options the optional parameters for this request. May not be null.
     * @return a jobResult object
     * @throws IOException if the function cannot create a URL for the job, or the IO failed on request
     * @throws IotHubException if the http request failed
     */
    public JobResult scheduleDirectMethod(
        String jobId,
        String queryCondition,
        String methodName,
        Date startTimeUtc,
        ScheduleDirectMethodOptions options)
            throws IOException, IotHubException
    {
        URL url;

        if (jobId == null || jobId.isEmpty())
        {
            throw new IllegalArgumentException("jobId cannot be null or empty");
        }

        if (methodName == null || methodName.isEmpty())
        {
            throw new IllegalArgumentException("method name cannot be null or empty");
        }

        if (startTimeUtc == null)
        {
            throw new IllegalArgumentException("startTimeUtc cannot be null");
        }

        Objects.requireNonNull(options);

        if (options.getMaxExecutionTimeInSeconds() < 0)
        {
            throw new IllegalArgumentException("maxExecutionTimeInSeconds cannot be less than 0");
        }

        MethodParser cloudToDeviceMethod =
            new MethodParser(
                methodName,
                options.getMethodResponseTimeout(),
                options.getMethodConnectTimeout(),
                options.getPayload());

        JobsParser jobsParser =
            new JobsParser(
                jobId,
                cloudToDeviceMethod,
                queryCondition,
                startTimeUtc,
                options.getMaxExecutionTimeInSeconds());

        String json = jobsParser.toJson();

        try
        {
            url = IotHubConnectionString.getUrlJobs(this.hostName, jobId);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalArgumentException("Invalid JobId to create url");
        }

        ProxyOptions proxyOptions = this.clientOptions.getProxyOptions();
        Proxy proxy = proxyOptions != null ? proxyOptions.getProxy() : null;

        HttpRequest httpRequest = new HttpRequest(
            url,
            HttpMethod.PUT,
            json.getBytes(StandardCharsets.UTF_8),
            this.getAuthenticationToken(),
            proxy);

        httpRequest.setReadTimeoutMillis(this.clientOptions.getHttpReadTimeout());
        httpRequest.setConnectTimeoutMillis(this.clientOptions.getHttpConnectTimeout());

        HttpResponse response = httpRequest.send();

        return new JobResult(new String(response.getBody()));
    }

    /**
     * Get the current job on the iotHub.
     *
     * @param jobId Unique Job Id for this job
     * @return a jobResult object
     * @throws IllegalArgumentException if the jobId is invalid
     * @throws IOException if the function cannot create a URL for the job, or the IO failed on request
     * @throws IotHubException if the http request failed
     */
    public JobResult getJob(String jobId)
        throws IllegalArgumentException, IOException, IotHubException
    {
        URL url;

        if (jobId == null || jobId.isEmpty())
        {
            throw new IllegalArgumentException("jobId cannot be null or empty");
        }

        try
        {
            url = IotHubConnectionString.getUrlJobs(this.hostName, jobId);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalArgumentException("Invalid JobId to create url");
        }

        ProxyOptions proxyOptions = clientOptions.getProxyOptions();
        Proxy proxy = proxyOptions != null ? proxyOptions.getProxy() : null;

        HttpRequest httpRequest = new HttpRequest(
            url,
            HttpMethod.GET,
            new byte[0],
            this.getAuthenticationToken(),
            proxy);

        httpRequest.setReadTimeoutMillis(clientOptions.getHttpReadTimeout());
        httpRequest.setConnectTimeoutMillis(clientOptions.getHttpConnectTimeout());

        HttpResponse response = httpRequest.send();

        return new JobResult(new String(response.getBody()));
    }

    /**
     * Cancel a current jod on the IoTHub
     *
     * @param jobId Unique Job Id for this job
     * @return a jobResult object
     * @throws IllegalArgumentException if the jobId is invalid
     * @throws IOException if the function cannot create a URL for the job, or the IO failed on request
     * @throws IotHubException if the http request failed
     */
    public JobResult cancelJob(String jobId)
        throws IllegalArgumentException, IOException, IotHubException
    {
        URL url;
        if (jobId == null || jobId.isEmpty())
        {
            throw new IllegalArgumentException("jobId cannot be null or empty");
        }

        try
        {
            url = IotHubConnectionString.getUrlJobsCancel(this.hostName, jobId);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalArgumentException("Invalid JobId to create url");
        }

        ProxyOptions proxyOptions = clientOptions.getProxyOptions();
        Proxy proxy = proxyOptions != null ? proxyOptions.getProxy() : null;

        HttpRequest httpRequest = new HttpRequest(
            url,
            HttpMethod.POST,
            EMPTY_JSON,
            this.getAuthenticationToken(),
            proxy);

        httpRequest.setReadTimeoutMillis(clientOptions.getHttpReadTimeout());
        httpRequest.setConnectTimeoutMillis(clientOptions.getHttpConnectTimeout());

        HttpResponse response = httpRequest.send();

        return new JobResult(new String(response.getBody()));
    }

    private TwinState getParserFromDevice(Twin device)
    {
        TwinCollection tags = null;
        TwinCollection desired = null;
        TwinCollection reported = null;

        if (device.getTags() != null)
        {
            tags = setToMap(device.getTags());
        }

        if (device.getDesiredProperties() != null)
        {
            desired = setToMap(device.getDesiredProperties());
        }

        if (device.getReportedProperties() != null)
        {
            reported = setToMap(device.getReportedProperties());
        }

        TwinState twinState = new TwinState(tags, desired, reported);

        if (device.getDeviceId() != null)
        {
            twinState.setDeviceId(device.getDeviceId());
        }

        if (device.getETag() == null)
        {
            twinState.setETag("*");
        }
        else
        {
            twinState.setETag(device.getETag());
        }

        return twinState;
    }

    private TwinCollection setToMap(Set<Pair> set)
    {
        TwinCollection map = new TwinCollection();

        if (set != null)
        {
            for (Pair p : set)
            {
                map.put(p.getKey(), p.getValue());
            }
        }
        return map;
    }

    @SuppressWarnings("unused")
    protected JobClient()
    {
    }

    private String getAuthenticationToken()
    {
        // Three different constructor types for this class, and each type provides either a TokenCredential implementation,
        // an AzureSasCredential instance, or just the connection string. The sas token can be retrieved from the non-null
        // one of the three clientOptions.
        if (this.credentialCache != null)
        {
            return this.credentialCache.getTokenString();
        }
        else if (this.azureSasCredential != null)
        {
            return this.azureSasCredential.getSignature();
        }

        return new IotHubServiceSasToken(iotHubConnectionString).toString();
    }
}
