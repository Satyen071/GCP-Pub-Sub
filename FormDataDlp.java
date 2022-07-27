package com.loblaw.dataflow.transforms;

import com.google.cloud.bigquery.*;
import com.google.cloud.kms.v1.CryptoKeyName;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.KmsEnvelopeAeadKeyManager;
import com.google.crypto.tink.integration.gcpkms.GcpKmsClient;
import com.loblaw.dataflow.options.PubSubToBigQueryOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.json.JSONObject;
import org.springframework.cloud.gcp.bigquery.core.BigQueryTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class FormDataDlp extends DoFn<Map<String, String>, Map<String, String>> {

    private final ValueProvider<String> projectId;
    private final ValueProvider<String> dataSet;
    private final ValueProvider<String> keyRing;
    private final ValueProvider<String> keyId;
    private final ValueProvider<String> location;

    private static final String JSON_DATA = "jsonData";
    private static final String DLP_CONFIG = "form_dlp_config";
    private static final String FILE_URL = "fileUrl";
    private static final String FORM_NAME = "formName";
    private static final String FORM_META_DATA = "formMetaData";
    private static final String DELETED = "DELETED";

    private static final String KEK_URI_SUFFIX = "gcp-kms://";

    public FormDataDlp(PubSubToBigQueryOptions options) {
        this.projectId = options.getProjectId();
        this.dataSet = options.getDataSet();
        this.keyId = options.getKeyId();
        this.location = options.getLocationId();
        this.keyRing = options.getKeyRing();
    }

    @ProcessElement
    public void dlpOnFormData(ProcessContext context)  {
        log.info("dlp on data started");
        BigQuery bigquery = BigQueryOptions.getDefaultInstance().getService();
        BigQueryTemplate bigQueryTemplate = new BigQueryTemplate(bigquery, dataSet.get());

        String formName = "";
        String formDataUrl = "";

        try {
            Map<String, String> receivedData = context.element();
            assert receivedData != null;
            String encryptedDataObject = Objects.requireNonNull(receivedData.get("encryptedData"));
            JSONObject jsonObject = new JSONObject(Objects.requireNonNull(receivedData.get(JSON_DATA)));
            JSONObject modifiedJson = parser(jsonObject);
            formDataUrl = receivedData.get(FILE_URL);
            List<String> cryptoHashingList = new ArrayList<>();
            List<String> redactionList = new ArrayList<>();
            List<String> maskingList = new ArrayList<>();
            ConcurrentHashMap<String, String> outputData = new ConcurrentHashMap<>();

            formName = jsonObject.getJSONObject(FORM_META_DATA).get(FORM_NAME).toString();
            modifiedJson.put(FORM_META_DATA,jsonObject.getJSONObject(FORM_META_DATA));
            TableId tableIdObject = TableId.of(dataSet.get(), DLP_CONFIG);
            TableResult tableData = bigquery.listTableData(tableIdObject, BigQuery.TableDataListOption.pageSize(100));

            if (tableData.getTotalRows() != 0) {
                cryptoHashingList = getCryptoHashingConfig(tableData, formName);
                redactionList = getRedactionConfig(tableData, formName);
                maskingList = getMaskingConfig(tableData, formName);
            }

            JSONObject encryptedFormJsonData = null;
            if (!cryptoHashingList.isEmpty())
                encryptedFormJsonData =encryptPIIFields(modifiedJson, cryptoHashingList);

            if (!redactionList.isEmpty())
                encryptedFormJsonData =encryptPIIFields(modifiedJson, redactionList);

            if (!maskingList.isEmpty())
                encryptedFormJsonData =encryptPIIFields(modifiedJson, maskingList);

            if(encryptedFormJsonData!=null && encryptedFormJsonData.length() != 0)
                outputData.put(JSON_DATA,encryptedFormJsonData.toString());
            else
                outputData.put(JSON_DATA, modifiedJson.toString());
            outputData.put(FILE_URL, receivedData.get(FILE_URL));
            outputData.put("encryptedData", encryptedDataObject);
            context.output(outputData);
        } catch (Exception exception) {
            writeDataToErrorTable(bigQueryTemplate, formName, formDataUrl, exception.toString());
        }

    }

    private List<String> getCryptoHashingConfig(TableResult tableData, String formName) {
        List<String> cryptoHashingList = new ArrayList<>();
        FieldValueList fieldValues;
        if (tableData.getTotalRows() != 0) {
            for (FieldValueList values : tableData.getValues()) {
                fieldValues = values;
                if (null != fieldValues.get(0).getValue() && fieldValues.get(0).getStringValue().equalsIgnoreCase(formName) &&
                        (null != fieldValues.get(3).getValue() && !fieldValues.get(3).getValue().toString().equalsIgnoreCase(DELETED))
                        && null != fieldValues.get(2).getValue() && null != fieldValues.get(1).getValue() &&
                        "CRYPTOHASHING".equalsIgnoreCase(fieldValues.get(2).getStringValue())) {

                    cryptoHashingList.add(fieldValues.get(1).getStringValue());
                }
            }
        }
        return cryptoHashingList;
    }

    private List<String> getRedactionConfig(TableResult tableData, String formName) {
        List<String> redactionList = new ArrayList<>();
        FieldValueList fieldValues;
        if (tableData.getTotalRows() != 0) {
            for (FieldValueList values : tableData.getValues()) {
                fieldValues = values;
                if (null != fieldValues.get(0).getValue() && fieldValues.get(0).getStringValue().equalsIgnoreCase(formName) &&
                        (null != fieldValues.get(3).getValue() && !fieldValues.get(3).getValue().toString().equalsIgnoreCase(DELETED))
                        && null != fieldValues.get(2).getValue() && null != fieldValues.get(1).getValue() &&
                        "REDACTION".equalsIgnoreCase(fieldValues.get(2).getStringValue())) {

                    redactionList.add(fieldValues.get(1).getStringValue());
                }
            }
        }
        return redactionList;
    }
    private List<String> getMaskingConfig(TableResult tableData, String formName) {
        List<String> maskingList = new ArrayList<>();
        FieldValueList fieldValues;
        if (tableData.getTotalRows() != 0) {
            for (FieldValueList values : tableData.getValues()) {
                fieldValues = values;
                if (null != fieldValues.get(0).getValue() && fieldValues.get(0).getStringValue().equalsIgnoreCase(formName) &&
                        (null != fieldValues.get(3).getValue() && !fieldValues.get(3).getValue().toString().equalsIgnoreCase(DELETED))
                        && null != fieldValues.get(2).getValue() && null != fieldValues.get(1).getValue() &&
                        "MASKING".equalsIgnoreCase(fieldValues.get(2).getStringValue())) {
                    maskingList.add(fieldValues.get(1).getStringValue());
                }
            }
        }
        return maskingList;
    }

    public void writeDataToErrorTable(BigQueryTemplate bigQueryTemplate, String formName,
                                      String formDataMap, String description) {
        try {
            Job job;
            List<Field> errorFieldList = new ArrayList<>();
            errorFieldList.add(Field.of(FORM_NAME, LegacySQLTypeName.STRING));
            errorFieldList.add(Field.of("errorFormData", LegacySQLTypeName.STRING));
            errorFieldList.add(Field.of("timeStamp", LegacySQLTypeName.STRING));
            errorFieldList.add(Field.of("description", LegacySQLTypeName.STRING));
            Schema schema = Schema.of(errorFieldList);
            ConcurrentHashMap<String, String> keyValue = new ConcurrentHashMap<>();
            keyValue.put(FORM_NAME, formName);
            keyValue.put("errorFormData", formDataMap);
            keyValue.put("timeStamp", java.sql.Timestamp.from(Instant.now()).toString());
            keyValue.put("description", description);
            JSONObject errorData = new JSONObject(keyValue);
            log.info("Writing the data to the error table from DLP step");
            job = bigQueryTemplate.writeDataToTable("errorDataTable",
                    new ByteArrayInputStream(errorData.toString().getBytes()), FormatOptions.json(), schema).get();
            job = job.waitFor();
            if (job.isDone()) {
                log.info("dataWritten in the error table");
            } else {
                log.info("BigQuery was unable to load into the table due to an error:" + job.getStatus().getError());
            }
        } catch (InterruptedException e) {
            log.error("interrupted exception occurred:{}", e.getMessage());
            Thread.currentThread().interrupt();
        }
        catch (Exception exception) {
            log.error("Unable to write into error table exception message DLP: {} formName: {} description: {}", exception.getMessage(),
                    formName, description);
        }
    }
    JSONObject parser(JSONObject jsonObject) {
        Map<String, String> keyValue = new HashMap<>();
        jsonObject.keys().forEachRemaining(key -> {
            if(!key.equalsIgnoreCase(FORM_META_DATA)){
                keyValue.put(key,String.valueOf(jsonObject.get(key)));
            }
        });
        return new JSONObject(keyValue);
    }

    private JSONObject encryptPIIFields(JSONObject jsonObject, List<String> cryptoHashingList) {
        try {
            log.info("Encrypting PII Data");
            final Aead aead;
            AeadConfig.register();
            CryptoKeyName keyVersionName = CryptoKeyName.of(projectId.get(), location.get(), keyRing.get(), keyId.get());
            GcpKmsClient.register(Optional.of(KEK_URI_SUFFIX + keyVersionName.toString()), Optional.empty());
            KeysetHandle handle = KeysetHandle.generateNew(KmsEnvelopeAeadKeyManager
                    .createKeyTemplate(KEK_URI_SUFFIX + keyVersionName, KeyTemplates.get("AES256_GCM")));
            aead = handle.getPrimitive(Aead.class);
            if (jsonObject.length() != 0) {
               jsonObject.keySet()
                       .stream()
                       .filter(key -> !key.equalsIgnoreCase(FORM_META_DATA) && cryptoHashingList.contains(key))
                       .forEach(key -> {
                           try {
                               byte[] encryptedValue = aead.encrypt(jsonObject.getString(key).getBytes(StandardCharsets.UTF_8), null);
                               jsonObject.put(key, Base64.getEncoder().encodeToString(encryptedValue));
                           } catch (GeneralSecurityException e) {
                               log.info("Unable to de-identify fields");
                           }
                       });
            }
            log.info("Data encrypted");
            return jsonObject;
        }catch (Exception exception){
            log.info("Error occurred:{}",exception.getMessage());
        }
        return new JSONObject();
    }
}
