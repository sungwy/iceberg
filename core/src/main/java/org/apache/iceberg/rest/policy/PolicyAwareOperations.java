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
 * Client-side {@code TableOperations} capability: a staged {@link PolicyUpdate} is carried on the
 * next commit's request as the sibling {@code policy} field. {@code BaseTransaction} stages the
 * policy here just before flushing the commit, so the policy rides Iceberg's own commit builder.
 */
public interface PolicyAwareOperations {

  /** Stage a policy to ride the next commit (single-use; cleared after the commit is built). */
  void stagePolicy(PolicyUpdate policy);
}
