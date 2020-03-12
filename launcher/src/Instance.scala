package launcher
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model._

import scala.collection.JavaConverters._

object Instance{
  val devboxVersion = "102"

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
                            log: String => Unit): Instance = {
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
            ephemeral
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
              val outOfDate = currentInstance
                .tags()
                .asScala
                .exists(t => t.key() == "devbox_version" && t.value != devboxVersion || forceNewInstance)

              if (outOfDate){
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
            s"More than one running instance found: ${multiple.map(_.instanceId()).mkString(", ")}"
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
                     ephemeral: Boolean) = {

    ec2.runInstances(
      RunInstancesRequest.builder()
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
                  .value(devboxVersion)
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
        .networkInterfaces(
          InstanceNetworkInterfaceSpecification
            .builder()
            .groups(securityGroupIds:_*)
            .subnetId(subnetId.orNull)
            .associatePublicIpAddress(true)
            .deleteOnTermination(true)
            .deviceIndex(0)
            .build()

        )
        .userData(java.util.Base64.getEncoder.encodeToString(userdata.getBytes))
        .instanceInitiatedShutdownBehavior(
          if (ephemeral) ShutdownBehavior.TERMINATE else ShutdownBehavior.STOP
        )
        .build()
      )
  }

  def mangleKnownHosts(ipAddress: String, log: String => Unit) = {
    log("Attempting to resolve devbox")
    val knownHostsLines = os
      .read
      .lines(os.home / ".ssh" / "known_hosts")
      .filter(!_.startsWith(ipAddress))

    while({
      val p = os.proc("ssh-keyscan", ipAddress).call(check = false)
      val newKnownHosts = p.out.lines
      val rc = p.exitCode

      if (rc == 0 && newKnownHosts.exists(_.contains(ipAddress))) {

        log("Updating ~/.ssh/known_hosts")
        os.write.over(
          os.home / ".ssh" / "known_hosts",
          (knownHostsLines ++ newKnownHosts).mkString("\n")
        )
        false
      } else {
        Thread.sleep(5000)
        true
      }
    })()
  }

  def mangleSshConfig(configPath: os.Path,
                      ipAddress: String,
                      url: String,
                      sshPrivateKey: os.Path,
                      userName: String,
                      log: String => Unit) = {
    log(s"Updating $configPath Pointing $url to $ipAddress with key $sshPrivateKey")

    val hostLines = try os.read.lines(configPath) catch { case e: Throwable => Nil }

    if (!hostLines.contains(s"Include devbox-config-$url")){
      try os.copy(configPath, os.home / ".ssh" / "non-devbox-config")
      catch{case e: Throwable => /*donothing*/}

      os.write.over(configPath, s"Include devbox-config-$url\n" + hostLines.mkString("\n"))
    }

    os.write.over(
      os.home / ".ssh" / s"devbox-config-$url",
      s"""Host $url
         |    ForwardAgent yes
         |    Compression yes
         |    IdentityFile $sshPrivateKey
         |    Hostname $ipAddress
         |    User $userName
       """.stripMargin
    )
  }
}