/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bdgenomics.avocado.calls.reads

import fi.tkk.ics.hadoop.bam.{ SAMRecordWritable, VariantContextWritable }
import java.io.{ File, OutputStream }
import java.lang.Runnable
import java.nio.file.Files
import java.util.concurrent.{ ExecutorService, Executors }
import org.apache.commons.configuration.SubnodeConfiguration
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.bdgenomics.formats.avro.ADAMRecord
import org.bdgenomics.adam.converters.VariantContextConverter
import org.bdgenomics.adam.models.{ SAMFileHeaderWritable, ADAMVariantContext, ReferencePosition }
import org.bdgenomics.adam.rdd.ADAMContext._
import org.bdgenomics.adam.rdd.GenomicRegionPartitioner
import org.bdgenomics.avocado.calls.{ VariantCall, VariantCallCompanion }
import org.bdgenomics.avocado.partitioners.PartitionSet
import org.bdgenomics.avocado.stats.AvocadoConfigAndStats
import org.broadinstitute.variant.variantcontext.VariantContext
import org.broadinstitute.variant.vcf.VCFFileReader
import net.sf.samtools.{ SAMFileHeader, SAMRecord, BAMFileWriter, SAMFileWriterImpl }
import scala.collection.JavaConversions._
import scala.util.Sorting

private[calls] object ReadCallExternal extends VariantCallCompanion {

  val callName = "ReadCallExternal"

  protected def apply(stats: AvocadoConfigAndStats,
                      config: SubnodeConfiguration,
                      partitions: PartitionSet): VariantCall = {
    // get command and partition count
    val cmd = config.getString("command")
    val partitions = config.getInt("partitions")

    new ReadCallExternal(stats.contigLengths,
      partitions,
      cmd)
  }
}

private[reads] class ReadCallExternal(contigLengths: Map[String, Long],
                                      numPart: Int,
                                      cmd: String) extends VariantCall {

  val companion = ReadCallExternal

  // create writer - this is a code stench; 
  // samtools suggests to pass a null file if using a stream
  // need to wrap a few private methods
  class BAMStreamWriter(stream: OutputStream) extends BAMFileWriter(stream, null) {

    def writeAlignment(record: SAMRecordWritable) {
      writeAlignment(record.get)
    }

    def writeHeader() {
      // this also is a stink...
      writeHeader("") //getFileHeader.getTextHeader)
    }
  }

  class ExternalWriter(records: Array[(ReferencePosition, SAMRecordWritable)],
                       header: SAMFileHeader,
                       stream: OutputStream) extends Runnable {
    def run() {
      val writer = new BAMStreamWriter(stream)

      // write header
      writer.setHeader(header)
      writer.writeHeader()

      var count = 0
      println("Starting to write records.")

      // cram data into standard in and flush
      records.foreach(r => {
        if (count % 1000 == 0) {
          stream.flush
          println("flush succeeded")
        }
        println("Have written " + count + " reads.")

        count += 1

        writer.writeAlignment(r._2)
      })

      // finish and close
      writer.close()
    }
  }

  def callVariants(iter: Iterator[(ReferencePosition, SAMRecordWritable)],
                   header: SAMFileHeaderWritable): Iterator[VariantContextWritable] = {

    // sort records
    val reads = iter.toArray
    Sorting.stableSort(reads, (kv1: (ReferencePosition, SAMRecordWritable), kv2: (ReferencePosition, SAMRecordWritable)) => {
      kv1._1.compare(kv2._1) == -1
    })
    println("have " + reads.length + " reads")

    // we can get empty partitions if we:
    // - have contigs that do not have reads mapped to them
    // - don't have unmapped reads in our dataset
    if (reads.length == 0) {
      // can't write a bam file of length 0 ;)
      Iterator()
    } else {
      // get temp directory for vcf output
      val tempDir = Files.createTempDirectory("vcf_out")

      // build snap process
      val cmdUpdated = cmd.replaceAll("::VCFOUT::",
        tempDir.toAbsolutePath.toString + "/calls.vcf")
      println("running: " + cmdUpdated)
      val pb = new ProcessBuilder(cmdUpdated.split(" ").toList)

      // redirect error and get i/o streams
      pb.redirectError(ProcessBuilder.Redirect.INHERIT)

      // start process and get pipes
      val process = pb.start()
      val inp = process.getOutputStream()

      // get thread pool with two threads
      val pool: ExecutorService = Executors.newFixedThreadPool(2)

      // build java list of things to execute
      pool.submit(new ExternalWriter(reads, header.header, inp))

      // wait for process to finish
      process.waitFor()

      // shut down pool
      pool.shutdown()

      println("process completed")

      // get vcf data - no index
      val vcfFile = new VCFFileReader(new File(tempDir.toAbsolutePath.toString + "/calls.vcf"), false)
      val iterator = vcfFile.iterator()
      var records = List[VariantContextWritable]()

      // loop and collect records
      while (iterator.hasNext()) {
        val record = iterator.next()

        // wrap variant context and append
        val vcw = new VariantContextWritable()
        vcw.set(record)
        records = vcw :: records
      }

      // need to close iterator - another code smell, but is required by samtools
      iterator.close()

      // convert back to iterator and return
      records.toIterator
    }
  }

  def call(rdd: RDD[ADAMRecord]): RDD[ADAMVariantContext] = {

    // get initial partition count
    val partitions = rdd.partitions.length

    // convert records from adam to sam/bam
    val (reads, header) = rdd.adamConvertToSAM()

    // broadcast header
    val hdrBcast = reads.context.broadcast(SAMFileHeaderWritable(header))

    println("have " + reads.count + " reads")

    // key reads by position and repartition
    val readsByPosition = reads.keyBy(r => ReferencePosition(r.get.getReferenceName.toString, r.get.getAlignmentStart))
      .partitionBy(new GenomicRegionPartitioner(numPart, contigLengths))

    println("have " + readsByPosition.count + " reads after partitioning")
    readsByPosition.foreachPartition(i => println("have " + i.length + " reads per partition"))

    // map partitions to external program
    val variants = readsByPosition.mapPartitions(r => callVariants(r, hdrBcast.value))

    // convert variants to adam format, coalesce, and return
    val converter = new VariantContextConverter()
    variants.flatMap(vc => converter.convert(vc.get))
      .coalesce(partitions, true)
  }

  def isCallable(): Boolean = true
}
