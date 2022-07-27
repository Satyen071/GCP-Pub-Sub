package com.loblaw.dataflow.transforms;

import com.github.wnameless.json.flattener.JsonFlattener;
import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.bigquery.*;
import com.google.cloud.bigquery.storage.v1.*;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import com.loblaw.dataflow.code.ErrorCode;
import com.loblaw.dataflow.exception.ExceptionFactory;
import com.loblaw.dataflow.options.PubSubToBigQueryOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.cloud.gcp.bigquery.core.BigQueryTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
public class FormDataToBigQuery extends DoFn<Map<String, String>, TableRow> {
    ValueProvider<String> projectId;
    ValueProvider<String> dataSet;
    ValueProvider<String> deadLetterTopicId;
    int firstRetryWaitTime;
    int secondRetryWaitTime;
    int thirdRetryWaitTime;
    private static final String FILE_URL = "fileUrl";
    private static final String FORM_META_DATA = "formMetaData";
    private static final String FORM_NAME = "formName";
    private static final String BQ_THRESHOLD_ERROR = "Exceeded rate limits: too many table update operations for this table";
    private static final int MAX_RETRY = 3;

    public FormDataToBigQuery(PubSubToBigQueryOptions options) {
        this.projectId = options.getProjectId();
        this.dataSet = options.getDataSet();
        this.deadLetterTopicId = options.getDeadLetterTopicId();
    }

    @ProcessElement
    public void processFormDataToBigQuery(ProcessContext context) {
        BigQuery bigquery = BigQueryOptions.getDefaultInstance().getService();
        BigQueryTemplate bigQueryTemplate = new BigQueryTemplate(bigquery, dataSet.get());
        String tableName = "";
        Map<String, String> newFormData = new HashMap<>();
        try {
            Map<String, String> receivedData = context.element();
            assert receivedData != null;
            JSONObject jsonObject = new JSONObject(Objects.requireNonNull(receivedData.get("jsonData")));
            String encryptedData = Objects.requireNonNull(receivedData.get("encryptedData"));
            String result = JsonFlattener.flatten(jsonObject.toString());
            JSONObject modifiedData = new JSONObject(result);
            List<Field> newFieldList = new ArrayList<>();
            Map<String, String> keyValue = new HashMap<>();
            modifiedData.keys().forEachRemaining(key -> {
                String newKey1 = key.replace("[", "")
                        .replace("]", "")
                        .replace("{", "")
                        .replace("}", "")
                        .replace(".", "_")
                        .replace("\"", "")
                        .replace("-", "_")
                        .replace(" ", "");
                keyValue.put(newKey1, modifiedData.get(key).toString());
                newFieldList.add(Field.of(newKey1, LegacySQLTypeName.STRING));
            });

            log.info("new size of fields after removing the redundant fields:{}", newFieldList.size());
            Schema newSchema = Schema.of(newFieldList);
            JSONObject keyValueMap = new JSONObject(keyValue);
            newFormData.put("formData", keyValueMap.toString());
            newFormData.put(FILE_URL, receivedData.get(FILE_URL));
            tableName = jsonObject.getJSONObject(FORM_META_DATA).get(FORM_NAME).toString();
            Table formTable = bigquery.getTable(TableId.of(dataSet.get(), tableName));
            if (Objects.nonNull(formTable) && formTable.exists()) {
                log.info("writing to the bigquery table:{}", tableName);
                Table table = bigquery.getTable(dataSet.get(), tableName);
                Schema existingSchema = table.getDefinition().getSchema();
                assert existingSchema != null;
                FieldList fields = existingSchema.getFields();
                log.info("existing Schema size:{} json data field size:{}", fields.size(), newFieldList.size());
                Map<Boolean, Schema> updatedSchema = findDifferentColumns(existingSchema, newSchema);
                Map.Entry<Boolean, Schema> entry = updatedSchema.entrySet().iterator().next();
                if (Boolean.TRUE.equals(entry.getKey())) {
                    log.info("Existing table has new column");
                    Table updatedTable = table.toBuilder().setDefinition(StandardTableDefinition.of(entry.getValue())).build();
                    updatedTable.update();
                    Thread.sleep(7000);
                    streamWriteTOBigQueryWithRetries(newFormData, tableName, encryptedData);
                } else {
                    log.info("No new column found in the data");
                    streamWriteTOBigQueryWithRetries(newFormData, tableName, encryptedData);
                }

            } else {
                log.info("Writing data to a new table");
                TableId tableDesc = TableId.of(dataSet.get(), tableName);
                TableDefinition tableDefinition = StandardTableDefinition.of(newSchema);
                TableInfo tableInfo = TableInfo.newBuilder(tableDesc, tableDefinition).build();
                log.info("creating new table with name:{}", tableName);
                bigquery.create(tableInfo);
                log.info("writing form data to bigquery table:{}", tableName);
                streamWriteTOBigQueryWithRetries(newFormData, tableName, encryptedData);
            }
        } catch (Exception exception) {
            log.error("Exception error occurred while processing FormDataToBigQuery {}", exception.getMessage());
            exception.printStackTrace();
            writeDataToErrorTable(bigQueryTemplate, tableName, newFormData.get(FILE_URL), exception.toString());
        }

    }


    private boolean streamFormDataToBigQueryTable(Map<String, String> formDataMap, String tableName) {

        JSONObject jsonObject = new JSONObject(formDataMap.get("formData"));
        Map<String, String> newFormData = new HashMap<>();
        jsonObject.keys().forEachRemaining(key -> newFormData.put(key, jsonObject.get(key).toString()));
        boolean retry = false;
        try {
            retry = writeCommittedStream(projectId.get(), dataSet.get(), tableName, newFormData);
        } catch (Descriptors.DescriptorValidationException e) {
            log.error("exception occurred:{}", e.getMessage());
        } catch (InterruptedException e) {
            log.error("Interrupted exception Thrown");
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("Exception occurred:{}", e.getMessage());
        }
        return retry;
    }

    private boolean writeCommittedStream(String projectId, String datasetName, String tableName, Map<String, String> formData)
            throws Descriptors.DescriptorValidationException, InterruptedException, IOException {
        boolean retry = false;

        try(BigQueryWriteClient client = BigQueryWriteClient.create()) {
            DataWriter writer = new DataWriter();
            TableName parentTable = TableName.of(projectId, datasetName, tableName);
            writer.initialize(parentTable, client);
            JSONArray jsonArr = new JSONArray();
            jsonArr.put(formData);
            long offset = 0;
            writer.append(jsonArr, offset);
            offset += jsonArr.length();
            writer.cleanup(client);
        } catch (ExecutionException e) {
            log.info("Failed to append records:{}", e.getMessage());
            retry = true;
        }
        log.info("Appended records successfully for form : {}",tableName);
        return retry;
    }

    private void writeDataToErrorTable(BigQueryTemplate bigQueryTemplate, String formName, String formDataMap, String description) {
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
            job = bigQueryTemplate.writeDataToTable("errorDataTable", new ByteArrayInputStream(errorData.toString().getBytes()), FormatOptions.json(), schema).get();
            job = job.waitFor();
            if (job.isDone()) {
                log.info("dataWritten in the error table");
            } else {
                log.info("BigQuery was unable to load into the table due to an error:" + job.getStatus().getError());
            }
        } catch (InterruptedException e) {
            log.error("Interrupted exception Thrown");
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.error("Unable to write into error table exception message BIG QUERY: {} formName: {} description: {}", exception.getMessage(), formName, description);

        }

    }

    private Map<Boolean, Schema> findDifferentColumns(Schema existingSchema, Schema newSchema) {
        log.info("checking for schema changes");
        FieldList existingFieldList = existingSchema.getFields();
        List<Field> existingFields = new ArrayList<>(existingFieldList);
        List<String> existingColumnNames = existingFields.stream().map(Field::getName).collect(Collectors.toList());
        FieldList newFieldList = newSchema.getFields();
        List<Field> newFields = new ArrayList<>(newFieldList);
        Map<Boolean, Schema> schemaChange = new HashMap<>();
        AtomicReference<Boolean> isSchemaUpdated = new AtomicReference<>(false);
        newFields.forEach(field -> {
            if (!existingColumnNames.contains(field.getName())) {
                isSchemaUpdated.set(true);
                existingFields.add(Field.of(field.getName(), LegacySQLTypeName.STRING));
            }
        });
        schemaChange.put(isSchemaUpdated.get(), Schema.of(existingFields));
        log.info("schema has been updated");
        return schemaChange;
    }

    private void streamWriteTOBigQueryWithRetries(Map<String, String> formDataMap, String tableName, String encryptedData) {
        int maxRetry = MAX_RETRY;
        while (streamFormDataToBigQueryTable(formDataMap, tableName)) {
            int delayInterval = getWaitTimePeriod(maxRetry);
            log.info("Retrying after {} secs, with Thread: {}", delayInterval, Thread.currentThread().getId());
            try {
                Thread.sleep(delayInterval);
            } catch (InterruptedException e) {
                log.error("Thread Interrupted, Retrying without wait ");
                /*
            When you catch the InterruptedException and swallow it, you essentially prevent any higher-level methods/thread groups from noticing the interrupt.
             Which may cause problems.By calling Thread.currentThread().interrupt(), you set the interrupt flag of the thread,
              so higher-level interrupt handlers will notice it and can handle it appropriately.
             */
                Thread.currentThread().interrupt();
            }

            if (maxRetry <= 0) {
                log.info("Exhausted maximum re-tries, writing data to error table and pushing the message to DeadLetter");
                BigQuery bigquery = BigQueryOptions.getDefaultInstance().getService();
                BigQueryTemplate bigQueryTemplate = new BigQueryTemplate(bigquery, dataSet.get());
                writeDataToErrorTable(bigQueryTemplate, tableName, formDataMap.get(FILE_URL), BQ_THRESHOLD_ERROR);
                sendMessageToDeadLetterTopic(encryptedData);
                break;
            }

            maxRetry--;
        }

    }

    private int getWaitTimePeriod(int maxRetry) {
        int delayInterval = 1500;  //default 15 seconds
        try (InputStream input = FormDataToBigQuery.class.getResourceAsStream("/fields.properties")) {
            Properties properties = new Properties();
            properties.load(input);
            firstRetryWaitTime = Integer.parseInt(properties.get("firstRetryWaitTime").toString());
            secondRetryWaitTime = Integer.parseInt(properties.get("secondRetryWaitTime").toString());
            thirdRetryWaitTime = Integer.parseInt(properties.get("thirdRetryWaitTime").toString());

            switch (maxRetry) {
                case 1:
                    delayInterval = thirdRetryWaitTime;
                    break;
                case 2:
                    delayInterval = secondRetryWaitTime;
                    break;
                case 3:
                    delayInterval = firstRetryWaitTime;
                    break;
                default:
                    delayInterval = 0;
            }
        } catch (IOException e) {
            log.error("Properties file not found, using default retry delay interval");
        }

        return delayInterval;
    }

    private void sendMessageToDeadLetterTopic(String encryptedData) {
        Publisher publisher = null;
        try {
            ByteString data = ByteString.copyFrom(encryptedData.getBytes(StandardCharsets.UTF_8));
            PubsubMessage pubsubMessage = PubsubMessage.newBuilder().setData(data).build();
            TopicName topicName = TopicName.of(projectId.toString(), deadLetterTopicId.toString());
            publisher = Publisher.newBuilder(topicName).build();
            publisher.publish(pubsubMessage);
            log.info("message pushed to dead letter queue");
        } catch (IOException e) {
            throw ExceptionFactory.getGenericException(ErrorCode.UNKNOWN_CODE, e.toString());
        } finally {
            if (Objects.nonNull(publisher)) {
                try {
                    publisher.shutdown();
                } catch (Exception exception) {
                    log.error("Unable to shutdown publisher for deadLetterTopic {}", exception.getMessage());
                }
            }
        }
    }
}
