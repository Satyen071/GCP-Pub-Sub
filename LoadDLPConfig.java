package com.loblaw.dataflow;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.ReadChannel;
import com.google.cloud.bigquery.*;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

import java.io.*;
import java.nio.channels.Channels;

// Load DLP Config Data from Cloud Storage
public class LoadDLPConfig {

  private static GoogleCredentials credentials;
  private static String projectId;

  private static String searchQuery = "SELECT formName,fieldName,dlpTechnique,addnl_info " +
          "FROM %1$s.%2$s where formName=\"%3$s\" " +
          "and fieldName=\"%4$s\" and addnl_info is not null and addnl_info != 'DELETED';";
  private static String updateQuery = "UPDATE %1$s.%2$s SET " +
          "addnl_info=\"DELETED\"" +
          "where formName=\"%3$s\" " +
          "and fieldName=\"%4$s\" " +
          "and dlpTechnique=\"%5$s\";";
  private static String insertQuery = "INSERT INTO %1$s.%2$s VALUES(\"%3$s\",\"%4$s\",\"%5$s\",\"\");";

  public static void loadDLPConfig(
      String datasetName, String tableName, String sourceUri) {
    try {
      Schema schema =
              Schema.of(
                      Field.of("formName", StandardSQLTypeName.STRING),
                      Field.of("fieldName", StandardSQLTypeName.STRING),
                      Field.of("dlpTechnique", StandardSQLTypeName.STRING),
                      Field.of("addnl_info", StandardSQLTypeName.STRING)
                      );

      // Initialize client that will be used to send requests. This client only needs to be created
      // once, and can be reused for multiple requests.
      BigQuery bigquery = BigQueryOptions.newBuilder()
              .setCredentials(credentials)
              .setProjectId(projectId)
              .build()
              .getService();

      Storage storage = StorageOptions.newBuilder()
              .setProjectId(projectId)
              .setCredentials(credentials)
              .build()
              .getService();

      //Read CSV Config from Cloud Storage
      Blob blob = storage.get(BlobId.fromGsUtilUri(sourceUri));
      ReadChannel readChannel = blob.reader();
      BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(readChannel)));
      try (CSVReader csvReader = new CSVReaderBuilder(reader).withSkipLines(1).build()) {
        String[] lineInArray;
        while ((lineInArray = csvReader.readNext()) != null) {
          if (lineInArray.length == 4 &&
                  (lineInArray[0] != null && !lineInArray[0].isEmpty()) &&
                  (lineInArray[1] != null && !lineInArray[1].isEmpty()) &&
                  (lineInArray[2] != null && !lineInArray[2].isEmpty()) ) {
            if (null != lineInArray[3] && "Deleted".equalsIgnoreCase(lineInArray[3].trim())) {
              try {
                String query = String.format(updateQuery, datasetName, tableName, lineInArray[0].trim(), lineInArray[1].trim(), lineInArray[2].trim());
                System.out.println("UPDATE Query: " + query);
                QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
                // Execute the query.
                TableResult result = bigquery.query(queryConfig);
                System.out.println("UPDATE Status: " + result.toString());
              } catch (Exception e) {
                System.out.println("Unable to update " + e.getMessage());
              }
            } else {
              String query = String.format(searchQuery,datasetName, tableName, lineInArray[0].trim(), lineInArray[1].trim(), lineInArray[2].trim());
              System.out.println("SEARCH Query: " + query);
              QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
              // Execute the query.
              TableResult result = bigquery.query(queryConfig);
              if(result != null && result.getTotalRows() == 0) {
                System.out.println("No Config found, inserting a new config");
                String insert = String.format(insertQuery,datasetName, tableName, lineInArray[0].trim(), lineInArray[1].trim(), lineInArray[2].trim());
                System.out.println("INSERT Query: " + insert);
                QueryJobConfiguration queryConfig2 = QueryJobConfiguration.newBuilder(insert).build();
                // Execute the query.
                try{
                  TableResult result2 = bigquery.query(queryConfig2);
                  System.out.println("INSERT Status: " + result2.toString());
                } catch (Exception e) {
                System.out.println("Unable to INSERT " + e.getMessage());
              }
              } else {
                System.out.println("Config already exists, no update required");
              }
            }
          }
        }
      }

      // Skip header row in the file.
//      CsvOptions csvOptions = CsvOptions.newBuilder().setSkipLeadingRows(1).build();
//
//      TableId tableId = TableId.of(datasetName, tableName);
//      LoadJobConfiguration loadConfig =
//          LoadJobConfiguration.newBuilder(tableId, sourceUri, csvOptions).setSchema(schema).build();

      // Load data from a GCS CSV file into the table
//      JobId jobId = JobId.newBuilder().build();
//      // Create a job with job ID
//      Job job = bigquery.create(JobInfo.of(jobId, queryConfig));
//      //Job job = bigquery.create(JobInfo.of(loadConfig));
//      // Blocks until this load table job completes its execution, either failing or succeeding.
//      job = job.waitFor();
//      if (job.isDone()) {
//        System.out.println("CSV from GCS successfully added during load append job");
//      } else {
//        System.out.println(
//            "BigQuery was unable to load into the table due to an error:"
//                + job.getStatus().getError());
//      }
    } catch (BigQueryException | InterruptedException e) {
      System.out.println("Column not added during load append \n" + e.toString());
    } catch (Exception ex)
    {
      ex.printStackTrace();
    }

  }

  public static void main(String[] args) throws IOException {
    if(args.length == 5) {
      try {
            projectId = args[3];
            try (FileInputStream input = new FileInputStream(args[4])) {
              credentials = GoogleCredentials.fromStream(input);
            }
            loadDLPConfig(args[0], args[1], args[2]);
      } catch (Exception ex) {
        System.out.println("Unable to execute: " + ex.getMessage());
      }
    }
  }
}