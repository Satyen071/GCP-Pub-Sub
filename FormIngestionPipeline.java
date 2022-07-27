package com.loblaw.dataflow;


import com.loblaw.dataflow.options.PubSubToBigQueryOptions;
import com.loblaw.dataflow.transforms.FormDataDlp;
import com.loblaw.dataflow.transforms.FormDataToBigQuery;
import com.loblaw.dataflow.transforms.FormDataToCloudStorageFn;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.runners.dataflow.options.DataflowPipelineWorkerPoolOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;

import java.io.IOException;
import java.util.Collections;

@Slf4j
public class FormIngestionPipeline {

    public static void run(PubSubToBigQueryOptions options) {

        Pipeline pipeline = Pipeline.create(options);
        PCollection<PubsubMessage> pCollection = pipeline.apply("Read PubSub messages",
                PubsubIO.readMessagesWithAttributesAndMessageId().fromSubscription(options.getInputSubscription()));

        pCollection.apply(ParDo.of(new FormDataToCloudStorageFn(options)))
                .apply(ParDo.of(new FormDataDlp(options)))
                .apply(ParDo.of(new FormDataToBigQuery(options)));

        PipelineResult result = pipeline.run();
        try {
            result.getState();
            result.waitUntilFinish();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {

        try {
            PipelineOptionsFactory.register(PubSubToBigQueryOptions.class);
            PubSubToBigQueryOptions options = PipelineOptionsFactory
                    .fromArgs(args)
                    .withValidation()
                    .as(PubSubToBigQueryOptions.class);

            options.setMaxBufferingDurationMilliSec(10000);
            options.setStreaming(true);
            options.setEnableStreamingEngine(true);
            options.setAutoscalingAlgorithm(DataflowPipelineWorkerPoolOptions.AutoscalingAlgorithmType.THROUGHPUT_BASED);
            options.setDumpHeapOnOOM(true);
            options.setNumberOfWorkerHarnessThreads(200);
            run(options);
        } catch (Exception e) {
            log.error("Exception occurred in main:{} message {}",e.getClass().getName(),e.getMessage());
        }

    }
}

