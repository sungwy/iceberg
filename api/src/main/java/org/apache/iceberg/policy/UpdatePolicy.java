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
package org.apache.iceberg.policy;

import java.util.List;

/**
 * API for authoring catalog-interpreted policy changes that co-commit with a {@link
 * org.apache.iceberg.Transaction}'s metadata changes (obtained via {@link
 * org.apache.iceberg.Transaction#updatePolicy()}).
 *
 * <p>This builder is deliberately <b>payload-agnostic</b>: it mirrors the wire {@code PolicyUpdate}
 * envelope exactly — an {@code address} (how the catalog's binder dispatches), a {@code references}
 * manifest (every column the change depends on, by name, unbound), and an address-specific {@code
 * body} that is opaque to the api/core envelope. It does <b>not</b> enumerate specific policy kinds
 * (grants, masks, filters); those are vendor-interpreted addresses, so blessing any of them in the
 * core API would be a category error. The body is passed as JSON so the api surface stays free of a
 * JSON-library dependency (the Jackson-backed wire type lives in {@code iceberg-core}).
 *
 * <p>Like {@link org.apache.iceberg.UpdateSchema}, configure with {@link #add} then {@link
 * #commit()} to stage into the transaction; {@code transaction.commitTransaction()} carries the
 * staged changes as the {@code policy-updates} sibling list on the single commit request. Column
 * references are authored by <b>name</b> (unbound) and bound to field-ids by the catalog at commit —
 * so a policy may reference a column the same transaction adds.
 */
public interface UpdatePolicy {

  /**
   * Stage one policy change to co-commit with this transaction.
   *
   * @param address names the kind of policy change; how the catalog's binder dispatches
   * @param references every column this change depends on, by name (unbound, non-empty); must equal,
   *     as a set, the columns the {@code body} references — the catalog rejects the commit otherwise
   * @param body the address-specific content as a JSON object (e.g. {@code {"masks":[...]}}), opaque
   *     to the envelope; its column references are authored unbound (by name) and bound at commit
   * @return this for chaining
   */
  UpdatePolicy add(String address, List<String> references, String body);

  /** Stage the authored policy change(s) into the owning transaction's next commit. */
  void commit();
}
