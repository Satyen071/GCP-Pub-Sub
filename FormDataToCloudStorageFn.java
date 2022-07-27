package com.loblaw.dataflow.transforms;

import com.google.cloud.bigquery.*;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.KmsEnvelopeAeadKeyManager;
import com.google.crypto.tink.integration.gcpkms.GcpKmsClient;
import com.google.protobuf.ByteString;
import com.loblaw.dataflow.options.PubSubToBigQueryOptions;
import com.loblaw.dataflow.validation.ValidateRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.json.JSONObject;
import org.springframework.cloud.gcp.bigquery.core.BigQueryTemplate;

import java.io.ByteArrayInputStream;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Slf4j
public class FormDataToCloudStorageFn extends DoFn<PubsubMessage, Map<String, String>> {

    ValueProvider<String> projectId;
    ValueProvider<String> keyRing;
    ValueProvider<String> keyId;
    ValueProvider<String> location;
    ValueProvider<String> bucketName;

    ValueProvider<String> dataSet;

    private static final String FORM_NAME = "formName";

    private static final String FILE_URL = "fileUrl";

    private static final String KEK_URI_SUFFIX = "gcp-kms://";


    public FormDataToCloudStorageFn(PubSubToBigQueryOptions options) {
        this.keyId = options.getKeyId();
        this.projectId = options.getProjectId();
        this.location = options.getLocationId();
        this.keyRing = options.getKeyRing();
        this.bucketName = options.getBucketName();
        this.dataSet = options.getDataSet();
    }

    @ProcessElement
    public void process(ProcessContext context) {
        log.info("start decrypting data");
        String baseUrl = "https://storage.cloud.google.com/";
        String fileUrl = "";
        String formName = "";
        Aead aead;
        try {

            if (null != context.element() && null != Objects.requireNonNull(context.element()).getAttributeMap()) {
                for (String key : Objects.requireNonNull(Objects.requireNonNull(context.element()).getAttributeMap()).keySet()) {
                    log.info("Pub Sub Attribute Key: {}  == {}", key,
                            Objects.requireNonNull(Objects.requireNonNull(context.element())
                                    .getAttributeMap()).get(key));
                }
            }
            AeadConfig.register();
            ByteString data = ByteString.copyFrom(Objects.requireNonNull(context.element()).getPayload());
            CryptoKeyName keyVersionName = CryptoKeyName.of(projectId.get(), location.get(), keyRing.get(), keyId.get());
            GcpKmsClient.register(Optional.of(KEK_URI_SUFFIX + keyVersionName.toString()), Optional.empty());
            KeysetHandle handle = KeysetHandle.generateNew(KmsEnvelopeAeadKeyManager
                    .createKeyTemplate(KEK_URI_SUFFIX + keyVersionName, KeyTemplates.get("AES256_GCM")));
            aead = handle.getPrimitive(Aead.class);
            byte[] decrypt = aead.decrypt(data.toByteArray(), null);
            log.info("Decrypted Data");
            JSONObject jsonObject = new JSONObject(new String(decrypt));
            JSONObject formMetaData = jsonObject.getJSONObject("formMetaData");

            formName = String.valueOf(formMetaData.get(FORM_NAME));
            String organization = String.valueOf(formMetaData.get("organization"));
            String team = String.valueOf(formMetaData.get("team"));
            String version = String.valueOf(formMetaData.get("version"));

            ValidateRequest validateRequest = new ValidateRequest();
            validateRequest.validateRequestHeaders(organization, team, formName, version);
            validateRequest.validateRequestBody(jsonObject.getJSONObject("formData"));

            JSONObject pureJsonFormat = new JSONObject(jsonObject.getJSONObject("formData").toString());
            pureJsonFormat.put("formMetaData", formMetaData);

            String fileName = fileName(formName);
            log.info("fileName is : {}", fileName);

            String folderName = folderName();
            HttpTransportOptions transportOptions = StorageOptions.getDefaultHttpTransportOptions();
            transportOptions = transportOptions.toBuilder().setConnectTimeout(60000).setReadTimeout(60000)
                    .build();
            Storage storage = StorageOptions.newBuilder().setTransportOptions(transportOptions)
                    .setProjectId(projectId.get()).build().getService();
            BlobId blobId = BlobId.of(Objects.requireNonNull(bucketName.get()), formName + "/" + folderName + fileName + ".json");
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();
            byte[] content = data.toByteArray();
            storage.createFrom(blobInfo, new ByteArrayInputStream(content));
            fileUrl = baseUrl + bucketName.get() + "/" + formName + "/" + folderName + fileName + ".json";

            log.info("Form Stored Successfully for form : {}", formName);
            Map<String, String> outputData = new HashMap<>();
            outputData.put("jsonData", pureJsonFormat.toString());
            outputData.put(FILE_URL, fileUrl);
            outputData.put("encryptedData", data.toString());
            context.output(outputData);
        } catch (Exception exception) {
            log.error("Unable to save data in cloud storage : " + exception.getMessage());
            BigQuery bigquery = BigQueryOptions.getDefaultInstance().getService();
            BigQueryTemplate bigQueryTemplate = new BigQueryTemplate(bigquery, dataSet.get());
            writeDataToErrorTable(bigQueryTemplate, formName, fileUrl, exception.getMessage());
        }
    }

    public String fileName(String formName) {
        String fileName;
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        int hours = calendar.get(Calendar.HOUR_OF_DAY);
        int minutes = calendar.get(Calendar.MINUTE);
        int seconds = calendar.get(Calendar.SECOND);
        SecureRandom random = new SecureRandom();
        fileName = formName + hours + "_" + minutes + "_" + seconds + "_" + random.nextInt(9999);
        return fileName;
    }

    public String folderName() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int month = calendar.get(Calendar.MONTH) + 1;
        Integer year = calendar.get(Calendar.YEAR);
        String date = year + "-" + (month < 10 ? ("0" + month) : (month)) + "/" + day;
        return date + "/";
    }

    public void writeDataToErrorTable(BigQueryTemplate bigQueryTemplate, String formName, String formDataMap, String description) {
        try {
            Job job;
            List<Field> errorFieldList = new ArrayList<>();
            errorFieldList.add(Field.of(FORM_NAME, LegacySQLTypeName.STRING));
            errorFieldList.add(Field.of("errorFormData", LegacySQLTypeName.STRING));
            errorFieldList.add(Field.of("timeStamp", LegacySQLTypeName.STRING));
            errorFieldList.add(Field.of("description", LegacySQLTypeName.STRING));
            Schema schema = Schema.of(errorFieldList);
            Map<String, String> keyValue = new HashMap<>();
            keyValue.put(FORM_NAME, formName);
            keyValue.put("errorFormData", formDataMap);
            keyValue.put("timeStamp", java.sql.Timestamp.from(Instant.now()).toString());
            keyValue.put("description", description);
            JSONObject errorData = new JSONObject(keyValue);
            log.info("Writing the data to the error table");
            job = bigQueryTemplate.writeDataToTable("errorDataTable",
                    new ByteArrayInputStream(errorData.toString().getBytes()), FormatOptions.json(), schema).get();
            job = job.waitFor();
            if (job.isDone()) {
                log.info("dataWritten in the error table");
            } else {
                log.info("BigQuery was unable to load into the table due to an error:" + job.getStatus().getError());
            }
        } catch (InterruptedException e) {
            log.error("Interrupted exception occurred while storing in cloud storage");
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.error("Unable to write into error table exception message CLOUD: {} formName: {} description: {}", exception.getMessage(), formName, description);
        }

    }
}
