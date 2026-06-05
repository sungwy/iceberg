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

import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;

/**
 * Optional capability for {@link TableOperations} that can co-commit a {@link PolicyUpdate}
 * atomically with a metadata change.
 *
 * <p>This is the seam through which a commit carrying a sibling {@code policy} field is applied. A
 * catalog whose {@code TableOperations} implements this interface commits the metadata pointer and
 * the policy change in a <b>single transaction</b> (single-store catalogs get real atomicity). When
 * a commit carries a policy field but the backing {@code TableOperations} does not implement this
 * interface, the commit MUST be rejected rather than silently dropping the policy — capability
 * negotiation, not parser leniency, is the safety mechanism.
 */
public interface SupportsPolicyCommit {

  /**
   * Atomically commit a metadata change and a policy change in the same transaction.
   *
   * <p>The implementation binds unbound (by-name) column references in {@code policy} to field-ids
   * against {@code updated} (resolving {@code bind-schema-id: -1} to the schema produced by this
   * commit), persists the bound policy, and advances the metadata pointer from {@code base} to
   * {@code updated} — all-or-nothing. If any part fails, neither the metadata pointer nor the policy
   * is durable.
   *
   * @param base the table metadata the commit is applied on top of (the expected current state)
   * @param updated the new table metadata to commit
   * @param policy the policy payload to bind and persist in the same transaction
   */
  void commit(TableMetadata base, TableMetadata updated, PolicyUpdate policy);
}
