// Copyright © 2017-2021 UKG Inc. <https://www.ukg.com>

package surge.core

import akka.actor._
import surge.health.HealthSignalBusTrait
import surge.internal.SurgeModel
import surge.internal.akka.kafka.KafkaConsumerPartitionAssignmentTracker
import surge.internal.core.SurgePartitionRouterImpl
import surge.kafka.PersistentActorRegionCreator
import surge.kafka.streams._

trait SurgePartitionRouter extends HealthyComponent with Controllable {
  def actorRegion: ActorRef
}

object SurgePartitionRouter {
  def apply(
      system: ActorSystem,
      partitionTracker: KafkaConsumerPartitionAssignmentTracker,
      businessLogic: SurgeModel[_, _, _, _],
      regionCreator: PersistentActorRegionCreator[String],
      signalBus: HealthSignalBusTrait): SurgePartitionRouter = {
    new SurgePartitionRouterImpl(system, partitionTracker, businessLogic, regionCreator, signalBus)
  }
}
