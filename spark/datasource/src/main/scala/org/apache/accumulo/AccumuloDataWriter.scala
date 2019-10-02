/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.accumulo

import org.apache.accumulo.core.client.Accumulo
import org.apache.accumulo.core.data.Mutation
import org.apache.accumulo.core.client.lexicoder._
import org.apache.hadoop.io.Text
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.v2.writer.{DataWriter, WriterCommitMessage}
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row

class AccumuloDataWriter (tableName: String, schema: StructType, mode: SaveMode, properties: java.util.Properties)
  extends DataWriter[InternalRow] {

    // val context = new ClientContext(properties)
    // TODO: construct BatchWriterConfig from properties if passed in
    // val batchWriter = new TabletServerBatchWriter(context, new BatchWriterConfig)
    // private val tableId = Tables.getTableId(context, tableName)

    private val client = Accumulo.newClient().from(properties).build();
    private val batchWriter = client.createBatchWriter(tableName)

    private val doubleEncoder = new DoubleLexicoder
    private val floatEncoder = new FloatLexicoder
    private val longEncoder = new LongLexicoder
    private val intEncoder = new IntegerLexicoder
    private val stringEncoder = new StringLexicoder

    private val doubleAccessor = InternalRow.getAccessor(DoubleType)
    private val floatAccessor = InternalRow.getAccessor(FloatType)
    private val longAccessor = InternalRow.getAccessor(LongType)
    private val intAccessor = InternalRow.getAccessor(IntegerType)
    private val stringAccessor = InternalRow.getAccessor(StringType)

    private def encode(record: InternalRow, fieldIdx: Int, field: StructField) = {
        field.dataType match {
            case DoubleType => doubleEncoder.encode(doubleAccessor(record, fieldIdx).asInstanceOf[Double])
            case FloatType => floatEncoder.encode(floatAccessor(record, fieldIdx).asInstanceOf[Float])
            case LongType => longEncoder.encode(longAccessor(record, fieldIdx).asInstanceOf[Long])
            case IntegerType => intEncoder.encode(intAccessor(record, fieldIdx).asInstanceOf[Integer])
            case StringType => stringEncoder.encode(stringAccessor(record, fieldIdx).asInstanceOf[String])
        }
    }

    private val structAccessor = InternalRow.getAccessor(new StructType())

    def write(record: InternalRow): Unit = {
        // TODO: iterating over the schema should be done outside of the write-loop
        schema.fields.zipWithIndex.foreach {
            // loop through fields
            case (cf: StructField, cfIdx: Int) => {

                // check which types we have top-level
                cf.dataType match {
                   case ct: StructType => {
                        val nestedRecord = structAccessor(record, cfIdx).asInstanceOf[InternalRow]

                        // TODO: use configurable row_id field
                        ct.fields.zipWithIndex.foreach {
                            case (cq: StructField, cqIdx) => batchWriter.addMutation(new Mutation(new Text("row_id"))
                                .at()
                                .family(cf.name)
                                .qualifier(cq.name)
                                .put(encode(nestedRecord, cqIdx, cq)))

                        }
                   }
                   case _ => batchWriter.addMutation(new Mutation(new Text("row_id"))
                          .at()
                          .family(cf.name)
                          .put(encode(record, cfIdx, cf)))
                }
           }
        }
    }

    def commit(): WriterCommitMessage = {
        batchWriter.flush()
        batchWriter.close()

        client.close()
        WriteSucceeded
    }

    def abort(): Unit = {
        batchWriter.close()
        client.close()
    }

    object WriteSucceeded extends WriterCommitMessage
}
