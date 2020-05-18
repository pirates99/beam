/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.ml;

import com.google.auto.value.AutoValue;
import com.google.cloud.dlp.v2.DlpServiceClient;
import com.google.privacy.dlp.v2.ContentItem;
import com.google.privacy.dlp.v2.FieldId;
import com.google.privacy.dlp.v2.InspectConfig;
import com.google.privacy.dlp.v2.InspectContentRequest;
import com.google.privacy.dlp.v2.InspectContentResponse;
import com.google.privacy.dlp.v2.ProjectName;
import com.google.privacy.dlp.v2.Table;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PTransform} connecting to Cloud DLP and inspecting text for identifying data according
 * to provided settings.
 *
 * <p>Either inspectTemplateName (String) or inspectConfig {@link InspectConfig} need to be set.
 *
 * <p>Batch size defines how big are batches sent to DLP at once in bytes.
 */
@Experimental
@AutoValue
public abstract class DLPInspectText
    extends PTransform<
        PCollection<KV<String, String>>, PCollection<KV<String, InspectContentResponse>>> {
  public static final Logger LOG = LoggerFactory.getLogger(DLPInspectText.class);

  public static final Integer DLP_PAYLOAD_LIMIT = 52400;

  @Nullable
  public abstract String inspectTemplateName();

  @Nullable
  public abstract InspectConfig inspectConfig();

  public abstract Integer batchSize();

  public abstract String projectId();

  @Nullable
  public abstract String csvDelimiter();

  @Nullable
  public abstract PCollectionView<List<String>> csvHeader();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setInspectTemplateName(String inspectTemplateName);

    public abstract Builder setInspectConfig(InspectConfig inspectConfig);

    public abstract Builder setBatchSize(Integer batchSize);

    public abstract Builder setProjectId(String projectId);

    public abstract Builder setCsvDelimiter(String csvDelimiter);

    public abstract Builder setCsvHeader(PCollectionView<List<String>> csvHeader);

    public abstract DLPInspectText build();
  }

  public static Builder newBuilder() {
    return new AutoValue_DLPInspectText.Builder();
  }

  @Override
  public PCollection<KV<String, InspectContentResponse>> expand(
      PCollection<KV<String, String>> input) {
    return input
        .apply(ParDo.of(new MapStringToDlpRow(csvDelimiter())))
        .apply("Batch Contents", ParDo.of(new BatchRequestForDLP(batchSize())))
        .apply(
            "DLPInspect",
            ParDo.of(
                new InspectData(projectId(), inspectTemplateName(), inspectConfig(), csvHeader())));
  }

  public static class InspectData
      extends DoFn<KV<String, Iterable<Table.Row>>, KV<String, InspectContentResponse>> {
    private final String projectId;
    private final String inspectTemplateName;
    private final InspectConfig inspectConfig;
    private transient InspectContentRequest.Builder requestBuilder;
    private final PCollectionView<List<String>> csvHeader;

    public InspectData(
        String projectId,
        String inspectTemplateName,
        InspectConfig inspectConfig,
        PCollectionView<List<String>> csvHeader) {
      this.projectId = projectId;
      this.inspectTemplateName = inspectTemplateName;
      this.inspectConfig = inspectConfig;
      this.csvHeader = csvHeader;
    }

    @Setup
    public void setup() {
      this.requestBuilder =
          InspectContentRequest.newBuilder().setParent(ProjectName.of(this.projectId).toString());
      if (inspectTemplateName != null) {
        requestBuilder.setInspectTemplateName(this.inspectTemplateName);
      }
      if (inspectConfig != null) {
        requestBuilder.setInspectConfig(inspectConfig);
      }
      if (inspectTemplateName == null && inspectConfig == null) {
        throw new IllegalArgumentException("");
      }
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws IOException {
      try (DlpServiceClient dlpServiceClient = DlpServiceClient.create()) {
        List<FieldId> tableHeaders;
        if (csvHeader != null) {
          tableHeaders =
              c.sideInput(csvHeader).stream()
                  .map(header -> FieldId.newBuilder().setName(header).build())
                  .collect(Collectors.toList());
        } else {
          tableHeaders = new ArrayList<>();
          tableHeaders.add(FieldId.newBuilder().setName("value").build());
        }
        Table table =
            Table.newBuilder()
                .addAllHeaders(tableHeaders)
                .addAllRows(c.element().getValue())
                .build();
        ContentItem contentItem = ContentItem.newBuilder().setTable(table).build();
        this.requestBuilder.setItem(contentItem);
        InspectContentResponse response =
            dlpServiceClient.inspectContent(this.requestBuilder.build());
        c.output(KV.of(c.element().getKey(), response));
      }
    }
  }
}