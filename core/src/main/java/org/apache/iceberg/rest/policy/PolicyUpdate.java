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
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

/**
 * A catalog-interpreted policy authoring payload carried as a sibling field on a commit request
 * (see {@code UpdateTableRequest#policy()}), NOT as a member of the {@code updates} array.
 *
 * <p>A {@code PolicyUpdate} mutates authorization state, not {@link org.apache.iceberg.TableMetadata}
 * — placing it in {@code updates} would be a category error (RFC §8.12). It rides alongside the
 * metadata updates and is co-committed in the same transaction by a catalog that implements {@link
 * SupportsPolicyCommit}.
 *
 * <p>This type is deliberately <b>generic</b>: core carries the payload but does not interpret it.
 * {@link #bindSchemaId()} selects the schema that unbound column references bind against ({@code -1}
 * = the schema produced by this same commit), and {@link #actions()} is the raw, server-interpreted
 * action list (e.g. the well-known {@code apply-grants} address). Keeping the actions opaque to core
 * means new policy addresses do not require core changes.
 */
public class PolicyUpdate {

  private final int bindSchemaId;
  private final JsonNode actions;

  public PolicyUpdate(int bindSchemaId, JsonNode actions) {
    Preconditions.checkArgument(null != actions, "Invalid policy actions: null");
    Preconditions.checkArgument(actions.isArray(), "Policy actions must be an array: %s", actions);
    this.bindSchemaId = bindSchemaId;
    this.actions = actions;
  }

  /**
   * The id of the schema that unbound (by-name) column references in this policy bind against.
   *
   * <p>{@code -1} is the well-known sentinel for "the schema created by this same commit", letting a
   * policy reference a column the commit itself adds.
   */
  public int bindSchemaId() {
    return bindSchemaId;
  }

  /** The raw, server-interpreted action list (e.g. {@code apply-grants}). */
  public JsonNode actions() {
    return actions;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("bindSchemaId", bindSchemaId)
        .add("actions", actions)
        .toString();
  }
}
