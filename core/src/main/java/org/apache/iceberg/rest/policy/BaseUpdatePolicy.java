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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/**
 * Default {@link UpdatePolicy} builder. Accumulates grant/revoke operations into a single
 * {@code apply-grants} action and, on {@link #commit()}, stages the resulting {@link PolicyUpdate}
 * into the owning transaction (via the supplied callback). References are authored unbound
 * (by name); the catalog binds them at commit ({@code bind-schema-id: -1}).
 */
public class BaseUpdatePolicy implements UpdatePolicy {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final Consumer<PolicyUpdate> stageFn;
  private final ArrayNode grants = NODES.arrayNode();

  /**
   * @param stageFn callback that stages the built policy into the transaction (typically {@code
   *     BaseTransaction::stagePendingPolicy})
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

  @Override
  public PolicyUpdate apply() {
    Preconditions.checkState(grants.size() > 0, "No policy operations to commit");
    ObjectNode action = NODES.objectNode();
    action.put("address", "apply-grants");
    action.set("grants", grants);
    ArrayNode actions = NODES.arrayNode();
    actions.add(action);
    // bind-schema-id -1: bind against the schema this commit produces.
    return new PolicyUpdate(-1, actions);
  }

  @Override
  public void commit() {
    stageFn.accept(apply());
  }
}
