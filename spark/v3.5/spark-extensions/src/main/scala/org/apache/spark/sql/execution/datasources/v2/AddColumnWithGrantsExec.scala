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
package org.apache.spark.sql.execution.datasources.v2

import org.apache.iceberg.rest.CatalogHandlers
import org.apache.iceberg.rest.requests.UpdateTableRequest
import org.apache.iceberg.spark.Spark3Util
import org.apache.iceberg.spark.SparkCatalog
import org.apache.iceberg.spark.source.SparkTable
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.AddColumnWithGrants
import org.apache.spark.sql.catalyst.plans.logical.GrantSpec
import org.apache.spark.sql.connector.catalog.Identifier
import org.apache.spark.sql.connector.catalog.TableCatalog

/**
 * Executes a fused {@code ALTER TABLE … ADD COLUMN … GRANT/REVOKE …} statement as ONE commit. The
 * single [[UpdateTableRequest]] (standard updates + sibling PolicyUpdate) is assembled and driven
 * through Iceberg's server-side [[CatalogHandlers]] against the catalog this Spark catalog wraps, so
 * the metadata change and the policy co-commit atomically in one boundary — from a live Spark SQL
 * statement, engine-side.
 *
 * Scope: the request is assembled by the exec and submitted via {@code CatalogHandlers.updateTable}.
 * Threading the sibling policy through Iceberg's internal {@code Transaction} request builder (so a
 * plain {@code Transaction.commitTransaction()} carries it) would be the remaining core change.
 */
case class AddColumnWithGrantsExec(
    catalog: TableCatalog,
    ident: Identifier,
    columnName: String,
    columnType: String,
    grants: Seq[GrantSpec])
    extends LeafV2CommandExec {

  import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._

  override lazy val output: Seq[Attribute] = Nil

  override protected def run(): Seq[InternalRow] = {
    val icebergCatalog = catalog match {
      case spark: SparkCatalog => spark.icebergCatalog()
      case other =>
        throw new UnsupportedOperationException(
          s"Fused ADD COLUMN … GRANT requires an Iceberg SparkCatalog, got: $other")
    }

    val base = catalog.loadTable(ident) match {
      case iceberg: SparkTable => iceberg.table.schema()
      case other =>
        throw new UnsupportedOperationException(s"Not an Iceberg table: $other")
    }

    val tableIdent = Spark3Util.identifierToTableIdentifier(ident)
    val request: UpdateTableRequest =
      AddColumnWithGrants.assemble(tableIdent, columnName, columnType, grants, base)

    // ONE commit: metadata updates + sibling policy, co-committed atomically server-side.
    CatalogHandlers.updateTable(icebergCatalog, tableIdent, request)
    Nil
  }

  override def simpleString(maxFields: Int): String =
    s"AddColumnWithGrants ${catalog.name}.${ident.quoted} ADD COLUMN $columnName $columnType " +
      grants.map(g => s"${g.action} ${g.privilege}(${g.columns.mkString(",")})").mkString(" ")
}
