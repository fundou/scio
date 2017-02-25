/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.extra

import java.util.UUID

import com.spotify.scio.ScioContext
import com.spotify.scio.values.{SCollection, SideInput}
import com.spotify.sparkey.SparkeyReader
import org.apache.beam.sdk.transforms.{DoFn, View}
import org.apache.beam.sdk.values.PCollectionView
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
 * Main package for Sparkey side input APIs. Import all.
 *
 * {{{
 * import com.spotify.scio.extra.sparkey._
 * }}}
 *
 * To save an `SCollection[(String, String)]` to a Sparkey file:
 * {{{
 * val s = sc.parallelize(Seq("a" -> "one", "b" -> "two"))
 *
 * // temporary location
 * val s1: SCollection[SparkeyUri] = s.asSparkey
 *
 * // specific location
 * val s1: SCollection[SparkeyUri] = s.asSparkey("gs://<bucket>/<path>/<sparkey-prefix>")
 * }}}
 *
 * The result `SCollection[SparkeyUri]` can be converted to a side input:
 * {{{
 * val s: SCollection[SparkeyUri] = sc.parallelize(Seq("a" -> "one", "b" -> "two")).asSparkey
 * val side: SideInput[SparkeyReader] = s.asSparkeySideInput
 * }}}
 *
 * These two steps can be done with a syntactic sugar:
 * {{{
 * val side: SideInput[SparkeyReader] = sc
 *   .parallelize(Seq("a" -> "one", "b" -> "two"))
 *   .asSparkeySideInput
 * }}}
 *
 * An existing Sparkey file can also be converted to a side input directly:
 * {{{
 * sc.sparkeySideInput("gs://<bucket>/<path>/<sparkey-prefix>")
 * }}}
 *
 * `SparkeyReader` can be used like a lookup table in a side input operation:
 * {{{
 * val main: SCollection[String] = sc.parallelize(Seq("a", "b", "c"))
 * val side: SideInput[SparkeyReader] = sc
 *   .parallelize(Seq("a" -> "one", "b" -> "two"))
 *   .asSparkeySideInput
 *
 * main.withSideInputs(side)
 *   .map { (x, s) =>
 *     s(side).getOrElse(x, "unknown")
 *   }
 * }}}
 */
package object sparkey {

  /** Enhanced version of [[ScioContext]] with Sparkey methods. */
  implicit class SparkeyScioContext(val self: ScioContext) extends AnyVal {
    /**
     * Create a SideInput of `SparkeyReader` from a [[SparkeyUri]] base path, to be used with
     * [[com.spotify.scio.values.SCollection.withSideInputs SCollection.withSideInputs]].
     */
    def sparkeySideInput(basePath: String): SideInput[SparkeyReader] = {
      val uri = SparkeyUri(basePath, self.options)
      val view = self.parallelize(Seq(uri)).applyInternal(View.asSingleton())
      new SparkeySideInput(view)
    }
  }

  /**
   * Enhanced version of [[com.spotify.scio.values.SCollection SCollection]] with Sparkey methods.
   */
  implicit class SparkeyPairSCollection(val self: SCollection[(String, String)]) {

    private val logger = LoggerFactory.getLogger(this.getClass)

    /**
     * Write the key-value pairs of this SCollection as a Sparkey file to a specific location.
     *
     * @return A singleton SCollection containing the [[SparkeyUri]] of the saved files.
     */
    def asSparkey(basePath: String): SCollection[SparkeyUri] = {
      val uri = SparkeyUri(basePath, self.context.options)
      require(!uri.exists, s"Sparkey URI ${uri.basePath} already exists.")
      logger.info(s"Saving as sparkey: $uri")
      self.transform { in =>
        in.groupBy(_ => ())
          .map { case (_, xs) =>
            val writer = new SparkeyWriter(uri)
            val it = xs.iterator
            while (it.hasNext) {
              val kv = it.next()
              writer.put(kv._1.toString, kv._2.toString)
            }
            writer.close()
            uri
          }
      }
    }

    /**
     * Write the key-value pairs of this SCollection as a Sparkey file to a temporary location.
     *
     * @return A singleton SCollection containing the [[SparkeyUri]] of the saved files.
     */
    def asSparkey: SCollection[SparkeyUri] = {
      val uuid = UUID.randomUUID()
      val basePath = self.context.options.getTempLocation + s"/sparkey-$uuid"
      this.asSparkey(basePath)
    }

    /**
     * Convert this SCollection to a SideInput, mapping key-value pairs of each window to a
     * `SparkeyReader`, to be used with
     * [[com.spotify.scio.values.SCollection.withSideInputs SCollection.withSideInputs]]. It is
     * required that each key of the input be associated with a single value.
     */
    def asSparkeySideInput: SideInput[SparkeyReader] = self.asSparkey.asSparkeySideInput

  }

  /**
   * Enhanced version of [[com.spotify.scio.values.SCollection SCollection]] with Sparkey methods.
   */
  implicit class SparkeySCollection(val self: SCollection[SparkeyUri]) extends AnyVal {
    /**
     * Convert this SCollection to a SideInput of `SparkeyReader`, to be used with
     * [[com.spotify.scio.values.SCollection.withSideInputs SCollection.withSideInputs]].
     */
    def asSparkeySideInput: SideInput[SparkeyReader] = {
      val view = self.applyInternal(View.asSingleton())
      new SparkeySideInput(view)
    }
  }

  /** Enhanced version of `SparkeyReader` that mimics a `Map`. */
  implicit class RichSparkeyReader(val self: SparkeyReader) extends Map[String, String] {
    override def get(key: String): Option[String] = Option(self.getAsString(key))
    override def iterator: Iterator[(String, String)] =
      self.iterator.asScala.map(e => (e.getKeyAsString, e.getValueAsString))

    //scalastyle:off method.name
    override def +[B1 >: String](kv: (String, B1)): Map[String, B1] =
      throw new NotImplementedError("Sparkey-backed map; operation not supported.")
    override def -(key: String): Map[String, String] =
      throw new NotImplementedError("Sparkey-backed map; operation not supported.")
    //scalastyle:on method.name
  }

  private class SparkeySideInput(val view: PCollectionView[SparkeyUri])
    extends SideInput[SparkeyReader] {
    override def get[I, O](context: DoFn[I, O]#ProcessContext): SparkeyReader =
      SparkeyUri(context.sideInput(view).basePath, context.getPipelineOptions).getReader
  }

}