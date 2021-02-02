package launcher
import java.io.EOFException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import devbox.common.Util
import io.sentry.event.Event.Level
import io.sentry.event.EventBuilder
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model._

import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap
import scala.concurrent.duration._

object Instance{
  def ensureInstanceRunning(ec2: Ec2Client,
                            instanceType: String,
                            amiId: String,
                            instanceName: String,
                            forceNewInstance0: Boolean,
                            userdata: String,
                            securityGroupIds: Seq[String],
                            subnetId: Option[String],
                            ebsVolumeSize: Int,
                            ebsVolumeType: String,
                            iamInstanceArn: Option[String],
                            tags: Seq[(String, String)],
                            ephemeral: Boolean,
                            log: String => Unit,
                            devboxVmVersion: String,
                            noTerminate: Boolean): Instance = {
    log(s"Ensuring instance running $instanceName")
    var forceNewInstance = forceNewInstance0
    while({
      val described = ec2.describeInstances(
        DescribeInstancesRequest.builder()
          .filters(
            Filter.builder()
              .name("tag:Name")
              .values(instanceName)
              .build()
          )
          .build()
      )

      val instances = nonTerminatedInstances(described)

      val continue = instances.toSeq match{
        case Nil =>
          createInstance(
            ec2,
            instanceType,
            amiId,
            instanceName,
            forceNewInstance,
            userdata,
            securityGroupIds,
            subnetId,
            ebsVolumeSize,
            ebsVolumeType,
            iamInstanceArn,
            tags,
            ephemeral,
            devboxVmVersion
          )
          true
        case Seq(currentInstance: Instance) =>
          currentInstance.state().name match {
            case InstanceStateName.PENDING | InstanceStateName.STOPPING =>
              log(
                s"Instance ${currentInstance.instanceId()} currently "+
                s"${currentInstance.state().name}, waiting for it to complete"
              )
              Thread.sleep(5000)
              true
            case InstanceStateName.STOPPED =>
              ec2.startInstances(
                StartInstancesRequest.builder().instanceIds(currentInstance.instanceId()).build()
              )
              true
            case InstanceStateName.RUNNING =>
              val existingDevboxVersion = currentInstance
                .tags()
                .asScala
                .collectFirst{case t if t.key() == "devbox_version" => t.value}
                .get

              if ((!noTerminate && (existingDevboxVersion != devboxVmVersion)) || forceNewInstance){
                if (!forceNewInstance) {
                  log(s"Devbox instance out of date, existingDevboxVersion:$existingDevboxVersion devboxVmVersion:$devboxVmVersion")
                  log(
                    "Press Enter to terminate instances and spin up a fresh one, or Ctrl-C to stop. " +
                      "Run the devbox with --no-terminate if you want to spin up the existing devbox " +
                      "and retrieve any work in progress. Note that syncing may not work as expected on " +
                      "an out of date devbox."
                  )
                  scala.Console.in.readLine()
                }
                log(s"Terminating old instance: ${currentInstance.instanceId()}")
                ec2.terminateInstances(
                  TerminateInstancesRequest.builder()
                    .instanceIds(currentInstance.instanceId())
                    .build()
                )
                true
              }else{
                log(s"Instance already running ${currentInstance.instanceId()}")
                return currentInstance
              }
            case _ => ???
          }
        case multiple =>
          val event = new EventBuilder()
            .withMessage("Multiple devbox instances running")
            .withLevel(Level.WARNING)
            .withTag("whoami", System.getProperty("user.name"))
          val instanceOptions = multiple.zipWithIndex.map { case (instance, idx) =>
            val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
            val launchTime = dateFormatter.format(instance.launchTime())
            event.withExtra(s"instance-${idx}", Map(
              "id" -> instance.instanceId,
              "region" -> instance.placement().availabilityZone(),
              "launchTime" -> launchTime,
              "state" -> instance.state().nameAsString()
            ))
            s"\t[${idx + 1}] ${instance.instanceId()} (${instance.placement().availabilityZone()}) launched at ${launchTime}"
          }.mkString("\n")
          Util.sentryCapture(event.build())
          log(s"Multiple Devbox instances found. Which one would you like to keep? (enter 0 to discard all and get a new one)\n" + instanceOptions)
          val chosen = readChoice(log)
          val idsToDelete = (multiple.take(chosen) ++ multiple.drop(chosen + 1)).map(_.instanceId())
          log(s"Terminating instances: ${idsToDelete.mkString(", ")}")
          ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(idsToDelete:_*).build())

          // Wait for the instances to terminate
          val timeout = 60.seconds.fromNow
          while ({
            Thread.sleep(2000)
            val instances = nonTerminatedInstances(ec2.describeInstances(
              DescribeInstancesRequest.builder().instanceIds(idsToDelete:_*)
                .build()
            ))
            !instances.isEmpty && timeout.hasTimeLeft()
          })()
          true
      }
      forceNewInstance = false
      continue
    })()
    ???
  }

  def readChoice(log: String => Unit): Int = {
    while(true) {
      try {
        val choice = scala.io.StdIn.readInt() - 1
        return choice
      } catch {
        case _: NumberFormatException => log("Please enter a number")
        case _: EOFException => System.exit(1)
      }
    }
    ???
  }

  def nonTerminatedInstances(response: DescribeInstancesResponse) = {
    response.reservations().asScala.flatMap(_.instances().asScala)
      .filter(instance => {
        val name = instance.state().name
        name != InstanceStateName.SHUTTING_DOWN && name != InstanceStateName.TERMINATED
      })
  }



  def createInstance(ec2: Ec2Client,
                     instanceType: String,
                     amiId: String,
                     instanceName: String,
                     newInstance: Boolean,
                     userdata: String,
                     securityGroupIds: Seq[String],
                     subnetId: Option[String],
                     ebsVolumeSize: Int,
                     ebsVolumeType: String,
                     iamInstanceArn: Option[String],
                     tags: Seq[(String, String)],
                     ephemeral: Boolean,
                     devboxVmVersion: String) = {

    val instanceTemplate = RunInstancesRequest.builder()
      .iamInstanceProfile(
        IamInstanceProfileSpecification
          .builder()
          .arn(iamInstanceArn.orNull)
          .build()
      )
      .imageId(amiId)
      .instanceType(InstanceType.fromValue(instanceType))
      .maxCount(1)
      .minCount(1)
      .monitoring(RunInstancesMonitoringEnabled.builder().enabled(true).build())
      .blockDeviceMappings(
        BlockDeviceMapping
          .builder()
          .deviceName("/dev/sda1")
          .ebs(
            EbsBlockDevice
              .builder()
              .volumeSize(ebsVolumeSize)
              .volumeType(VolumeType.fromValue(ebsVolumeType))
              .build()
          )
          .build()
      )
      .tagSpecifications(
        TagSpecification.builder()
          .resourceType(ResourceType.INSTANCE)
          .tags(
            Seq(
              Tag.builder()
                .key("Name")
                .value(instanceName)
                .build(),
              Tag.builder()
                .key("devbox_version")
                .value(devboxVmVersion)
                .build()
            ) ++ tags.map{case (k, v) =>
              Tag.builder()
                .key(k)
                .value(v)
                .build()
            }:_*
          )
          .build(),
        TagSpecification.builder()
          .resourceType(ResourceType.VOLUME)
          .tags(
            tags.map{case (k, v) =>
              Tag.builder()
                .key(k)
                .value(v)
                .build()
            }:_*
          )
          .build()
      )
      .userData(java.util.Base64.getEncoder.encodeToString(userdata.getBytes))
      .instanceInitiatedShutdownBehavior(
        if (ephemeral) ShutdownBehavior.TERMINATE else ShutdownBehavior.STOP
      )
    ec2.runInstances(
      subnetId match{
        case None =>
          if (securityGroupIds.isEmpty) instanceTemplate.build()
          else instanceTemplate.securityGroupIds(securityGroupIds:_*).build()

        case Some(subnetId) =>
          val networkTemplate = InstanceNetworkInterfaceSpecification
            .builder()
            .subnetId(subnetId)
            .associatePublicIpAddress(true)
            .deleteOnTermination(true)
            .deviceIndex(0)
          instanceTemplate
            .networkInterfaces(
              if (securityGroupIds.isEmpty) networkTemplate.build()
              else networkTemplate.groups(securityGroupIds:_*).build()
            )
            .build()
      }
    )
  }

}
