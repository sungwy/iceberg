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

import org.apache.iceberg.PendingUpdate;
import org.apache.iceberg.expressions.Expression;

/**
 * API for authoring a catalog-interpreted policy change as a first-class pending update on a
 * {@link org.apache.iceberg.Transaction} (obtained via {@link SupportsPolicyUpdates#updatePolicy()}).
 *
 * <p>Like {@link org.apache.iceberg.UpdateSchema}, configuring this builder and calling {@link
 * #commit()} stages the change into the transaction; {@code transaction.commitTransaction()} then
 * carries it as the sibling {@code policy} field on the single {@code UpdateTableRequest}. Column
 * references are authored by <b>name</b> (unbound) and bound to field-ids by the catalog at commit —
 * so a policy may reference a column the same transaction adds.
 */
public interface UpdatePolicy extends PendingUpdate<PolicyUpdate> {

  /** Grant a privilege on the given columns to a grantee. Columns are authored by name. */
  UpdatePolicy grant(String privilege, Grantee grantee, String... columns);

  /** Revoke a privilege on the given columns from a grantee. Columns are authored by name. */
  UpdatePolicy revoke(String privilege, Grantee grantee, String... columns);

  /**
   * Author a row-filter predicate (Iceberg Expression form, unbound references) scoped to a grantee.
   *
   * <p>NOTE: this requires the function-call and bound-reference nodes added by "Extending Iceberg
   * Expressions"; until that lands, this is intentionally unsupported (the grant path needs none of
   * it). See the POC README.
   */
  UpdatePolicy rowFilter(Expression unboundPredicate, Grantee scope);

  /**
   * Author a column mask expression (Iceberg Expression form, unbound references) scoped to a
   * grantee. See {@link #rowFilter} for the Expression-extension dependency.
   */
  UpdatePolicy mask(String column, Expression unboundMaskExpr, Grantee scope);
}
