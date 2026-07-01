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

/**
 * (De)serializes one {@link PolicyUpdate}. The whole object is carried verbatim — {@code address}
 * and {@code references} plus an opaque, address-specific body — so a new address needs no parser
 * change.
 */
public class PolicyUpdateParser {

  private PolicyUpdateParser() {}

  public static String toJson(PolicyUpdate policy) {
    return toJson(policy, false);
  }

  public static String toJson(PolicyUpdate policy, boolean pretty) {
    return JsonUtil.generate(gen -> toJson(policy, gen), pretty);
  }

  public static void toJson(PolicyUpdate policy, JsonGenerator gen) throws IOException {
    Preconditions.checkArgument(null != policy, "Invalid policy update: null");
    gen.writeTree(policy.node());
  }

  public static PolicyUpdate fromJson(String json) {
    return JsonUtil.parse(json, PolicyUpdateParser::fromJson);
  }

  public static PolicyUpdate fromJson(JsonNode json) {
    Preconditions.checkArgument(null != json, "Cannot parse policy update from null object");
    return new PolicyUpdate(json);
  }
}
