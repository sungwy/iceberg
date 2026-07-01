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
import java.util.ArrayList;
import java.util.List;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/**
 * One catalog-interpreted policy change, carried in the {@code policy-updates} sibling list on a
 * commit request (see {@code UpdateTableRequest#policyUpdates()}), NOT as a member of {@code
 * updates}. A {@code PolicyUpdate} mutates authorization state, not {@link
 * org.apache.iceberg.TableMetadata}, so putting it in {@code updates} would be a category error.
 *
 * <p>Shape (RFC "Proposed Envelope Design", non-normative / illustrative):
 *
 * <ul>
 *   <li>{@code address} (required) — names the kind of change; how the catalog's binder dispatches.
 *   <li>{@code references} (required) — every column this change depends on, by NAME, unbound; the
 *       manifest the binder resolves against the resulting schema. May be empty (table-scoped).
 *   <li>an address-specific, opaque body ({@code additionalProperties: true}) carrying the change's
 *       column references by name in whatever form fits the address (e.g. {@code grants},
 *       {@code filter}).
 * </ul>
 *
 * <p>There is intentionally no {@code bind-schema-id} on the wire: binding is always against the
 * schema this commit produces (the resulting schema), an implicit rule, so no schema id rides the
 * request. The type stays generic — core carries the body uninterpreted so a new address needs no
 * core change.
 */
public class PolicyUpdate {

  private static final String ADDRESS = "address";
  private static final String REFERENCES = "references";

  private final JsonNode node;

  /** @param node the full policy-update object: {@code {address, references, ...body}} */
  public PolicyUpdate(JsonNode node) {
    Preconditions.checkArgument(null != node, "Invalid policy update: null");
    Preconditions.checkArgument(
        node.isObject(), "Cannot build policy update from non-object: %s", node);
    JsonNode address = node.get(ADDRESS);
    Preconditions.checkArgument(
        address != null && address.isTextual(),
        "Policy update requires a string '%s'",
        ADDRESS);
    JsonNode references = node.get(REFERENCES);
    Preconditions.checkArgument(
        references != null && references.isArray(),
        "Policy update requires a '%s' array (may be empty)",
        REFERENCES);
    this.node = node;
  }

  /** The address: the kind of policy change, used by the catalog's binder to dispatch. */
  public String address() {
    return node.get(ADDRESS).asText();
  }

  /**
   * The references manifest: every column this change depends on, by name, unbound. This is the
   * list the binder resolves against the resulting schema (reject on any unmatched name).
   */
  public List<String> references() {
    List<String> names = new ArrayList<>();
    node.get(REFERENCES).forEach(name -> names.add(name.asText()));
    return names;
  }

  /** The full policy-update object, including the address-specific body (opaque to core). */
  public JsonNode node() {
    return node;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("policyUpdate", node).toString();
  }
}
