package launcher
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model._

import scala.collection.JavaConverters._

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

      val instances = described.reservations().asScala.flatMap(_.instances().asScala)
          .filter(_.state().name != InstanceStateName.TERMINATED)

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
            case InstanceStateName.PENDING | InstanceStateName.STOPPING | InstanceStateName.SHUTTING_DOWN =>
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
                  log(s"Devbox instance out of data, existingDevboxVersion:$existingDevboxVersion devboxVmVersion:$devboxVmVersion")
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
          throw new Exception(
            s"More than one running instance found: ${multiple.map(_.instanceId()).mkString(", ")}. You can delete extra instances in the aws-dev-admin console (double check your region!)."
          )
      }
      forceNewInstance = false
      continue
    })()
    ???
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