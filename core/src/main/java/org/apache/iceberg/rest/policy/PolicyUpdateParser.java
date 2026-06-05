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
package org.apache.iceberg.rest.policy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.util.JsonUtil;

public class PolicyUpdateParser {

  private static final String BIND_SCHEMA_ID = "bind-schema-id";
  private static final String ACTIONS = "actions";

  private PolicyUpdateParser() {}

  public static String toJson(PolicyUpdate policy) {
    return toJson(policy, false);
  }

  public static String toJson(PolicyUpdate policy, boolean pretty) {
    return JsonUtil.generate(gen -> toJson(policy, gen), pretty);
  }

  public static void toJson(PolicyUpdate policy, JsonGenerator gen) throws IOException {
    Preconditions.checkArgument(null != policy, "Invalid policy update: null");

    gen.writeStartObject();
    gen.writeNumberField(BIND_SCHEMA_ID, policy.bindSchemaId());
    gen.writeFieldName(ACTIONS);
    gen.writeTree(policy.actions());
    gen.writeEndObject();
  }

  public static PolicyUpdate fromJson(String json) {
    return JsonUtil.parse(json, PolicyUpdateParser::fromJson);
  }

  public static PolicyUpdate fromJson(JsonNode json) {
    Preconditions.checkArgument(null != json, "Cannot parse policy update from null object");
    Preconditions.checkArgument(
        json.isObject(), "Cannot parse policy update from non-object: %s", json);

    int bindSchemaId = JsonUtil.getInt(BIND_SCHEMA_ID, json);

    JsonNode actions = json.get(ACTIONS);
    Preconditions.checkArgument(
        actions != null && actions.isArray(),
        "Cannot parse policy update: '%s' must be an array",
        ACTIONS);

    return new PolicyUpdate(bindSchemaId, actions);
  }
}
