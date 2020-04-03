// Copyright © 2017-2019 Ultimate Software Group. <https://www.ultimatesoftware.com>

package com.ultimatesoftware.akka.streams.kafka

import java.util.Properties

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{ Committer, Consumer }
import akka.kafka.{ CommitterSettings, ConsumerMessage, ConsumerSettings, Subscriptions }
import akka.stream.Materializer
import akka.stream.scaladsl.{ Keep, Source }
import com.typesafe.config.{ Config, ConfigFactory }
import com.ultimatesoftware.config.TimeoutConfig
import com.ultimatesoftware.scala.core.kafka.{ KafkaSecurityConfiguration, KafkaTopic }
import org.apache.kafka.clients.consumer.{ ConsumerConfig, ConsumerConfigExtension }
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, StringDeserializer }
import org.slf4j.{ Logger, LoggerFactory }

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }

trait KafkaConsumerTrait {
  private val log: Logger = LoggerFactory.getLogger(getClass)
  def actorSystem: ActorSystem

  private def defaultCommitterSettings = CommitterSettings(actorSystem)

  private val defaultParallelism = 10

  def createCommittableSource(
    topic: KafkaTopic,
    consumerSettings: ConsumerSettings[String, Array[Byte]]): Source[ConsumerMessage.CommittableMessage[String, Array[Byte]], Consumer.Control] = {
    Consumer.committableSource(consumerSettings, Subscriptions.topics(topic.name))
  }

  def commitOffsetSinkAndRun(
    source: Source[ConsumerMessage.Committable, Consumer.Control],
    committerSettings: CommitterSettings = defaultCommitterSettings)(implicit materializer: Materializer): DrainingControl[Done] = {
    source
      .toMat(Committer.sink(committerSettings))(Keep.both)
      .mapMaterializedValue(DrainingControl.apply)
      .run()
  }

  def streamAndCommitOffsets(
    topic: KafkaTopic,
    business: (String, Array[Byte]) ⇒ Future[Done],
    parallelism: Int = defaultParallelism,
    consumerSettings: ConsumerSettings[String, Array[Byte]],
    committerSettings: CommitterSettings = defaultCommitterSettings)(implicit mat: Materializer, ec: ExecutionContext): Consumer.DrainingControl[Done] = {
    val source = createCommittableSource(topic, consumerSettings)

    val flow = source.mapAsync(parallelism) { msg ⇒
      business(msg.record.key, msg.record.value)
        .map(_ ⇒ msg.committableOffset)
        .recover {
          case e ⇒
            log.error(
              "An exception was thrown by the business logic! The stream will be stopped and must be manually restarted.  If you hit this often " +
                "you can try the **experimental** managed stream implementation by setting `surge.use-new-consumer = true` in your configuration.",
              e)
            throw e
        }
    }

    commitOffsetSinkAndRun(flow, committerSettings)
  }

}

case class KafkaConsumer()(implicit val actorSystem: ActorSystem) extends KafkaConsumerTrait
object KafkaConsumer extends KafkaSecurityConfiguration {
  private val config: Config = ConfigFactory.load()
  def consumerSettings(actorSystem: ActorSystem, groupId: String): ConsumerSettings[String, Array[Byte]] = {
    val baseSettings = ConsumerSettings[String, Array[Byte]](actorSystem, Some(new StringDeserializer()), Some(new ByteArrayDeserializer()))

    val securityProps = new Properties()
    configureSecurityProperties(securityProps)

    val brokers = config.getString("kafka.brokers")
    baseSettings
      .withBootstrapServers(brokers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, TimeoutConfig.Kafka.consumerSessionTimeout.toMillis.toString)
      .withProperty(ConsumerConfigExtension.LEAVE_GROUP_ON_CLOSE_CONFIG, TimeoutConfig.debugTimeoutEnabled.toString)
      .withProperties(securityProps.asScala.toMap)
  }
}
