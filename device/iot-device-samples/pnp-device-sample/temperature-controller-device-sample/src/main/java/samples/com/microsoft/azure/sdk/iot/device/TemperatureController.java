// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package samples.com.microsoft.azure.sdk.iot.device;

import com.microsoft.azure.sdk.iot.deps.convention.ClientPropertyCollection;
import com.microsoft.azure.sdk.iot.deps.convention.WritablePropertyResponse;
import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.*;
import com.microsoft.azure.sdk.iot.device.convention.*;
import com.microsoft.azure.sdk.iot.provisioning.device.*;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.exceptions.ProvisioningDeviceClientException;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProviderSymmetricKey;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.util.Map.Entry;

@Slf4j
public class TemperatureController {

    public enum StatusCode {
        COMPLETED (200),
        IN_PROGRESS (202),
        NOT_FOUND (404);

        private final int value;
        StatusCode(int value) {
            this.value = value;
        }
    }

    // DTDL interface used: https://github.com/Azure/iot-plugandplay-models/blob/main/dtmi/com/example/temperaturecontroller-2.json
    // The TemperatureController model contains 2 Thermostat components that implement different versions of Thermostat models.
    // Both Thermostat models are identical in definition but this is done to allow IoT Central to handle
    // TemperatureController model correctly.

    private static final String deviceConnectionString = System.getenv("IOTHUB_DEVICE_CONNECTION_STRING");
    private static final String deviceSecurityType = System.getenv("IOTHUB_DEVICE_SECURITY_TYPE");
    private static final String MODEL_ID = "dtmi:com:example:TemperatureController;2";
    private static final String THERMOSTAT_1 = "thermostat1";
    private static final String THERMOSTAT_2 = "thermostat2";
    private static final String SERIAL_NO = "SR-123456";

    // Environmental variables for Dps
    private static final String scopeId = System.getenv("IOTHUB_DEVICE_DPS_ID_SCOPE");
    private static final String globalEndpoint = System.getenv("IOTHUB_DEVICE_DPS_ENDPOINT");
    private static final String deviceSymmetricKey = System.getenv("IOTHUB_DEVICE_DPS_DEVICE_KEY");
    private static final String registrationId = System.getenv("IOTHUB_DEVICE_DPS_DEVICE_ID");

    // Plug and play features are available over MQTT, MQTT_WS, AMQPS, and AMQPS_WS.
    private static final ProvisioningDeviceClientTransportProtocol provisioningProtocol = ProvisioningDeviceClientTransportProtocol.MQTT;
    private static final IotHubClientProtocol protocol = IotHubClientProtocol.MQTT;

    private static final int MAX_TIME_TO_WAIT_FOR_REGISTRATION = 1000; // in milli seconds

    private static final Random random = new Random();
    private static DeviceClient deviceClient;

    // HashMap to hold the temperature updates sent over each "Thermostat" component.
    // NOTE: Memory constrained device should leverage storage capabilities of an external service to store this information and perform computation.
    // See https://docs.microsoft.com/en-us/azure/event-grid/compare-messaging-services for more details.
    private static final Map<String, Map<Date, Double>> temperatureReadings = new HashMap<>();

    // HashMap to hold the current temperature for each "Thermostat" component.
    private static final Map<String, Double> temperature = new HashMap<>();

    // HashMap to hold the max temperature since last reboot, for each "Thermostat" component.
    private static final Map<String, Double> maxTemperature = new HashMap<>();

    static class ProvisioningStatus
    {
        ProvisioningDeviceClientRegistrationResult provisioningDeviceClientRegistrationInfoClient = new ProvisioningDeviceClientRegistrationResult();
        Exception exception;
    }

    static class ProvisioningDeviceClientRegistrationCallbackImpl implements ProvisioningDeviceClientRegistrationCallback
    {
        @Override
        public void run(ProvisioningDeviceClientRegistrationResult provisioningDeviceClientRegistrationResult, Exception exception, Object context)
        {
            if (context instanceof ProvisioningStatus)
            {
                ProvisioningStatus status = (ProvisioningStatus) context;
                status.provisioningDeviceClientRegistrationInfoClient = provisioningDeviceClientRegistrationResult;
                status.exception = exception;
            }
            else
            {
                System.out.println("Received unknown context");
            }
        }
    }

    public static void main(String[] args) throws IOException, URISyntaxException, ProvisioningDeviceClientException, InterruptedException {

        // This sample follows the following workflow:
        // -> Initialize device client instance.
        // -> Set handler to receive "reboot" command - root interface.
        // -> Set handler to receive "getMaxMinReport" command - on "Thermostat" components.
        // -> Set handler to receive "targetTemperature" property updates from service - on "Thermostat" components.
        // -> Send initial device info - "workingSet" over telemetry, "serialNumber" over reported property update - root interface.
        // -> Periodically send "temperature" over telemetry - on "Thermostat" components.
        // -> Send "maxTempSinceLastReboot" over property update, when a new max temperature is set - on "Thermostat" components.

        // This environment variable indicates if DPS or IoT Hub connection string will be used to provision the device.
        // Expected values: (case-insensitive)
        // "DPS" - The sample will use DPS to provision the device.
        // "connectionString" - The sample will use IoT Hub connection string to provision the device.
        if ((deviceSecurityType == null) || deviceSecurityType.isEmpty())
        {
            throw new IllegalArgumentException("Device security type needs to be specified, please set the environment variable \"IOTHUB_DEVICE_SECURITY_TYPE\"");
        }

        log.debug("Initialize the device client.");

        switch (deviceSecurityType.toLowerCase())
        {
            case "dps":
            {
                if (validateArgsForDpsFlow())
                {
                    initializeAndProvisionDevice();
                    break;
                }
                throw new IllegalArgumentException("Required environment variables are not set for DPS flow, please recheck your environment.");
            }
            case "connectionstring":
            {
                if (validateArgsForIotHubFlow())
                {
                    initializeDeviceClient();
                    break;
                }
                throw new IllegalArgumentException("Required environment variables are not set for IoT Hub flow, please recheck your environment.");
            }
            default:
            {
                throw new IllegalArgumentException("Unrecognized value for IOTHUB_DEVICE_SECURITY_TYPE received: {s_deviceSecurityType}." +
                        " It should be either \"DPS\" or \"connectionString\" (case-insensitive).");
            }
        }

        log.debug("Set handler for \"reboot\" command.");
        log.debug("Set handler for \"getMaxMinReport\" command.");
        deviceClient.subscribeToDeviceComamnds(new CommandsCallback(), null, new CommandIotHubEventCallback(), null);

        log.debug("Set handler to receive \"targetTemperature\" updates.");
        deviceClient.startDeviceTwin(new TwinIotHubEventCallback(), null, new GenericPropertyUpdateCallback(), null);
        deviceClient.subscribeToWritablePropertiesEvent(new TargetTemperatureUpdateCallback(), null);

        updateDeviceInformation();
        sendDeviceMemory();
        sendDeviceSerialNumber();

        final AtomicBoolean temperatureReset = new AtomicBoolean(true);
        maxTemperature.put(THERMOSTAT_1, 0.0d);
        maxTemperature.put(THERMOSTAT_2, 0.0d);

        new Thread(new Runnable() {
            @SneakyThrows({InterruptedException.class, IOException.class})
            @Override
            public void run() {
                while (true) {
                    if (temperatureReset.get()) {
                        // Generate a random value between 5.0°C and 45.0°C for the current temperature reading for each "Thermostat" component.
                        temperature.put(THERMOSTAT_1, BigDecimal.valueOf(random.nextDouble() * 40 + 5).setScale(1, RoundingMode.HALF_UP).doubleValue());
                        temperature.put(THERMOSTAT_2, BigDecimal.valueOf(random.nextDouble() * 40 + 5).setScale(1, RoundingMode.HALF_UP).doubleValue());
                    }

                    sendTemperatureReading(THERMOSTAT_1);
                    sendTemperatureReading(THERMOSTAT_2);

                    temperatureReset.set(temperature.get(THERMOSTAT_1) == 0 && temperature.get(THERMOSTAT_2) == 0);
                    Thread.sleep(5 * 1000);
                }
            }
        }).start();
    }

    private static boolean validateArgsForIotHubFlow()
    {
        return !(deviceConnectionString == null || deviceConnectionString.isEmpty());
    }

    private static boolean validateArgsForDpsFlow()
    {
        return !((globalEndpoint == null || globalEndpoint.isEmpty())
                && (scopeId == null || scopeId.isEmpty())
                && (registrationId == null || registrationId.isEmpty())
                && (deviceSymmetricKey == null || deviceSymmetricKey.isEmpty()));
    }

    private static void initializeAndProvisionDevice() throws ProvisioningDeviceClientException, IOException, URISyntaxException, InterruptedException {
        SecurityProviderSymmetricKey securityClientSymmetricKey = new SecurityProviderSymmetricKey(deviceSymmetricKey.getBytes(StandardCharsets.UTF_8), registrationId);
        ProvisioningDeviceClient provisioningDeviceClient;
        ProvisioningStatus provisioningStatus = new ProvisioningStatus();

        provisioningDeviceClient = ProvisioningDeviceClient.create(globalEndpoint, scopeId, provisioningProtocol, securityClientSymmetricKey);

        AdditionalData additionalData = new AdditionalData();
        additionalData.setProvisioningPayload(com.microsoft.azure.sdk.iot.provisioning.device.plugandplay.PnpHelper.createDpsPayload(MODEL_ID));

        provisioningDeviceClient.registerDevice(new ProvisioningDeviceClientRegistrationCallbackImpl(), provisioningStatus, additionalData);

        while (provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getProvisioningDeviceClientStatus() != ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_ASSIGNED)
        {
            if (provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getProvisioningDeviceClientStatus() == ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_ERROR ||
                    provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getProvisioningDeviceClientStatus() == ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_DISABLED ||
                    provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getProvisioningDeviceClientStatus() == ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_FAILED)
            {
                provisioningStatus.exception.printStackTrace();
                System.out.println("Registration error, bailing out");
                break;
            }
            System.out.println("Waiting for Provisioning Service to register");
            Thread.sleep(MAX_TIME_TO_WAIT_FOR_REGISTRATION);
        }

        ClientOptions options = new ClientOptions();
        options.setModelId(MODEL_ID);

        if (provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getProvisioningDeviceClientStatus() == ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_ASSIGNED) {
            System.out.println("IotHUb Uri : " + provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getIothubUri());
            System.out.println("Device ID : " + provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getDeviceId());

            String iotHubUri = provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getIothubUri();
            String deviceId = provisioningStatus.provisioningDeviceClientRegistrationInfoClient.getDeviceId();

            log.debug("Opening the device client.");
            deviceClient = DeviceClient.createFromSecurityProvider(iotHubUri, deviceId, securityClientSymmetricKey, IotHubClientProtocol.MQTT, options);
            deviceClient.open();
        }
    }

    /**
     * Initialize the device client instance over Mqtt protocol, setting the ModelId into ClientOptions.
     * This method also sets a connection status change callback, that will get triggered any time the device's connection status changes.
     */
    private static void initializeDeviceClient() throws URISyntaxException, IOException {
        ClientOptions options = new ClientOptions();
        options.setModelId(MODEL_ID);
        deviceClient = new DeviceClient(deviceConnectionString, protocol, options);

        deviceClient.registerConnectionStatusChangeCallback((status, statusChangeReason, throwable, callbackContext) -> {
            log.debug("Connection status change registered: status={}, reason={}", status, statusChangeReason);

            if (throwable != null) {
                log.debug("The connection status change was caused by the following Throwable: {}", throwable.getMessage());
                throwable.printStackTrace();
            }
        }, deviceClient);

        deviceClient.open();
    }

    /**
     * The callback to handle "reboot" command. This method will send a temperature update (of 0°C) over telemetry for both associated components.
     */
    private static class CommandsCallback implements DeviceCommandCallback
    {
        static class RebootPayload
        {
            int delay;
        }

        static class GetMinMaxPayload
        {
            Date since;
        }

        static class GetMinMaxResponse
        {
            public Date since;
            public double maxTemp;
            public double minTemp;
            public double avgTemp;
            public String startTime;
            public String endTime;
        }

        final String reboot = "reboot";
        final String thermostat1 = "thermostat1";
        final String thermostat2 = "thermostat2";
        final String getMaxMinReport = "getMaxMinReport";

        @SneakyThrows(InterruptedException.class)
        @Override
        public DeviceCommandResponse onDeviceCommandReceived(DeviceCommandRequest request) {

            switch (request.getComponentName()) {
                case thermostat1:
                case thermostat2:
                    switch (request.getCommandName()){
                        case getMaxMinReport:
                            if (temperatureReadings.containsKey(request.getComponentName())) {
                                GetMinMaxPayload getMinMaxPayload = request.GetPayloadAsObject(GetMinMaxPayload.class);
                                log.debug("Command: Received - component=\"{}\", generating min, max, avg temperature report since {}", request.getComponentName(), getMinMaxPayload);

                                Map<Date, Double> allReadings = temperatureReadings.get(request.getComponentName());
                                Map<Date, Double> filteredReadings = allReadings.entrySet().stream()
                                        .filter(map -> map.getKey().after(getMinMaxPayload.since))
                                        .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

                                if (!filteredReadings.isEmpty()) {

                                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                                    double maxTempToSend = Collections.max(filteredReadings.values());
                                    double minTempToSend = Collections.min(filteredReadings.values());
                                    double avgTempToSend = filteredReadings.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
                                    String startTimeToSend = sdf.format(Collections.min(filteredReadings.keySet()));
                                    String endTimeToSend = sdf.format(Collections.max(filteredReadings.keySet()));

                                    // This class will be serialized by the PayloadConvention
                                    GetMinMaxResponse responsePayload = new GetMinMaxResponse();
                                    responsePayload.since = getMinMaxPayload.since;
                                    responsePayload.maxTemp = Collections.max(filteredReadings.values());
                                    responsePayload.minTemp = Collections.min(filteredReadings.values());
                                    responsePayload.avgTemp = avgTempToSend;
                                    responsePayload.startTime = sdf.format(Collections.min(filteredReadings.keySet()));
                                    responsePayload.endTime = sdf.format(Collections.max(filteredReadings.keySet()));

                                    log.debug("Command: MaxMinReport since {}: \"maxTemp\": {}°C, \"minTemp\": {}°C, \"avgTemp\": {}°C, \"startTime\": {}, \"endTime\": {}",
                                            getMinMaxPayload.since,
                                            maxTempToSend,
                                            minTempToSend,
                                            avgTempToSend,
                                            startTimeToSend,
                                            endTimeToSend);

                                    return new DeviceCommandResponse(StatusCode.COMPLETED.value, responsePayload);
                                }

                                log.debug("Command: component=\"{}\", no relevant readings found since {}, cannot generate any report.", request.getComponentName(), getMinMaxPayload);
                                return new DeviceCommandResponse(StatusCode.NOT_FOUND.value, null);
                            }

                            log.debug("Command: component=\"{}\", no temperature readings sent yet, cannot generate any report.", request.getComponentName());
                            return new DeviceCommandResponse(StatusCode.NOT_FOUND.value, null);
                    }

                    break;
            }

            // Handle reboot command from the default component
            switch (request.getCommandName()) {
                case reboot:
                    RebootPayload rebootPayload = request.GetPayloadAsObject(RebootPayload.class);
                    log.debug("Command: Received - Rebooting thermostat (resetting temperature reading to 0°C after {} seconds).", rebootPayload);
                    Thread.sleep(rebootPayload.delay * 1000L);

                    temperature.put(THERMOSTAT_1, 0.0d);
                    temperature.put(THERMOSTAT_2, 0.0d);

                    maxTemperature.put(THERMOSTAT_1, 0.0d);
                    maxTemperature.put(THERMOSTAT_2, 0.0d);

                    temperatureReadings.clear();
                    return new DeviceCommandResponse(StatusCode.COMPLETED.value, null);
                default:
                    log.debug("Command: command=\"{}\" is not implemented, no action taken.", request.getCommandName());
                    return new DeviceCommandResponse(StatusCode.NOT_FOUND.value, null);
            }
        }
    }

    /**
     * The desired property update callback, which receives the target temperature as a desired property update,
     * and updates the current temperature value over telemetry and reported property update.
     */
    private static class TargetTemperatureUpdateCallback implements WritablePropertiesRequestsCallback
    {
        final String propertyName = "targetTemperature";
        final List<String> thermostatList = new ArrayList<String>() {{ add("thermostat1"); add("thermostat2"); }};

        @SneakyThrows({InterruptedException.class})
        public void onWritablePropertyCallbackReceived(ClientPropertyCollection propertyCollection, Object context)
        {
            for (String componentName : thermostatList)
            {

                WritablePropertyResponse targetTemperature = propertyCollection.getValueForComponent(componentName, propertyName, WritablePropertyResponse.class);

                log.debug("Property: Received - component=\"{}\", {\"{}\": {}°C}.", componentName, propertyName, targetTemperature.getValue());

                ClientPropertyCollection collection = new ClientPropertyCollection();
                targetTemperature.setAckCode(StatusCode.IN_PROGRESS.value);
                collection.putComponentProperty(componentName, propertyName, targetTemperature);

                try
                {
                    deviceClient.updateClientPropertiesAsync(collection, null, null);
                }
                catch (IOException e)
                {
                    throw new RuntimeException("IOException when sending reported property update: ", e);
                }
                log.debug("Property: Update - {\"{}\": {}°C} is {}", propertyName, targetTemperature.getValue(), StatusCode.IN_PROGRESS);

                // Update temperature in 2 steps
                double step = ((Double) targetTemperature.getValue() - temperature.get(componentName)) / 2;
                for (int i = 1; i <= 2; i++)
                {
                    temperature.put(componentName, BigDecimal.valueOf(temperature.get(componentName) + step).setScale(1, RoundingMode.HALF_UP).doubleValue());
                    Thread.sleep(5 * 1000);
                }

                targetTemperature.setAckCode(StatusCode.COMPLETED.value);
                try
                {
                    deviceClient.updateClientPropertiesAsync(collection, null, null);
                }
                catch (IOException e)
                {
                    throw new RuntimeException("IOException when sending reported property update: ", e);
                }

                log.debug("Property: Update - {\"{}\": {}°C} is {}", propertyName, temperature, StatusCode.COMPLETED);
            }
    }

    }

    // Report the property updates on "deviceInformation" component.
    private static void updateDeviceInformation() throws IOException {
        String componentName = "deviceInformation";

        ClientPropertyCollection propertyPatch = new ClientPropertyCollection();
        propertyPatch.put(componentName, new HashMap<String, Object>()
        {{
            put("manufacturer", "element15");
            put("model", "ModelIDxcdvmk");
            put("swVersion", "1.0.0");
            put("osName", "Windows 10");
            put("processorArchitecture", "64-bit");
            put("processorManufacturer", "Intel");
            put("totalStorage", 256);
            put("totalMemory", 1024);
        }});

        deviceClient.updateClientPropertiesAsync(propertyPatch, null, null);

        log.debug("Property: Update - component = \"{}\" is {}.", componentName, StatusCode.COMPLETED);
    }
    
    private static void sendDeviceMemory() {
        String telemetryName = "workingSet";

        // TODO: Environment.WorkingSet equivalent in Java
        double workingSet = 1024;

        TelemetryMessage message = new TelemetryMessage();
        message.getTelemetry().put(telemetryName, workingSet);
        deviceClient.sendTelemetryAsync(message, new MessageIotHubEventCallback(), message);
        log.debug("Telemetry: Sent - {\"{}\": {}KiB }", telemetryName, workingSet);
    }

    private static void sendDeviceSerialNumber() throws IOException {
        String propertyName = "serialNumber";

        ClientPropertyCollection propertyPatch = new ClientPropertyCollection();
        propertyPatch.put(propertyName, SERIAL_NO);

        deviceClient.updateClientPropertiesAsync(propertyPatch, null, null);
        log.debug("Property: Update - {\"{}\": {}} is {}", propertyName, SERIAL_NO, StatusCode.COMPLETED);
    }

    private static void sendTemperatureReading(String componentName) throws IOException {
        sendTemperatureTelemetry(componentName);

        double currentMaxTemp = Collections.max(temperatureReadings.get(componentName).values());
        if (currentMaxTemp > maxTemperature.get(componentName)) {
            maxTemperature.put(componentName, currentMaxTemp);
            updateMaxTemperatureSinceLastReboot(componentName);
        }
    }

    private static void sendTemperatureTelemetry(String componentName) {
        String telemetryName = "temperature";
        double currentTemperature = temperature.get(componentName);

        TelemetryMessage message = new TelemetryMessage();
        message.setComponentName(componentName);
        message.getTelemetry().put(telemetryName, currentTemperature);
        deviceClient.sendTelemetryAsync(message, new MessageIotHubEventCallback(), message);

        log.debug("Telemetry: Sent - {\"{}\": {}°C} with message Id {}.", telemetryName, currentTemperature, message.getMessageId());

        // Add the current temperature entry to the list of temperature readings.
        Map<Date, Double> currentReadings;
        if (temperatureReadings.containsKey(componentName)) {
            currentReadings = temperatureReadings.get(componentName);
        } else {
            currentReadings = new HashMap<>();
        }
        currentReadings.put(new Date(), currentTemperature);
        temperatureReadings.put(componentName, currentReadings);
    }

    private static void updateMaxTemperatureSinceLastReboot(String componentName) throws IOException {
        String propertyName = "maxTempSinceLastReboot";
        double maxTemp = maxTemperature.get(componentName);

        ClientPropertyCollection propertyPatch = new ClientPropertyCollection();
        propertyPatch.putComponentProperty(componentName, propertyName,maxTemp);

        deviceClient.updateClientPropertiesAsync(propertyPatch, null, null);
        log.debug("Property: Update - {\"{}\": {}°C} is {}.", propertyName, maxTemp, StatusCode.COMPLETED);
    }

    /**
     * The callback to be invoked in response to device twin operations in IoT Hub.
     */
    private static class TwinIotHubEventCallback implements IotHubEventCallback {

        @Override
        public void execute(IotHubStatusCode responseStatus, Object callbackContext) {
            log.debug("Property - Response from IoT Hub: {}", responseStatus.name());
        }
    }

    /**
     * The callback to be invoked in response to command invocation from IoT Hub.
     */
    private static class CommandIotHubEventCallback implements IotHubEventCallback {

        @Override
        public void execute(IotHubStatusCode responseStatus, Object callbackContext) {
            String commandName = (String) callbackContext;
            log.debug("Command - Response from IoT Hub: command name={}, status={}", commandName, responseStatus.name());
        }
    }

    /**
     * The callback to be invoked when a telemetry response is received from IoT Hub.
     */
    private static class MessageIotHubEventCallback implements IotHubEventCallback {

        @Override
        public void execute(IotHubStatusCode responseStatus, Object callbackContext) {
            Message msg = (Message) callbackContext;
            log.debug("Telemetry - Response from IoT Hub: message Id={}, status={}", msg.getMessageId(), responseStatus.name());
        }
    }

    /**
     * The callback to be invoked for a property change that is not explicitly monitored by the device.
     */
    private static class GenericPropertyUpdateCallback implements TwinPropertyCallBack {

        @Override
        public void TwinPropertyCallBack(Property property, Object context) {
            log.debug("Property - Received property unhandled by device, key={}, value={}", property.getKey(), property.getValue());
        }
    }
}
