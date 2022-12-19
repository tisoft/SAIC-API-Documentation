package net.heberling.ismart.cli;

import com.owlike.genson.Context;
import com.owlike.genson.Converter;
import com.owlike.genson.Genson;
import com.owlike.genson.GensonBuilder;
import com.owlike.genson.convert.ChainedFactory;
import com.owlike.genson.reflect.TypeUtil;
import com.owlike.genson.stream.ObjectReader;
import com.owlike.genson.stream.ObjectWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.heberling.ismart.asn1.AbstractMessage;
import net.heberling.ismart.asn1.AbstractMessageCoder;
import net.heberling.ismart.asn1.Anonymizer;
import net.heberling.ismart.asn1.v1_1.Message;
import net.heberling.ismart.asn1.v1_1.MessageCoder;
import net.heberling.ismart.asn1.v1_1.entity.MP_UserLoggingInReq;
import net.heberling.ismart.asn1.v1_1.entity.MP_UserLoggingInResp;
import net.heberling.ismart.asn1.v1_1.entity.VinInfo;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVCReq;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVCStatus25857;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVMVehicleClassifiedStatusReq;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVMVehicleClassifiedStatusResp25857;
import net.heberling.ismart.asn1.v2_1.entity.RvcReqParam;
import org.apache.hc.client5.http.ClientProtocolException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.bn.annotations.ASN1Enum;
import org.bn.annotations.ASN1Sequence;
import org.bn.coders.IASN1PreparedElement;

public class GetData {
    public static void main(String[] args) throws IOException {
        MessageCoder<MP_UserLoggingInReq> loginRequestMessageCoder =
                new MessageCoder<>(MP_UserLoggingInReq.class);

        MP_UserLoggingInReq applicationData = new MP_UserLoggingInReq();
        applicationData.setPassword(args[1]);
        Message<MP_UserLoggingInReq> loginRequestMessage =
                loginRequestMessageCoder.initializeMessage(
                        "0000000000000000000000000000000000000000000000000#"
                                        .substring(args[0].length())
                                + args[0],
                        null,
                        null,
                        "501",
                        513,
                        1,
                        applicationData);

        String loginRequest = loginRequestMessageCoder.encodeRequest(loginRequestMessage);

        System.out.println(toJSON(anonymized(loginRequestMessageCoder, loginRequestMessage)));

        String loginResponse = sendRequest(loginRequest, "https://tap-eu.soimt.com/TAP.Web/ota.mp");

        Message<MP_UserLoggingInResp> loginResponseMessage =
                new MessageCoder<>(MP_UserLoggingInResp.class).decodeResponse(loginResponse);

        System.out.println(
                toJSON(
                        anonymized(
                                new MessageCoder<>(MP_UserLoggingInResp.class),
                                loginResponseMessage)));
        for (VinInfo vin : loginResponseMessage.getApplicationData().getVinList()) {

            fetchVehicleClassifiedStatus(loginResponseMessage, vin);
            // 5 front, 2, a/c, 0 disable
            // sendACCommand(loginResponseMessage, vin, (byte) 5, (byte) 8);
            // sendACCommand(loginResponseMessage, vin, (byte)0, (byte)0);
            sendLockCommand(loginResponseMessage, vin, false);
        }
    }

    private static void sendACCommand(
            Message<MP_UserLoggingInResp> loginResponseMessage,
            VinInfo vin,
            byte command,
            byte temperature)
            throws IOException {
        net.heberling.ismart.asn1.v2_1.MessageCoder<OTA_RVCReq> otaRvcReqMessageCoder =
                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCReq.class);

        OTA_RVCReq req = new OTA_RVCReq();
        req.setRvcReqType(new byte[] {6});
        List<RvcReqParam> params = new ArrayList<>();
        req.setRvcParams(params);
        RvcReqParam param = new RvcReqParam();
        param.setParamId(19);
        param.setParamValue(new byte[] {command});
        params.add(param);
        param = new RvcReqParam();
        param.setParamId(20);
        param.setParamValue(new byte[] {temperature});
        params.add(param);
        param = new RvcReqParam();
        param.setParamId(255);
        param.setParamValue(new byte[] {0});
        params.add(param);

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVCReq> enableACRequest =
                otaRvcReqMessageCoder.initializeMessage(
                        loginResponseMessage.getBody().getUid(),
                        loginResponseMessage.getApplicationData().getToken(),
                        vin.getVin(),
                        "510",
                        25857,
                        1,
                        req);
        fillReserved(enableACRequest.getReserved());

        String enableACRequestMessage = otaRvcReqMessageCoder.encodeRequest(enableACRequest);

        System.out.println(toJSON(anonymized(otaRvcReqMessageCoder, enableACRequest)));

        String enableACResponseMessage =
                sendRequest(enableACRequestMessage, "https://tap-eu.soimt.com/TAP.Web/ota.mpv21");

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVCStatus25857> enableACResponse =
                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCStatus25857.class)
                        .decodeResponse(enableACResponseMessage);

        System.out.println(
                toJSON(
                        anonymized(
                                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                        OTA_RVCStatus25857.class),
                                enableACResponse)));

        // ... use that to request the data again, until we have it
        // TODO: check for real errors (result!=0 and/or errorMessagePresent)
        while (enableACResponse.getApplicationData() == null) {

            fillReserved(enableACRequest.getReserved());

            if (enableACResponse.getBody().getResult() == 0) {
                // we get an eventId back...
                enableACRequest.getBody().setEventID(enableACResponse.getBody().getEventID());
            } else {
                // try a fresh eventId
                enableACRequest.getBody().setEventID(0);
            }

            System.out.println(toJSON(anonymized(otaRvcReqMessageCoder, enableACRequest)));

            enableACRequestMessage = otaRvcReqMessageCoder.encodeRequest(enableACRequest);

            enableACResponseMessage =
                    sendRequest(
                            enableACRequestMessage, "https://tap-eu.soimt.com/TAP.Web/ota.mpv21");

            enableACResponse =
                    new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCStatus25857.class)
                            .decodeResponse(enableACResponseMessage);

            System.out.println(
                    toJSON(
                            anonymized(
                                    new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                            OTA_RVCStatus25857.class),
                                    enableACResponse)));
        }
    }
    private static void sendLockCommand(
            Message<MP_UserLoggingInResp> loginResponseMessage,
            VinInfo vin,
            boolean lock)
            throws IOException {
        net.heberling.ismart.asn1.v2_1.MessageCoder<OTA_RVCReq> otaRvcReqMessageCoder =
                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCReq.class);

        OTA_RVCReq req = new OTA_RVCReq();
        req.setRvcReqType(new byte[] {6});
        List<RvcReqParam> params = new ArrayList<>();
        req.setRvcParams(params);
        RvcReqParam param = new RvcReqParam();
        param.setParamId(19);
        param.setParamValue(new byte[] {command});
        params.add(param);
        param = new RvcReqParam();
        param.setParamId(20);
        param.setParamValue(new byte[] {temperature});
        params.add(param);
        param = new RvcReqParam();
        param.setParamId(255);
        param.setParamValue(new byte[] {0});
        params.add(param);

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVCReq> enableACRequest =
                otaRvcReqMessageCoder.initializeMessage(
                        loginResponseMessage.getBody().getUid(),
                        loginResponseMessage.getApplicationData().getToken(),
                        vin.getVin(),
                        "510",
                        25857,
                        1,
                        req);
        fillReserved(enableACRequest.getReserved());

        String enableACRequestMessage = otaRvcReqMessageCoder.encodeRequest(enableACRequest);

        System.out.println(toJSON(anonymized(otaRvcReqMessageCoder, enableACRequest)));

        String enableACResponseMessage =
                sendRequest(enableACRequestMessage, "https://tap-eu.soimt.com/TAP.Web/ota.mpv21");

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVCStatus25857> enableACResponse =
                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCStatus25857.class)
                        .decodeResponse(enableACResponseMessage);

        System.out.println(
                toJSON(
                        anonymized(
                                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                        OTA_RVCStatus25857.class),
                                enableACResponse)));

        // ... use that to request the data again, until we have it
        // TODO: check for real errors (result!=0 and/or errorMessagePresent)
        while (enableACResponse.getApplicationData() == null) {

            fillReserved(enableACRequest.getReserved());

            if (enableACResponse.getBody().getResult() == 0) {
                // we get an eventId back...
                enableACRequest.getBody().setEventID(enableACResponse.getBody().getEventID());
            } else {
                // try a fresh eventId
                enableACRequest.getBody().setEventID(0);
            }

            System.out.println(toJSON(anonymized(otaRvcReqMessageCoder, enableACRequest)));

            enableACRequestMessage = otaRvcReqMessageCoder.encodeRequest(enableACRequest);

            enableACResponseMessage =
                    sendRequest(
                            enableACRequestMessage, "https://tap-eu.soimt.com/TAP.Web/ota.mpv21");

            enableACResponse =
                    new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCStatus25857.class)
                            .decodeResponse(enableACResponseMessage);

            System.out.println(
                    toJSON(
                            anonymized(
                                    new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                            OTA_RVCStatus25857.class),
                                    enableACResponse)));
        }
    }
    private static void fetchVehicleClassifiedStatus(
            Message<MP_UserLoggingInResp> loginResponseMessage, VinInfo vin) throws IOException {
        net.heberling.ismart.asn1.v2_1.MessageCoder<OTA_RVMVehicleClassifiedStatusReq>
                otaRvmVehicleClassifiedStatusReqMessageCoder =
                        new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                OTA_RVMVehicleClassifiedStatusReq.class);

        OTA_RVMVehicleClassifiedStatusReq req = new OTA_RVMVehicleClassifiedStatusReq();
        req.setVehStatusReqType(0);
        req.setRvmClassifiedType(new byte[] {1});
        net.heberling.ismart.asn1.v2_1.Message<OTA_RVMVehicleClassifiedStatusReq>
                chargingStatusMessage =
                        otaRvmVehicleClassifiedStatusReqMessageCoder.initializeMessage(
                                loginResponseMessage.getBody().getUid(),
                                loginResponseMessage.getApplicationData().getToken(),
                                vin.getVin(),
                                "511",
                                25857,
                                11,
                                req);

        chargingStatusMessage.getHeader().setProtocolVersion(32);

        String chargingStatusRequestMessage =
                otaRvmVehicleClassifiedStatusReqMessageCoder.encodeRequest(chargingStatusMessage);

        System.out.println(
                toJSON(
                        anonymized(
                                otaRvmVehicleClassifiedStatusReqMessageCoder,
                                chargingStatusMessage)));

        String chargingStatusResponse =
                sendRequest(
                        chargingStatusRequestMessage, "https://tap-eu.soimt.com/TAP.Web/ota.mpv21");

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVMVehicleClassifiedStatusResp25857>
                chargingStatusResponseMessage =
                        new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                        OTA_RVMVehicleClassifiedStatusResp25857.class)
                                .decodeResponse(chargingStatusResponse);

        System.out.println(
                toJSON(
                        anonymized(
                                new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                        OTA_RVMVehicleClassifiedStatusResp25857.class),
                                chargingStatusResponseMessage)));

        // ... use that to request the data again, until we have it
        // TODO: check for real errors (result!=0 and/or errorMessagePresent)
        while (chargingStatusResponseMessage.getApplicationData() == null) {

            fillReserved(chargingStatusMessage.getReserved());

            if (chargingStatusResponseMessage.getBody().getResult() == 0) {
                // we get an eventId back...
                chargingStatusMessage
                        .getBody()
                        .setEventID(chargingStatusResponseMessage.getBody().getEventID());
            } else {
                // try a fresh eventId
                chargingStatusMessage.getBody().setEventID(0);
            }

            System.out.println(
                    toJSON(
                            anonymized(
                                    otaRvmVehicleClassifiedStatusReqMessageCoder,
                                    chargingStatusMessage)));

            chargingStatusRequestMessage =
                    otaRvmVehicleClassifiedStatusReqMessageCoder.encodeRequest(
                            chargingStatusMessage);

            chargingStatusResponse =
                    sendRequest(
                            chargingStatusRequestMessage,
                            "https://tap-eu.soimt.com/TAP.Web/ota.mpv21");

            chargingStatusResponseMessage =
                    new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                    OTA_RVMVehicleClassifiedStatusResp25857.class)
                            .decodeResponse(chargingStatusResponse);

            System.out.println(
                    toJSON(
                            anonymized(
                                    new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                                            OTA_RVMVehicleClassifiedStatusResp25857.class),
                                    chargingStatusResponseMessage)));
        }
    }

    private static <
                    H extends IASN1PreparedElement,
                    B extends IASN1PreparedElement,
                    E extends IASN1PreparedElement,
                    M extends AbstractMessage<H, B, E>>
            M anonymized(AbstractMessageCoder<H, B, E, M> coder, M message) {
        M messageCopy = coder.decodeResponse(coder.encodeRequest(message));
        Anonymizer.anonymize(messageCopy);
        return messageCopy;
    }

    private static void fillReserved(byte[] reserved) {
        System.arraycopy(
                ((new Random(System.currentTimeMillis())).nextLong() + "1111111111111111")
                        .getBytes(),
                0,
                reserved,
                0,
                16);
    }

    private static String sendRequest(String request, String endpoint) throws IOException {
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            HttpPost httppost = new HttpPost(endpoint);
            // Request parameters and other properties.
            httppost.setEntity(new StringEntity(request, ContentType.TEXT_HTML));

            // Execute and get the response.
            // Create a custom response handler
            HttpClientResponseHandler<String> responseHandler =
                    response -> {
                        final int status = response.getCode();
                        if (status >= HttpStatus.SC_SUCCESS && status < HttpStatus.SC_REDIRECTION) {
                            final HttpEntity entity = response.getEntity();
                            try {
                                return entity != null ? EntityUtils.toString(entity) : null;
                            } catch (final ParseException ex) {
                                throw new ClientProtocolException(ex);
                            }
                        } else {
                            throw new ClientProtocolException(
                                    "Unexpected response status: " + status);
                        }
                    };
            return httpclient.execute(httppost, responseHandler);
        }
    }

    public static <
                    H extends IASN1PreparedElement,
                    B extends IASN1PreparedElement,
                    E extends IASN1PreparedElement,
                    M extends AbstractMessage<H, B, E>>
            String toJSON(M message) {
        // TODO: make sure this corresponds to the JER ASN.1 serialisation format
        final ChainedFactory chain =
                new ChainedFactory() {
                    @Override
                    protected Converter<?> create(
                            Type type, Genson genson, Converter<?> nextConverter) {
                        return new Converter<>() {
                            @Override
                            public void serialize(Object object, ObjectWriter writer, Context ctx)
                                    throws Exception {
                                if (object != null) {
                                    writer.beginNextObjectMetadata();
                                    if (object.getClass().isAnnotationPresent(ASN1Enum.class)) {
                                        writer.writeMetadata(
                                                "ASN1Type",
                                                object.getClass()
                                                        .getAnnotation(ASN1Enum.class)
                                                        .name());
                                    } else if (object.getClass()
                                            .isAnnotationPresent(ASN1Sequence.class)) {
                                        writer.writeMetadata(
                                                "ASN1Type",
                                                object.getClass()
                                                        .getAnnotation(ASN1Sequence.class)
                                                        .name());
                                    }
                                }

                                @SuppressWarnings("unchecked")
                                Converter<Object> n = (Converter<Object>) nextConverter;
                                if (!(writer instanceof UTF8StringObjectWriter)) {
                                    writer = new UTF8StringObjectWriter(writer);
                                }
                                n.serialize(object, writer, ctx);
                            }

                            @Override
                            public Object deserialize(ObjectReader reader, Context ctx)
                                    throws Exception {
                                return nextConverter.deserialize(reader, ctx);
                            }
                        };
                    }
                };
        chain.withNext(
                new ChainedFactory() {
                    @Override
                    protected Converter<?> create(
                            Type type, Genson genson, Converter<?> converter) {
                        final Class<?> clazz = TypeUtil.getRawClass(type);
                        if (clazz.isAnnotationPresent(ASN1Enum.class)) {

                            return new Converter<>() {
                                @Override
                                public void serialize(
                                        Object o, ObjectWriter objectWriter, Context context)
                                        throws Exception {
                                    Method getValue = clazz.getMethod("getValue");
                                    Object value = getValue.invoke(o);
                                    if (value == null) {
                                        objectWriter.writeNull();
                                    } else {
                                        objectWriter.writeString(String.valueOf(value));
                                    }
                                }

                                @Override
                                public Object deserialize(
                                        ObjectReader objectReader, Context context)
                                        throws Exception {
                                    throw new UnsupportedOperationException("not implemented yet");
                                }
                            };
                        } else {

                            return converter;
                        }
                    }
                });
        return new GensonBuilder()
                .useIndentation(true)
                .useRuntimeType(true)
                .exclude("preparedData")
                .withConverterFactory(chain)
                .create()
                .serialize(message);
    }
}
