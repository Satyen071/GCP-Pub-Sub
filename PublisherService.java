package com.loblaw.ingestionservice.service;


import com.google.api.core.ApiFuture;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.KmsEnvelopeAeadKeyManager;
import com.google.crypto.tink.integration.gcpkms.GcpKmsClient;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import com.loblaw.ingestionservice.configuration.EncryptionConfig;
import com.loblaw.ingestionservice.configuration.PubSubConfig;
import com.loblaw.ingestionservice.dto.FormInputData;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.threeten.bp.Duration;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Slf4j
public class PublisherService {

    private Publisher publisher;
    @Inject
    private PubSubConfig pubSubConfig;
    @Inject
    private EncryptionConfig encryptionConfig;
    private static final String KEK_URI_SUFFIX = "gcp-kms://";
    private static final String FORM_DATA = "formData";

    public void messagePublisher(FormInputData formInputData) {

        JSONObject formJsonData = new JSONObject(formInputData);
        JSONObject updatedJsonData = removeFields(formJsonData);
        log.info("Publishing data to topic: {}", pubSubConfig.getTopicId());
        TopicName topicName = TopicName.of(pubSubConfig.getProjectId(), pubSubConfig.getTopicId());
        Aead aead;

        try {
            AeadConfig.register();
            CryptoKeyName keyVersionName = CryptoKeyName.of(pubSubConfig.getProjectId(),
                    encryptionConfig.getLocationId(), encryptionConfig.getKeyRing(), encryptionConfig.getKeyId());

            GcpKmsClient.register(Optional.of(KEK_URI_SUFFIX + keyVersionName.toString()),
                    Optional.of(pubSubConfig.getCredential()));

            KeysetHandle handle = KeysetHandle.generateNew(KmsEnvelopeAeadKeyManager
                    .createKeyTemplate(KEK_URI_SUFFIX + keyVersionName, KeyTemplates.get("AES256_GCM")));
            aead = handle.getPrimitive(Aead.class);
            byte[] encrypt = aead.encrypt(updatedJsonData.toString().getBytes(StandardCharsets.UTF_8), null);
            log.info("Ciphertext created ");
            Duration initialRetryDelay = Duration.ofMillis(100);
            double retryDelayMultiplier = 2.0;
            Duration maxRetryDelay = Duration.ofSeconds(60);
            Duration initialRpcTimeout = Duration.ofSeconds(1);
            double rpcTimeoutMultiplier = 1.0;
            Duration maxRpcTimeout = Duration.ofSeconds(600);
            Duration totalTimeout = Duration.ofSeconds(600);

            RetrySettings retrySettings =
                    RetrySettings.newBuilder().setInitialRetryDelay(initialRetryDelay)
                            .setRetryDelayMultiplier(retryDelayMultiplier).setMaxRetryDelay(maxRetryDelay)
                            .setInitialRpcTimeout(initialRpcTimeout).setRpcTimeoutMultiplier(rpcTimeoutMultiplier)
                            .setMaxRpcTimeout(maxRpcTimeout).setTotalTimeout(totalTimeout)
                            .build();

            publisher = Publisher.newBuilder(topicName).setRetrySettings(retrySettings).build();
            PubsubMessage pubsubMessage = PubsubMessage.newBuilder().setData(ByteString.copyFrom(encrypt)).build();
            ApiFuture<String> publishedMessage = publisher.publish(pubsubMessage);
            log.info("Message id generated:{}", publishedMessage.get());
        } catch (InvalidArgumentException | StatusRuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Something went wrong: {}", e.getMessage());
        }
    }

    public JSONObject removeFields(JSONObject unFlattened) {
        if ((unFlattened.getJSONObject(FORM_DATA).length()!=0)) {
            JSONObject updatedFormDataJson = unFlattened.getJSONObject(FORM_DATA);
            try (InputStream input = PublisherService.class.getResourceAsStream("/fields.properties")) {
                Properties properties = new Properties();
                properties.load(input);
                List<String> keys = new ArrayList<>();
                List<String> data = new ArrayList<>(Arrays.asList(properties.get("fields").toString().split(",")));
                for (String d : data) {
                    unFlattened.getJSONObject(FORM_DATA).keys().forEachRemaining(key -> {
                        if (key.toLowerCase().startsWith(d.toLowerCase())) {
                            keys.add(key);
                        }
                    });
                }
                keys.forEach(updatedFormDataJson.keySet()::remove);
                return unFlattened;
            } catch (IOException e) {
                log.error("File Not Found: {}", e.getMessage());
            }
        } else {
            log.error("formData is empty");
        }
        return new JSONObject();
    }

}
