/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

public class TestS3FileIOPreSigning {

  private static final Map<String, String> PROPERTIES =
      ImmutableMap.of(
          AwsClientProperties.CLIENT_REGION,
          "us-west-2",
          S3FileIOProperties.ACCESS_KEY_ID,
          "access",
          S3FileIOProperties.SECRET_ACCESS_KEY,
          "secret");

  @Test
  public void testRequestIsSpelledByTheClient() {
    try (S3FileIO io = new S3FileIO()) {
      io.initialize(PROPERTIES);

      assertThat(io.httpUrl("s3://bucket/data/part-0.parquet"))
          .isEqualTo(URI.create("https://bucket.s3.us-west-2.amazonaws.com/data/part-0.parquet"));
      assertThat(io.httpUrl("s3a://bucket/data/part-0.parquet"))
          .isEqualTo(URI.create("https://bucket.s3.us-west-2.amazonaws.com/data/part-0.parquet"));
      assertThat(io.signingRegion("s3://bucket/data/part-0.parquet")).isEqualTo("us-west-2");
      assertThat(io.signingProvider("s3a://bucket/data/part-0.parquet")).isEqualTo("s3");
    }
  }

  @Test
  public void testEndpointAndPathStyleAreHonored() {
    try (S3FileIO io = new S3FileIO()) {
      io.initialize(
          ImmutableMap.<String, String>builder()
              .putAll(PROPERTIES)
              .put(S3FileIOProperties.ENDPOINT, "http://localhost:9000")
              .put(S3FileIOProperties.PATH_STYLE_ACCESS, "true")
              .build());

      assertThat(io.httpUrl("s3://bucket/data/part-0.parquet"))
          .isEqualTo(URI.create("http://localhost:9000/bucket/data/part-0.parquet"));
    }
  }
}
