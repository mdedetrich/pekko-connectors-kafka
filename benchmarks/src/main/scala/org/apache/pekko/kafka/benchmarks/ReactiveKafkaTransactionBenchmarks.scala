/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2014 - 2016 Softwaremill <https://softwaremill.com>
 * Copyright (C) 2016 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.kafka.benchmarks

import org.apache.pekko
import pekko.kafka.ProducerMessage
import pekko.kafka.ProducerMessage.{ Result, Results }
import pekko.kafka.benchmarks.ReactiveKafkaTransactionFixtures._
import pekko.stream.Materializer
import pekko.stream.scaladsl.{ Keep, Sink }
import com.codahale.metrics.Meter
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.ProducerRecord

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }
import scala.language.postfixOps
import scala.util.Success

object ReactiveKafkaTransactionBenchmarks extends LazyLogging {
  val streamingTimeout: FiniteDuration = 30.minutes
  type TransactionFixture = ReactiveKafkaTransactionTestFixture[KTransactionMessage, KProducerMessage, KResult]

  /**
   * Process records in a consume-transform-produce transactional workflow and commit every interval.
   */
  def consumeTransformProduceTransaction(fixture: TransactionFixture,
      meter: Meter)(implicit mat: Materializer): Unit = {
    logger.debug("Creating and starting a stream")
    val msgCount = fixture.msgCount
    val sinkTopic = fixture.sinkTopic
    val source = fixture.source

    val promise = Promise[Unit]()
    val logPercentStep = 1
    val loggedStep = if (msgCount > logPercentStep) 100 else 1

    val control = source
      .map { msg =>
        ProducerMessage.single(new ProducerRecord[Array[Byte], String](sinkTopic, msg.record.value()),
          msg.partitionOffset)
      }
      .via(fixture.flow)
      .toMat(Sink.foreach {
        case result: Result[Key, Val, PassThrough] =>
          meter.mark()
          val offset = result.offset
          if (result.offset % loggedStep == 0)
            logger.info(s"Transformed $offset elements to Kafka (${100 * offset / msgCount}%)")
          if (result.offset >= fixture.msgCount - 1)
            promise.complete(Success(()))
        case other: Results[Key, Val, PassThrough] =>
          meter.mark()
          promise.complete(Success(()))
      })(Keep.left)
      .run()

    Await.result(promise.future, streamingTimeout)
    control.shutdown()
    logger.debug("Stream finished")
  }
}
