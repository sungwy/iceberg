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

import java.util.List;
import org.apache.iceberg.MetadataUpdate;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.rest.RESTRequest;
import org.apache.iceberg.rest.policy.PolicyUpdate;

public class UpdateTableRequest implements RESTRequest {

  private TableIdentifier identifier;
  private List<org.apache.iceberg.UpdateRequirement> requirements;
  private List<MetadataUpdate> updates;
  // Sibling field carrying a catalog-interpreted policy change, co-committed with the metadata
  // updates above. NOT a member of the `updates` array — a PolicyUpdate is not a MetadataUpdate
  // (it mutates authz, not TableMetadata). See RFC §8.12. Null when the commit carries no policy.
  private PolicyUpdate policy;

  public UpdateTableRequest() {
    // needed for Jackson deserialization
  }

  public UpdateTableRequest(
      List<org.apache.iceberg.UpdateRequirement> requirements, List<MetadataUpdate> updates) {
    this.requirements = requirements;
    this.updates = updates;
  }

  UpdateTableRequest(
      TableIdentifier identifier,
      List<org.apache.iceberg.UpdateRequirement> requirements,
      List<MetadataUpdate> updates) {
    this(requirements, updates);
    this.identifier = identifier;
  }

  UpdateTableRequest(
      TableIdentifier identifier,
      List<org.apache.iceberg.UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      PolicyUpdate policy) {
    this(identifier, requirements, updates);
    this.policy = policy;
  }

  @Override
  public void validate() {}

  public List<org.apache.iceberg.UpdateRequirement> requirements() {
    return requirements != null ? requirements : ImmutableList.of();
  }

  public List<MetadataUpdate> updates() {
    return updates != null ? updates : ImmutableList.of();
  }

  public TableIdentifier identifier() {
    return identifier;
  }

  /**
   * The sibling policy change to co-commit with the metadata {@link #updates()}, or {@code null} if
   * the commit carries no policy. Handled on a separate code path from the {@code updates} loop.
   */
  public PolicyUpdate policy() {
    return policy;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("requirements", requirements)
        .add("updates", updates)
        .add("policy", policy)
        .toString();
  }

  public static UpdateTableRequest create(
      TableIdentifier identifier,
      List<org.apache.iceberg.UpdateRequirement> requirements,
      List<MetadataUpdate> updates) {
    return new UpdateTableRequest(identifier, requirements, updates);
  }

  public static UpdateTableRequest create(
      TableIdentifier identifier,
      List<org.apache.iceberg.UpdateRequirement> requirements,
      List<MetadataUpdate> updates,
      PolicyUpdate policy) {
    return new UpdateTableRequest(identifier, requirements, updates, policy);
  }
}
