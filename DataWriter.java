package com.loblaw.dataflow.transforms;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.bigquery.storage.v1.*;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.protobuf.Descriptors;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Phaser;

@Slf4j
public class DataWriter {

    private JsonStreamWriter streamWriter;
    // Track the number of in-flight requests to wait for all responses before shutting down.
    private final Phaser inFlightRequestCount = new Phaser(1);

    private final Object lock = new Object();

    @GuardedBy("lock")
    private RuntimeException error = null;

    void initialize(TableName parentTable, BigQueryWriteClient client)
            throws IOException, Descriptors.DescriptorValidationException, InterruptedException {
        WriteStream stream = WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build();

        CreateWriteStreamRequest createWriteStreamRequest =
                CreateWriteStreamRequest.newBuilder()
                        .setParent(parentTable.toString())
                        .setWriteStream(stream)
                        .build();
        WriteStream writeStream = client.createWriteStream(createWriteStreamRequest);
        streamWriter = JsonStreamWriter.newBuilder(writeStream.getName(), writeStream.getTableSchema()).build();
    }
    public void append(JSONArray data, long offset)
            throws Descriptors.DescriptorValidationException, IOException, ExecutionException {
        synchronized (this.lock) {
            if (this.error != null) {
                throw this.error;
            }
        }
        ApiFuture<AppendRowsResponse> future = streamWriter.append(data, offset);
        ApiFutures.addCallback(
                future, new DataWriter.AppendCompleteCallback(this), MoreExecutors.directExecutor());
        // Increase the count of in-flight requests.
        inFlightRequestCount.register();
    }

    public void cleanup(BigQueryWriteClient client) {
        inFlightRequestCount.arriveAndAwaitAdvance();
        streamWriter.close();
        synchronized (this.lock) {
            if (this.error != null) {
                throw this.error;
            }
        }
        FinalizeWriteStreamResponse finalizeResponse =
                client.finalizeWriteStream(streamWriter.getStreamName());
        log.info("Rows written: " + finalizeResponse.getRowCount());
    }
    public String getStreamName() {
        return streamWriter.getStreamName();
    }

    static class AppendCompleteCallback implements ApiFutureCallback<AppendRowsResponse> {

        private final DataWriter parent;
        public AppendCompleteCallback(DataWriter parent) {
            this.parent = parent;
        }
        public void onSuccess(AppendRowsResponse response) {
//            log.info("Append %d success\n", response.getAppendResult().getOffset().getValue());
            done();
        }
        public void onFailure(Throwable throwable) {
            synchronized (this.parent.lock) {
                if (this.parent.error == null) {
                    Exceptions.StorageException storageException = Exceptions.toStorageException(throwable);
                    this.parent.error =
                            (storageException != null) ? storageException : new RuntimeException(throwable);
                }
            }
            log.error("Error:{}", throwable.toString());
            done();
        }
        private void done() {
            // Reduce the count of in-flight requests.
            this.parent.inFlightRequestCount.arriveAndDeregister();
        }
    }
}

