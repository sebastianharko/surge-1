// Copyright © 2017-2020 UKG Inc. <https://www.ukg.com>

package surge.core

import java.time.Instant

import akka.actor.Status.Failure
import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.pattern.{ AskTimeoutException, ask }
import akka.testkit.{ TestKit, TestProbe }
import akka.util.Timeout
import org.apache.kafka.clients.producer.{ ProducerRecord, RecordMetadata }
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.{ AuthorizationException, ProducerFencedException }
import org.apache.kafka.common.header.internals.{ RecordHeader, RecordHeaders }
import org.apache.kafka.streams.{ LagInfo, MockLagInfo }
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{ PatienceConfiguration, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import surge.core.KafkaProducerActor.{ PublishFailure, PublishSuccess }
import surge.core.KafkaProducerActorImpl.{ AggregateStateRates, KTableProgressUpdate }
import surge.kafka.streams.{ AggregateStateStoreKafkaStreams, HealthCheck, HealthCheckStatus, HealthyActor }
import surge.metrics.Metrics
import surge.scala.core.kafka.{ KafkaBytesProducer, KafkaRecordMetadata }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class KafkaProducerActorSpec extends TestKit(ActorSystem("KafkaProducerActorSpec")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll
  with TestBoundedContext with MockitoSugar with ScalaFutures with PatienceConfiguration {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(
    timeout = Span(3, Seconds), interval = Span(10, Millis))

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }
  private def createRecordMeta(topic: String, partition: Int, offset: Int): RecordMetadata = {
    new RecordMetadata(
      new TopicPartition(topic, partition), 0, offset, Instant.now.toEpochMilli,
      0L, 0, 0)
  }

  private def mockStateStoreLags(assignedPartition: TopicPartition, currentOffset: Long, endOffset: Long): Map[String, Map[Integer, LagInfo]] = {
    Map(
      "exampleStateStore" -> Map(
        java.lang.Integer.valueOf(assignedPartition.partition()) -> new MockLagInfo(currentOffset, endOffset),
        java.lang.Integer.valueOf(assignedPartition.partition() + 1) -> new MockLagInfo(0, 10),
        java.lang.Integer.valueOf(assignedPartition.partition() + 2) -> new MockLagInfo(0, 8)))
  }

  private def mockStateStoreReturningOffset(
    assignedPartition: TopicPartition,
    currentOffset: Long, endOffset: Long): AggregateStateStoreKafkaStreams[String] = {
    val mockStateStore = mock[AggregateStateStoreKafkaStreams[String]]
    when(mockStateStore.partitionLags()(any[ExecutionContext])).thenReturn(Future.successful(mockStateStoreLags(assignedPartition, currentOffset, endOffset)))

    mockStateStore
  }

  private def testProducerActor(
    assignedPartition: TopicPartition,
    mockProducer: KafkaBytesProducer, mockStateStore: AggregateStateStoreKafkaStreams[_]): ActorRef = {
    val actor = system.actorOf(Props(
      new KafkaProducerActorImpl(assignedPartition, Metrics.globalMetricRegistry, businessLogic, mockStateStore, Some(mockProducer))))
    // Blocks the execution to wait until the actor is ready so we know its subscribed to the event bus
    system.actorSelection(actor.path).resolveOne()(Timeout(patienceConfig.timeout)).futureValue
    actor
  }

  private def mockRecordMetadata(assignedPartition: TopicPartition): KafkaRecordMetadata[String] = {
    val recordMeta = createRecordMeta(assignedPartition.topic(), assignedPartition.partition(), 1)
    new KafkaRecordMetadata[String](None, recordMeta)
  }

  private def testObjects(strings: Seq[String]): Seq[KafkaProducerActor.MessageToPublish] = {
    strings.map { str =>
      KafkaProducerActor.MessageToPublish(str, str.getBytes(), new RecordHeaders().add(new RecordHeader("object_name", str.getBytes())))
    }
  }
  private def records(assignedPartition: TopicPartition, events: Seq[KafkaProducerActor.MessageToPublish],
    state: KafkaProducerActor.MessageToPublish): Seq[ProducerRecord[String, Array[Byte]]] = {
    val eventRecords = events.map { event =>
      new ProducerRecord(businessLogic.kafka.eventsTopic.name, null, event.key, event.value, event.headers) // scalastyle:ignore null
    }
    val stateRecord = new ProducerRecord(businessLogic.kafka.stateTopic.name, assignedPartition.partition(), state.key, state.value, state.headers)

    eventRecords :+ stateRecord
  }

  private def setupTransactions(mockProducer: KafkaBytesProducer): Unit = {
    when(mockProducer.initTransactions()(any[ExecutionContext])).thenReturn(Future.unit)
    doNothing().when(mockProducer).beginTransaction()
    doNothing().when(mockProducer).abortTransaction()
    doNothing().when(mockProducer).commitTransaction()
  }

  "KafkaProducerActorImpl" should {
    val testEvents1 = testObjects(Seq("event1", "event2", "event3"))
    val testAggs1 = KafkaProducerActor.MessageToPublish("agg1", "agg1".getBytes(), new RecordHeaders())
    val testEvents2 = testObjects(Seq("event3", "event4"))
    val testAggs2 = KafkaProducerActor.MessageToPublish("agg3", "agg3".getBytes(), new RecordHeaders())

    "Return a failed future when the ask to the underlying publisher actor times out" in {
      val probe = TestProbe()
      val producer = new KafkaProducerActor[String, String](probe.ref, Metrics.globalMetricRegistry, "test-aggregate", new TopicPartition("testTopic", 1))

      val errorWatchProbe = TestProbe()
      val stateToPublish = KafkaProducerActor.MessageToPublish("test", "test".getBytes(), new RecordHeaders())
      val eventsToPublish = Seq(KafkaProducerActor.MessageToPublish("test", "test".getBytes(), new RecordHeaders()))
      producer.publish("test", stateToPublish, eventsToPublish).map { msg =>
        fail(s"Expected a failed future but received successful future with message [$msg]")
      }.recover {
        case e => errorWatchProbe.ref ! e
      }
      probe.expectMsg(KafkaProducerActorImpl.Publish(eventsToPublish = eventsToPublish, state = stateToPublish))
      errorWatchProbe.expectMsgType[AskTimeoutException](10.seconds)
    }

    "Recovers from beginTransaction failure" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducer = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)
      when(mockProducer.initTransactions()(any[ExecutionContext])).thenReturn(Future.unit)
      // Fail first transaction and then succeed always
      doThrow(new IllegalStateException("This is expected")).doNothing().when(mockProducer).beginTransaction()
      doNothing().when(mockProducer).abortTransaction()
      doNothing().when(mockProducer).commitTransaction()

      when(mockProducer.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val actor = testProducerActor(assignedPartition, mockProducer, mockStateStore)
      expectNoMessage()
      // First time beginTransaction will fail and commitTransaction won't be executed
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(actor, KafkaProducerActorImpl.FlushMessages)
      expectNoMessage()
      verify(mockProducer, times(1)).beginTransaction()
      verify(mockProducer, times(0)).commitTransaction()
      expectNoMessage()
      verify(mockProducer, times(1)).beginTransaction()
      // Actor should recover from begin transaction error and successfully commit
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(actor, KafkaProducerActorImpl.FlushMessages)
      expectNoMessage()
      verify(mockProducer, times(2)).beginTransaction()
      verify(mockProducer, times(1)).commitTransaction()
    }

    "Recovers from abortTransaction failure" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducer = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)
      when(mockProducer.initTransactions()(any[ExecutionContext])).thenReturn(Future.unit)
      // Fail first transaction and then succeed always
      doNothing().when(mockProducer).beginTransaction()
      doNothing().when(mockProducer).close()
      doThrow(new IllegalStateException("This is expected")).when(mockProducer).abortTransaction()
      doThrow(new IllegalStateException("This is expected")).doNothing().when(mockProducer).commitTransaction()

      when(mockProducer.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val actor = testProducerActor(assignedPartition, mockProducer, mockStateStore)
      expectNoMessage()
      // First time beginTransaction will fail and commitTransaction won't be executed
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(actor, KafkaProducerActorImpl.FlushMessages)
      expectNoMessage()
      verify(mockProducer, times(1)).beginTransaction()
      verify(mockProducer, times(1)).commitTransaction()
      verify(mockProducer, times(1)).abortTransaction()
      verify(mockProducer, times(1)).close()
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(actor, KafkaProducerActorImpl.FlushMessages)
      expectNoMessage()
      verify(mockProducer, times(2)).beginTransaction()
      verify(mockProducer, times(2)).commitTransaction()
    }

    "Gets to initialize the state if initializing kafka transactions fails with any error" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducer = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)

      when(mockProducer.initTransactions()(any[ExecutionContext]))
        .thenReturn(Future.failed(new AuthorizationException("This is expected")))
        .thenReturn(Future.failed(new IllegalStateException("This is expected")))
        .thenReturn(Future.unit)
      when(mockProducer.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val actor = testProducerActor(assignedPartition, mockProducer, mockStateStore)
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(actor, KafkaProducerActorImpl.FlushMessages)

      awaitAssert({
        verify(mockProducer, times(3)).initTransactions()(any[ExecutionContext])
        verify(mockProducer, times(1)).beginTransaction()
        verify(mockProducer, times(1)).commitTransaction()
      }, 11.seconds, 10.seconds)
    }

    "Stash IsAggregateStateCurrent messages until fully initialized when no messages inflight" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducer = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)
      setupTransactions(mockProducer)
      when(mockProducer.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      val mockStateStore = mock[AggregateStateStoreKafkaStreams[String]]
      when(mockStateStore.partitionLags()(any[ExecutionContext])).thenReturn(
        Future.successful(mockStateStoreLags(assignedPartition, 0, 10)),
        Future.successful(mockStateStoreLags(assignedPartition, 10, 10)))

      val actor = testProducerActor(assignedPartition, mockProducer, mockStateStore)
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      // Send IsAggregateStateCurrent messages to stash
      val isAggregateStateCurrent = KafkaProducerActorImpl.IsAggregateStateCurrent("bar", Instant.now.plusSeconds(10L))
      probe.send(actor, isAggregateStateCurrent)
      // Verify that we haven't initialized transactions yet so we are in the uninitialized state and messages were stashed
      verify(mockProducer, times(0)).initTransactions()(any[ExecutionContext])
      val response = actor.ask(isAggregateStateCurrent)(10.seconds).map(Some(_)).recoverWith { case _ => Future.successful(None) }
      assert(Await.result(response, 10.seconds).isDefined)
    }

    "Answer to IsAggregateStateCurrent when messages in flight" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducer = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)
      setupTransactions(mockProducer)
      when(mockProducer.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))
      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val actor = testProducerActor(assignedPartition, mockProducer, mockStateStore)
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      // Wait until published message is committed to be sure we are processing
      awaitAssert({
        verify(mockProducer, times(1)).commitTransaction()
      }, 10.seconds, 1.second)

      val barRecord1 = KafkaRecordMetadata(Some("bar"), createRecordMeta("testTopic", 0, 0))
      probe.send(actor, KafkaProducerActorImpl.EventsPublished(Seq(probe.ref), Seq(barRecord1)))
      val isAggregateStateCurrent = KafkaProducerActorImpl.IsAggregateStateCurrent("bar", Instant.now.plusSeconds(10L))
      val response = actor.ask(isAggregateStateCurrent)(10.seconds).map(Some(_)).recoverWith { case _ => Future.successful(None) }
      assert(Await.result(response, 3.second).isDefined)
    }

    "Stash Publish messages and publish them when fully initialized" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducer = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)
      setupTransactions(mockProducer)
      when(mockProducer.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))
      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val actor = testProducerActor(assignedPartition, mockProducer, mockStateStore)
      // Send publish messages to stash
      probe.send(actor, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      // Verify that we haven't initialized transactions yet so we are in the uninitialized state and messages were stashed
      verify(mockProducer, times(0)).initTransactions()(any[ExecutionContext])
      // Wait until the stashed messages gets committed
      awaitAssert({
        verify(mockProducer, times(1)).commitTransaction()
      }, 10.seconds, 1.second)
    }

    "Try to publish new incoming messages to Kafka if publishing to Kafka fails" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducerFailsPutRecords = mock[KafkaBytesProducer]
      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val failingPut = testProducerActor(assignedPartition, mockProducerFailsPutRecords, mockStateStore)

      val mockMetadata = mockRecordMetadata(assignedPartition)
      setupTransactions(mockProducerFailsPutRecords)
      when(mockProducerFailsPutRecords.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.failed(new RuntimeException("This is expected"))), Seq(Future.successful(mockMetadata)))

      probe.send(failingPut, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(failingPut, KafkaProducerActorImpl.FlushMessages)
      probe.expectMsgType[PublishFailure]
      verify(mockProducerFailsPutRecords).beginTransaction()
      verify(mockProducerFailsPutRecords).putRecords(records(assignedPartition, testEvents1, testAggs1))
      verify(mockProducerFailsPutRecords).abortTransaction()

      probe.send(failingPut, KafkaProducerActorImpl.Publish(testAggs2, testEvents2))
      probe.send(failingPut, KafkaProducerActorImpl.FlushMessages)
      probe.expectMsg(PublishSuccess)
      verify(mockProducerFailsPutRecords).putRecords(records(assignedPartition, testEvents2, testAggs2))
      verify(mockProducerFailsPutRecords).commitTransaction()
    }

    "Try to publish new incoming messages to Kafka if committing to Kafka fails" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducerFailsCommit = mock[KafkaBytesProducer]
      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val failingCommit = testProducerActor(assignedPartition, mockProducerFailsCommit, mockStateStore)

      when(mockProducerFailsCommit.initTransactions()(any[ExecutionContext])).thenReturn(Future.unit)
      doNothing().when(mockProducerFailsCommit).beginTransaction()
      doNothing().when(mockProducerFailsCommit).abortTransaction()
      doThrow(new RuntimeException("This is expected")).when(mockProducerFailsCommit).commitTransaction()

      val mockMetadata = mockRecordMetadata(assignedPartition)
      when(mockProducerFailsCommit.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      probe.send(failingCommit, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(failingCommit, KafkaProducerActorImpl.FlushMessages)
      probe.expectMsgType[PublishFailure]
      verify(mockProducerFailsCommit).beginTransaction()
      verify(mockProducerFailsCommit).putRecords(records(assignedPartition, testEvents1, testAggs1))
      verify(mockProducerFailsCommit).commitTransaction()
      verify(mockProducerFailsCommit).abortTransaction()

      // Since we can't stub the void method to throw an exception and then succeed, we just care that the actor attempts to send the next set of records
      probe.send(failingCommit, KafkaProducerActorImpl.Publish(testAggs2, testEvents2))
      probe.send(failingCommit, KafkaProducerActorImpl.FlushMessages)
      probe.expectMsgType[PublishFailure]
      verify(mockProducerFailsCommit).putRecords(records(assignedPartition, testEvents2, testAggs2))
    }

    "Stop if the producer is fenced out by another instance with the same transaction id" in {
      val probe = TestProbe()
      val assignedPartition = new TopicPartition("testTopic", 1)
      val mockProducerFenceOnBegin = mock[KafkaBytesProducer]
      val mockProducerFenceOnCommit = mock[KafkaBytesProducer]
      val mockMetadata = mockRecordMetadata(assignedPartition)

      when(mockProducerFenceOnBegin.initTransactions()(any[ExecutionContext])).thenReturn(Future.unit)
      doThrow(new ProducerFencedException("This is expected")).when(mockProducerFenceOnBegin).beginTransaction()
      doNothing().when(mockProducerFenceOnBegin).abortTransaction()
      doNothing().when(mockProducerFenceOnBegin).commitTransaction()
      when(mockProducerFenceOnBegin.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      when(mockProducerFenceOnCommit.initTransactions()(any[ExecutionContext])).thenReturn(Future.unit)
      doNothing().when(mockProducerFenceOnCommit).beginTransaction()
      doNothing().when(mockProducerFenceOnCommit).abortTransaction()
      doThrow(new ProducerFencedException("This is expected")).when(mockProducerFenceOnCommit).commitTransaction()
      when(mockProducerFenceOnCommit.putRecords(any[Seq[ProducerRecord[String, Array[Byte]]]]))
        .thenReturn(Seq(Future.successful(mockMetadata)))

      val mockStateStore = mockStateStoreReturningOffset(assignedPartition, 100L, 100L)
      val fencedOnBegin = testProducerActor(assignedPartition, mockProducerFenceOnBegin, mockStateStore)
      val fencedOnCommit = testProducerActor(assignedPartition, mockProducerFenceOnCommit, mockStateStore)

      probe.watch(fencedOnBegin)
      probe.send(fencedOnBegin, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(fencedOnBegin, KafkaProducerActorImpl.FlushMessages)
      probe.expectTerminated(fencedOnBegin)
      verify(mockProducerFenceOnBegin).beginTransaction()
      verify(mockProducerFenceOnBegin, times(0)).putRecords(records(assignedPartition, testEvents1, testAggs1))

      probe.watch(fencedOnCommit)
      probe.send(fencedOnCommit, KafkaProducerActorImpl.Publish(testAggs1, testEvents1))
      probe.send(fencedOnCommit, KafkaProducerActorImpl.FlushMessages)
      probe.expectTerminated(fencedOnCommit)
      verify(mockProducerFenceOnCommit).beginTransaction()
      verify(mockProducerFenceOnCommit).putRecords(records(assignedPartition, testEvents1, testAggs1))
      verify(mockProducerFenceOnCommit).commitTransaction()
    }
  }

  "KafkaProducerActor" should {
    def producerMock(testProbe: TestProbe): KafkaProducerActor[String, String] = {
      new KafkaProducerActor[String, String](testProbe.ref, Metrics.globalMetricRegistry, "test-aggregate-name", new TopicPartition("testTopic", 1))
    }
    "Terminate an underlying actor by sending a PoisonPill" in {
      val probe = TestProbe()
      val shouldBeTerminatedProbe = TestProbe()
      val producer = producerMock(shouldBeTerminatedProbe)
      probe.watch(shouldBeTerminatedProbe.ref)

      producer.terminate()
      probe.expectTerminated(shouldBeTerminatedProbe.ref)
    }

    "Check with the underlying actor if an aggregate is up to date in the KTable or not" in {
      val probe = TestProbe()
      val producer = producerMock(probe)

      val aggId1 = "testAggId1"
      val futureResponse1 = producer.isAggregateStateCurrent(aggId1)
      val receivedMsg1 = probe.expectMsgType[KafkaProducerActorImpl.IsAggregateStateCurrent]
      receivedMsg1.aggregateId shouldEqual aggId1
      probe.reply(true)
      Await.result(futureResponse1, 3.seconds) shouldEqual true

      val aggId2 = "testAggId2"
      val futureResponse2 = producer.isAggregateStateCurrent(aggId2)
      val receivedMsg2 = probe.expectMsgType[KafkaProducerActorImpl.IsAggregateStateCurrent]
      receivedMsg2.aggregateId shouldEqual aggId2
      probe.reply(false)
      Await.result(futureResponse2, 3.seconds) shouldEqual false
    }

    "Ask the underlying actor if it's healthy when performing a health check" in {
      val probe = TestProbe()
      val producer = producerMock(probe)

      val expectedHealthCheck = HealthCheck("test-health-check", "health-check-id", HealthCheckStatus.UP)
      val futureResult = producer.healthCheck()
      probe.expectMsg(HealthyActor.GetHealth)
      probe.reply(expectedHealthCheck)
      Await.result(futureResult, 3.seconds) shouldEqual expectedHealthCheck
    }

    "Report unhealthy if theres an error getting health from the underlying actor" in {
      val probe = TestProbe()
      val producer = producerMock(probe)

      val futureResult = producer.healthCheck()
      probe.expectMsg(HealthyActor.GetHealth)
      probe.reply(Failure(new RuntimeException("This is expected")))
      val result = Await.result(futureResult, 3.seconds)
      result.status shouldEqual HealthCheckStatus.DOWN
    }
  }

  "KafkaProducerActorState" should {
    implicit val unusedStateParent: ActorRef = TestProbe().ref
    implicit val rates: KafkaProducerActorImpl.AggregateStateRates = AggregateStateRates("test-aggregate", Metrics.globalMetricRegistry)

    val fooRecord1 = KafkaRecordMetadata(Some("foo"), createRecordMeta("testTopic", 0, 1))
    val barRecord1 = KafkaRecordMetadata(Some("bar"), createRecordMeta("testTopic", 0, 2))
    val bazRecord1 = KafkaRecordMetadata(Some("baz"), createRecordMeta("testTopic", 0, 3))
    val fooRecord2 = KafkaRecordMetadata(Some("foo"), createRecordMeta("testTopic", 0, 4))
    val barRecord2 = KafkaRecordMetadata(Some("bar"), createRecordMeta("testTopic", 0, 5))

    val exampleMetadata: Seq[KafkaRecordMetadata[String]] = Seq(fooRecord1, barRecord1, bazRecord1, fooRecord2)

    "Record new record metadata marked as in flight" in {
      val empty = KafkaProducerActorState.empty

      val newState = empty.addInFlight(exampleMetadata)
      newState.inFlight should contain allElementsOf Seq(barRecord1, bazRecord1, fooRecord2)

      newState.inFlightForAggregate("foo") should contain only fooRecord2
      newState.inFlightForAggregate("bar") should contain only barRecord1
      newState.inFlightForAggregate("baz") should contain only bazRecord1
      newState.inFlightForAggregate("missing") should have length 0

      val newState2 = newState.addInFlight(Seq(barRecord2))
      newState2.inFlightForAggregate("bar") should contain only barRecord2
    }

    "Track buffered pending writes" in {
      val empty = KafkaProducerActorState.empty

      val sender = TestProbe().ref
      val dummyState = KafkaProducerActor.MessageToPublish("foo", "foo".getBytes(), new RecordHeaders())
      val publishMsg = KafkaProducerActorImpl.Publish(dummyState, Seq(KafkaProducerActor.MessageToPublish("foo", "foo".getBytes(), new RecordHeaders())))

      val newState = empty.addPendingWrites(sender, publishMsg)
      newState.pendingWrites should contain only KafkaProducerActorImpl.PublishWithSender(sender, publishMsg)

      val flushedState = newState.flushWrites()
      flushedState.pendingWrites should have length 0
    }

    "Track pending aggregate initializations" in {
      val empty = KafkaProducerActorState.empty

      val upToDateProbe = TestProbe()
      val isStateCurrentMsg = KafkaProducerActorImpl.IsAggregateStateCurrent("bar", Instant.now.plusSeconds(10L))
      val expiredProbe = TestProbe()
      val isStateCurrentMsg2 = KafkaProducerActorImpl.IsAggregateStateCurrent("baz", Instant.now.minusSeconds(1L))
      val stillWaitingProbe = TestProbe()
      val isStateCurrentMsg3 = KafkaProducerActorImpl.IsAggregateStateCurrent("foo", Instant.now.plusSeconds(10L))

      val newState = empty.addInFlight(exampleMetadata).addPendingInitialization(upToDateProbe.ref, isStateCurrentMsg)
        .addPendingInitialization(expiredProbe.ref, isStateCurrentMsg2)
        .addPendingInitialization(stillWaitingProbe.ref, isStateCurrentMsg3)

      val expectedPendingInit = KafkaProducerActorImpl.PendingInitialization(upToDateProbe.ref, isStateCurrentMsg.aggregateId,
        isStateCurrentMsg.expirationTime)
      val expectedPendingInit2 = KafkaProducerActorImpl.PendingInitialization(expiredProbe.ref, isStateCurrentMsg2.aggregateId,
        isStateCurrentMsg2.expirationTime)
      val expectedPendingInit3 = KafkaProducerActorImpl.PendingInitialization(stillWaitingProbe.ref, isStateCurrentMsg3.aggregateId,
        isStateCurrentMsg3.expirationTime)
      newState.pendingInitializations should contain allElementsOf Seq(expectedPendingInit, expectedPendingInit2, expectedPendingInit3)

      val barRecordPartitionMeta = KTableProgressUpdate(
        topicPartition = new TopicPartition(barRecord1.wrapped.topic(), barRecord1.wrapped.partition()),
        lagInfo = new MockLagInfo(barRecord1.wrapped.offset(), barRecord2.wrapped.offset()))
      val processedState = newState.processedUpTo(barRecordPartitionMeta)
      processedState.pendingInitializations should contain only expectedPendingInit3

      upToDateProbe.expectMsg(true)
      expiredProbe.expectMsg(false)
      stillWaitingProbe.expectNoMessage()
    }

    "Calculate how long a transaction has been in progress for" in {
      val stateWithNoTransaction = KafkaProducerActorState.empty
      stateWithNoTransaction.currentTransactionTimeMillis shouldEqual 0L

      val expectedTransactionTime = 25L
      val transactionStartTime = Instant.now.minusMillis(expectedTransactionTime)
      val stateWithActiveTransaction = stateWithNoTransaction.copy(transactionInProgressSince = Some(transactionStartTime))
      stateWithActiveTransaction.currentTransactionTimeMillis shouldEqual expectedTransactionTime +- 2 // State creates a new Instant, so approximate is fine
    }
  }
}
