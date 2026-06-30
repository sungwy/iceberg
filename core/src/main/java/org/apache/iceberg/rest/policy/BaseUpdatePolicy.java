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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.policy.Grantee;
import org.apache.iceberg.policy.UpdatePolicy;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/**
 * Default {@link UpdatePolicy} builder (the core-side implementation of the api-level interface).
 * Accumulates grant/revoke operations and, on {@link #commit()}, stages one {@link PolicyUpdate}
 * (address {@code apply-grants}) into the owning transaction. The {@code references} manifest is the
 * set of all granted column names; references are authored unbound (by name) and bound at commit
 * against the resulting schema.
 */
public class BaseUpdatePolicy implements UpdatePolicy {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final Consumer<PolicyUpdate> stageFn;
  private final ArrayNode grants = NODES.arrayNode();

  /**
   * @param stageFn callback that stages the built policy into the transaction (typically {@code
   *     pendingPolicies::add})
   */
  public BaseUpdatePolicy(Consumer<PolicyUpdate> stageFn) {
    this.stageFn = stageFn;
  }

  @Override
  public UpdatePolicy grant(String privilege, Grantee grantee, String... columns) {
    return add("grant", privilege, grantee, columns);
  }

  @Override
  public UpdatePolicy revoke(String privilege, Grantee grantee, String... columns) {
    return add("revoke", privilege, grantee, columns);
  }

  @Override
  public UpdatePolicy rowFilter(Expression unboundPredicate, Grantee scope) {
    throw new UnsupportedOperationException(
        "Row filters require the Iceberg Expression function/bound-reference extension "
            + "(apply / id reference nodes) from \"Extending Iceberg Expressions\"; not yet "
            + "available. The flat grant path needs none of it. See the POC README.");
  }

  @Override
  public UpdatePolicy mask(String column, Expression unboundMaskExpr, Grantee scope) {
    throw new UnsupportedOperationException(
        "Column masks require the Iceberg Expression function extension; see rowFilter(...).");
  }

  private UpdatePolicy add(String op, String privilege, Grantee grantee, String... columns) {
    Preconditions.checkArgument(null != grantee, "Invalid grantee: null");
    Preconditions.checkArgument(
        null != columns && columns.length > 0, "Grant requires at least one column");
    ObjectNode granteeNode = NODES.objectNode();
    granteeNode.put("type", grantee.type());
    granteeNode.put("name", grantee.name());

    ArrayNode cols = NODES.arrayNode();
    for (String column : columns) {
      cols.add(column);
    }

    ObjectNode grant = NODES.objectNode();
    grant.put("op", op);
    grant.put("privilege", privilege);
    grant.set("grantee", granteeNode);
    grant.set("columns", cols);
    grants.add(grant);
    return this;
  }

  // Builds the staged wire payload. Private: the api-level UpdatePolicy exposes only commit(); the
  // PolicyUpdate (Jackson) type stays in core and never leaks onto the api surface.
  private PolicyUpdate apply() {
    Preconditions.checkState(grants.size() > 0, "No policy operations to commit");
    // references manifest = the set of all granted column names (by name, unbound).
    Set<String> referencedColumns = new LinkedHashSet<>();
    for (JsonNode grant : grants) {
      grant.get("columns").forEach(col -> referencedColumns.add(col.asText()));
    }
    ArrayNode references = NODES.arrayNode();
    referencedColumns.forEach(references::add);

    ObjectNode node = NODES.objectNode();
    node.put("address", "apply-grants");
    node.set("references", references);
    node.set("grants", grants);
    return new PolicyUpdate(node);
  }

  @Override
  public void commit() {
    stageFn.accept(apply());
  }
}
