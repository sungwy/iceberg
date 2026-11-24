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
package org.apache.iceberg.rest.auth;

import org.apache.iceberg.catalog.TableIdentifier;

/** The session that loaded a table, as RESTSessionCatalog binds it to the table's FileIO. */
public interface FileIOSession {

  /**
   * Returns the key that identifies this session in the catalog instance, for the table when the
   * table has its own session.
   */
  String sessionKey();

  TableIdentifier tableIdentifier();

  /**
   * Returns the source of the session's current token. Each call derives the session again through
   * the catalog's AuthManager, and returns null after the catalog is closed.
   */
  BearerTokenSource bearerTokenSource();
}
