package com.loblaw.dataflow.options;

import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.options.*;

public interface PubSubToBigQueryOptions extends DataflowPipelineOptions {

    @Description("GCP KeyRing name")
    @Default.String("my-asymmetric-signing-key2")
    ValueProvider<String> getKeyRing();

    void setKeyRing(ValueProvider<String> value);

    @Description("GCP Key Id name")
    @Default.String("my-new-key-id")
    ValueProvider<String> getKeyId();

    void setKeyId(ValueProvider<String> value);

    @Description("GCP Project Id name")
    @Default.String("persuasive-net-350713")
    ValueProvider<String> getProjectId();

    void setProjectId(ValueProvider<String> value);

    @Description("GCP Location Id name")
    @Default.String("global")
    ValueProvider<String> getLocationId();

    void setLocationId(ValueProvider<String> value);

    @Description("GCP BucketName")
    @Default.String("demo_bucket_storage")
    ValueProvider<String> getBucketName();

    void setBucketName(ValueProvider<String> value);

    @Description(
            "The Cloud Pub/Sub subscription to consume from. "
                    + "The name should be in the format of "
                    + "projects/<project-id>/subscriptions/<subscription-name>")
    @Validation.Required
    @Default.String("projects/persuasive-net-350713/subscriptions/demo_topic-sub")
    ValueProvider<String> getInputSubscription();

    void setInputSubscription(ValueProvider<String> value);

    @Description(
            "This determines whether the template reads from " + "a pub/sub subscription or a topic")
    @Default.Boolean(true)
    Boolean getUseSubscription();

    void setUseSubscription(Boolean value);

    @Description("BigQuery dataSet name")
    @Default.String("form_ingestion")
    ValueProvider<String> getDataSet();

    void setDataSet(ValueProvider<String> value);

    @Description("Dead Letter Topic Name")
    @Default.String("dead_topic")
    ValueProvider<String> getDeadLetterTopicId();

    void setDeadLetterTopicId(ValueProvider<String> value);

}
