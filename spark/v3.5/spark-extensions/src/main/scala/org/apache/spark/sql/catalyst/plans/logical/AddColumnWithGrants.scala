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
package org.apache.spark.sql.catalyst.plans.logical

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale
import org.apache.iceberg.MetadataUpdate
import org.apache.iceberg.Schema
import org.apache.iceberg.UpdateRequirement
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.rest.policy.PolicyUpdate
import org.apache.iceberg.rest.requests.UpdateTableRequest
import org.apache.iceberg.types.Type
import org.apache.iceberg.types.Types
import org.apache.spark.sql.catalyst.expressions.Attribute

/** One grant/revoke clause parsed from a fused ALTER TABLE … (GRANT|REVOKE) … statement. */
case class GrantSpec(
    action: String, // "grant" | "revoke"
    privilege: String,
    columns: Seq[String],
    granteeType: String,
    granteeName: String)

/**
 * A single fused statement: add one column AND author grants/revokes over columns (possibly
 * including the column being added). The whole statement compiles to exactly ONE
 * [[org.apache.iceberg.rest.requests.UpdateTableRequest]] — standard metadata updates plus a sibling
 * [[org.apache.iceberg.rest.policy.PolicyUpdate]] — so policy and metadata ride one commit boundary.
 *
 * [[buildCommit]] is the commit-assembly the exec rule would emit. It is exposed so the proof can
 * assert "one statement -> one commit" and drive that commit through the catalog's atomic path
 * without reworking Spark's sealed commit builder (that engine-side wiring is the Step-4 stretch).
 */
case class AddColumnWithGrants(
    table: Seq[String],
    columnName: String,
    columnType: String,
    grants: Seq[GrantSpec])
    extends LeafCommand {

  override lazy val output: Seq[Attribute] = Nil

  override def simpleString(maxFields: Int): String =
    s"AddColumnWithGrants ${table.mkString(".")} ADD COLUMN $columnName $columnType " +
      grants.map(g => s"${g.action} ${g.privilege}(${g.columns.mkString(",")})").mkString(" ")

  def identifier: TableIdentifier = {
    val name = table.last
    val levels = table.dropRight(1).toArray
    TableIdentifier.of(Namespace.of(levels: _*), name)
  }

  /** Assemble the ONE commit this statement compiles to, given the table's current schema. */
  def buildCommit(base: Schema): UpdateTableRequest =
    AddColumnWithGrants.assemble(identifier, columnName, columnType, grants, base)

  /** The sibling policy payload (one apply-grants action) carrying the unbound grants. */
  def policyUpdate(): PolicyUpdate = AddColumnWithGrants.policyUpdate(grants)
}

object AddColumnWithGrants {

  /**
   * Assemble the single {@link UpdateTableRequest} the fused statement compiles to: standard updates
   * (add a new schema with the column, make it current) plus the sibling [[PolicyUpdate]]. The new
   * column is added to a new schema; the grants are carried unbound (by name) and the catalog binds
   * them to field-ids against THIS commit's schema.
   */
  def assemble(
      identifier: TableIdentifier,
      columnName: String,
      columnType: String,
      grants: Seq[GrantSpec],
      base: Schema): UpdateTableRequest = {
    val columns = new java.util.ArrayList[Types.NestedField](base.columns())
    val newFieldId = base.highestFieldId() + 1
    columns.add(Types.NestedField.optional(newFieldId, columnName, icebergType(columnType)))
    val newSchema = new Schema(columns)

    val updates = new java.util.ArrayList[MetadataUpdate]()
    updates.add(new MetadataUpdate.AddSchema(newSchema))
    updates.add(new MetadataUpdate.SetCurrentSchema(-1))

    val requirements = new java.util.ArrayList[UpdateRequirement]()
    requirements.add(new UpdateRequirement.AssertCurrentSchemaID(base.schemaId()))

    UpdateTableRequest.create(identifier, requirements, updates, policyUpdate(grants))
  }

  def policyUpdate(grants: Seq[GrantSpec]): PolicyUpdate = {
    val mapper = new ObjectMapper()
    val grantsArr = mapper.createArrayNode()
    grants.foreach { g =>
      val grantee = mapper.createObjectNode()
      grantee.put("type", g.granteeType)
      grantee.put("name", g.granteeName)
      val cols = mapper.createArrayNode()
      g.columns.foreach(c => cols.add(c))
      val node = mapper.createObjectNode()
      node.put("op", g.action)
      node.put("privilege", g.privilege)
      node.set("grantee", grantee)
      node.set("columns", cols)
      grantsArr.add(node)
    }
    val action = mapper.createObjectNode()
    action.put("address", "apply-grants")
    action.set("grants", grantsArr)
    val actions = mapper.createArrayNode()
    actions.add(action)
    // bind-schema-id -1: bind against the schema this commit produces (lets a grant reference the
    // column the same statement adds).
    new PolicyUpdate(-1, actions)
  }

  private def icebergType(name: String): Type =
    name.toLowerCase(Locale.ROOT) match {
      case "string" => Types.StringType.get()
      case "int" | "integer" => Types.IntegerType.get()
      case "long" | "bigint" => Types.LongType.get()
      case "boolean" => Types.BooleanType.get()
      case "double" => Types.DoubleType.get()
      case "float" => Types.FloatType.get()
      case other => throw new IllegalArgumentException("Unsupported column type for POC: " + other)
    }
}
