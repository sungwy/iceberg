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
package org.apache.iceberg.rest.requests;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.MetadataUpdateParser;
import org.apache.iceberg.UpdateRequirement;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.TableIdentifierParser;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.rest.policy.PolicyUpdate;
import org.apache.iceberg.rest.policy.PolicyUpdateParser;
import org.apache.iceberg.util.JsonUtil;

public class UpdateTableRequestParser {

  private static final String IDENTIFIER = "identifier";
  private static final String REQUIREMENTS = "requirements";
  private static final String UPDATES = "updates";
  // Sibling field, parsed on a separate path from the `updates` array (which is untouched).
  private static final String POLICY_UPDATES = "policy-updates";

  private UpdateTableRequestParser() {}

  public static String toJson(UpdateTableRequest request) {
    return toJson(request, false);
  }

  public static String toJson(UpdateTableRequest request, boolean pretty) {
    return JsonUtil.generate(gen -> toJson(request, gen), pretty);
  }

  public static void toJson(UpdateTableRequest request, JsonGenerator gen) throws IOException {
    Preconditions.checkArgument(null != request, "Invalid update table request: null");

    gen.writeStartObject();

    if (null != request.identifier()) {
      gen.writeFieldName(IDENTIFIER);
      TableIdentifierParser.toJson(request.identifier(), gen);
    }

    gen.writeArrayFieldStart(REQUIREMENTS);
    for (UpdateRequirement updateRequirement : request.requirements()) {
      org.apache.iceberg.UpdateRequirementParser.toJson(updateRequirement, gen);
    }
    gen.writeEndArray();

    gen.writeArrayFieldStart(UPDATES);
    for (MetadataUpdate metadataUpdate : request.updates()) {
      MetadataUpdateParser.toJson(metadataUpdate, gen);
    }
    gen.writeEndArray();

    if (!request.policyUpdates().isEmpty()) {
      gen.writeArrayFieldStart(POLICY_UPDATES);
      for (PolicyUpdate policyUpdate : request.policyUpdates()) {
        PolicyUpdateParser.toJson(policyUpdate, gen);
      }
      gen.writeEndArray();
    }

    gen.writeEndObject();
  }

  public static UpdateTableRequest fromJson(String json) {
    return JsonUtil.parse(json, UpdateTableRequestParser::fromJson);
  }

  public static UpdateTableRequest fromJson(JsonNode json) {
    Preconditions.checkArgument(null != json, "Cannot parse update table request from null object");

    TableIdentifier identifier = null;
    List<UpdateRequirement> requirements = Lists.newArrayList();
    List<MetadataUpdate> updates = Lists.newArrayList();

    if (json.hasNonNull(IDENTIFIER)) {
      identifier = TableIdentifierParser.fromJson(JsonUtil.get(IDENTIFIER, json));
    }

    if (json.hasNonNull(REQUIREMENTS)) {
      JsonNode requirementsNode = JsonUtil.get(REQUIREMENTS, json);
      Preconditions.checkArgument(
          requirementsNode.isArray(),
          "Cannot parse requirements from non-array: %s",
          requirementsNode);
      requirementsNode.forEach(
          req -> requirements.add(org.apache.iceberg.UpdateRequirementParser.fromJson(req)));
    }

    if (json.hasNonNull(UPDATES)) {
      JsonNode updatesNode = JsonUtil.get(UPDATES, json);
      Preconditions.checkArgument(
          updatesNode.isArray(), "Cannot parse metadata updates from non-array: %s", updatesNode);

      updatesNode.forEach(update -> updates.add(MetadataUpdateParser.fromJson(update)));
    }

    List<PolicyUpdate> policyUpdates = Lists.newArrayList();
    if (json.hasNonNull(POLICY_UPDATES)) {
      JsonNode policyUpdatesNode = JsonUtil.get(POLICY_UPDATES, json);
      Preconditions.checkArgument(
          policyUpdatesNode.isArray(),
          "Cannot parse policy-updates from non-array: %s",
          policyUpdatesNode);
      policyUpdatesNode.forEach(pu -> policyUpdates.add(PolicyUpdateParser.fromJson(pu)));
    }

    return UpdateTableRequest.create(identifier, requirements, updates, policyUpdates);
  }
}
