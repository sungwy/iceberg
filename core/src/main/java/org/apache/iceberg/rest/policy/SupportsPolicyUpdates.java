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

/**
 * Optional capability for a {@link org.apache.iceberg.Transaction} that can carry a policy change.
 *
 * <p>Exposed as a capability interface (cast {@code (SupportsPolicyUpdates) transaction}) rather than
 * a method on the {@code Transaction} API itself, to avoid an api→core dependency on {@link
 * PolicyUpdate}. A production version would promote {@code updatePolicy()} onto {@code Transaction}
 * (moving the policy model into {@code iceberg-api}).
 */
public interface SupportsPolicyUpdates {

  /** Begin authoring a policy change to be co-committed with this transaction. */
  UpdatePolicy updatePolicy();
}
