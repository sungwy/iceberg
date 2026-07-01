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

import java.util.List;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;

/**
 * Optional capability for {@link TableOperations} that can co-commit a list of {@link PolicyUpdate}s
 * atomically with a metadata change.
 *
 * <p>This is the seam through which a commit carrying the sibling {@code policy-updates} list is
 * applied. A catalog whose {@code TableOperations} implements this interface commits the metadata
 * pointer and the policy changes in a <b>single transaction</b> (single-store catalogs get real
 * atomicity). When a commit carries policy updates but the backing {@code TableOperations} does not
 * implement this interface, the commit MUST be rejected rather than silently dropping them. There is
 * no capability handshake; client/catalog trust is established out of band, and reject-if-cannot-honor
 * (not parser leniency) is the safety rule.
 */
public interface SupportsPolicyCommit {

  /**
   * Atomically commit a metadata change and a list of policy changes in the same transaction.
   *
   * <p>The implementation binds each {@link PolicyUpdate}'s {@code references} (unbound, by name) to
   * field-ids against {@code updated} (the resulting schema this commit produces), persists the
   * bound policies, and advances the metadata pointer from {@code base} to {@code updated} —
   * all-or-nothing. If any reference does not resolve, any address is unsupported, or any part
   * fails, neither the metadata pointer nor any policy is durable. Binding never partially applies.
   *
   * @param base the table metadata the commit is applied on top of (the expected current state)
   * @param updated the new table metadata to commit
   * @param policyUpdates the policy changes to bind and persist in the same transaction
   */
  void commit(TableMetadata base, TableMetadata updated, List<PolicyUpdate> policyUpdates);
}
