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

/** The principal a grant/revoke or a row-filter/mask scope applies to. */
public class Grantee {

  private final String type; // "role" | "user"
  private final String name;

  private Grantee(String type, String name) {
    this.type = type;
    this.name = name;
  }

  public static Grantee role(String name) {
    return new Grantee("role", name);
  }

  public static Grantee user(String name) {
    return new Grantee("user", name);
  }

  public String type() {
    return type;
  }

  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return type + ":" + name;
  }
}
