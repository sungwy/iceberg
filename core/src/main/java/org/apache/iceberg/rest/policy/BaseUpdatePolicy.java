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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.apache.iceberg.policy.UpdatePolicy;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/**
 * Default {@link UpdatePolicy} builder (the core-side implementation of the api-level interface).
 * Payload-agnostic: each {@link #add} call builds one {@link PolicyUpdate} = {@code {address,
 * references, ...body}} from the api-level (Jackson-free) arguments, and {@link #commit()} stages
 * them into the owning transaction. References are authored unbound (by name) and bound at commit
 * against the resulting schema; the Jackson-backed {@link PolicyUpdate} never appears on the api
 * surface.
 */
public class BaseUpdatePolicy implements UpdatePolicy {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Consumer<PolicyUpdate> stageFn;
  private final List<PolicyUpdate> staged = new ArrayList<>();

  /**
   * @param stageFn callback that stages each built policy into the transaction (typically {@code
   *     pendingPolicies::add})
   */
  public BaseUpdatePolicy(Consumer<PolicyUpdate> stageFn) {
    this.stageFn = stageFn;
  }

  @Override
  public UpdatePolicy add(String address, List<String> references, String body) {
    Preconditions.checkArgument(null != address && !address.isEmpty(), "Invalid address: empty");
    Preconditions.checkArgument(
        null != references && !references.isEmpty(),
        "Policy references must be non-empty (every co-committed policy change binds at least one column)");

    ObjectNode node = NODES.objectNode();
    node.put("address", address);
    ArrayNode refs = NODES.arrayNode();
    references.forEach(refs::add);
    node.set("references", refs);

    JsonNode bodyNode;
    try {
      bodyNode = MAPPER.readTree(body);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid policy body JSON for address '" + address + "'", e);
    }
    Preconditions.checkArgument(
        bodyNode != null && bodyNode.isObject(), "Policy body must be a JSON object: %s", body);
    node.setAll((ObjectNode) bodyNode);

    staged.add(new PolicyUpdate(node));
    return this;
  }

  @Override
  public void commit() {
    Preconditions.checkState(!staged.isEmpty(), "No policy changes to commit");
    staged.forEach(stageFn);
  }
}
