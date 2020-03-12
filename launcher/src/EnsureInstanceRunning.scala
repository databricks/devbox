package launcher

import devbox.common.Cli
import devbox.common.Cli.Arg
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.services.ec2.Ec2Client

object EnsureInstanceRunning {

  val signature = Seq(
    Arg[EnsureInstanceRunning, String](
      "user-name", None,
      "What username to use when configuring Ubuntu on the devbox",
      (c, v) => c.copy(userName = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "instance-type", None,
      "What kind of instance to use; you can specify a bigger instance or more specialized instance if necessary",
      (c, v) => c.copy(instanceType = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "ssh-private-key", None,
      "path to the SSH private key to use",
      (c, v) => c.copy(sshPrivateKeyOpt = Some(os.Path(v)))
    ),
    Arg[EnsureInstanceRunning, String](
      "ssh-public-key", None,
      "path to the SSH public key to use",
      (c, v) => c.copy(sshPublicKeyOpt = Some(os.Path(v)))
    ),
    Arg[EnsureInstanceRunning, String](
      "ssh-config-path", None,
      "path to the SSH config to use",
      (c, v) => c.copy(sshConfigPath = os.Path(v))
    ),
    Arg[EnsureInstanceRunning, String](
      "ami-id", None,
      "What AMI to use; defaults to the same AMI as used for our jenkins slaves",
      (c, v) => c.copy(amiId = v)
    ),
    Arg[EnsureInstanceRunning, Unit](
      "ephemeral", None,
      "Use ephemeral local filesystem instead of EBS. Ephemeral instances have much " +
      "better disk performance than EBS-backed instances, but lose all their disk state " +
      "and need to be restarted and re-syncedfrom scratch every time they shut down. " +
      "must be used with a ephemeral-disk-compatible instance, e.g. --instance-type m5d.4xlarge",
      (c, v) => c.copy(ephemeral = true)
    ),
    Arg[EnsureInstanceRunning, Unit](
      "new-instance", None,
      "Create a new instance rather than re-using an existing one; " +
      "useful if your instance is in a bad state and you want to start a fresh",
      (c, v) => c.copy(forceNewInstance = true)
    ),
    Arg[EnsureInstanceRunning, String](
      "security-group-id", None,
      "Add a security group to your EC2 instance (can be passed more than once)",
      (c, v) => c.copy(securityGroupIds = c.securityGroupIds :+ v)
    ),
    Arg[EnsureInstanceRunning, String](
      "subnet-id", None,
      "",
      (c, v) => c.copy(subnetId = Some(v))
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-access-key-id", None,
      "",
      (c, v) => c.copy(awsAccessKeyId = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-secret-key-id", None,
      "",
      (c, v) => c.copy(awsSecretKeyId = v)
    ),
    Arg[EnsureInstanceRunning, Int](
      "ebs-volume-size", None,
      "How big do you want your EBS volume to be?",
      (c, v) => c.copy(ebsVolumeSize = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "ebs-volume-type", None,
      "What kind of EBS volume do you want? e.g. gp2, io1, st1 or sc1",
      (c, v) => c.copy(ebsVolumeType = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "iam-instance-arn", None,
      "Any IAM role that you want to give this instance",
      (c, v) => c.copy(ebsVolumeType = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "instance-tag", None,
      "Add a tag to give this instance, e.g. 'foo=bar' (can be passed more than once)",
      (c, v) => c.copy(tags = c.tags :+ (v.split("=", 2) match{case Array(k, v) => (k, v)}))
    ),
    Arg[EnsureInstanceRunning, String](
      "init-file", None,
      "Copy a file to the instance when first created. Note that this only supports text files, " +
      "and is limited by the 16k maximum userdata size on Amazon EC2",
      (c, v) => c.copy(initFiles =
        c.initFiles :+ (v.split(":", 2) match{
          case Array(k, v) => (os.read(os.Path(k, os.pwd)), v)
        })
      )
    ),
    Arg[EnsureInstanceRunning, String](
      "setup-script", None,
      "Run a script on the instance when first created. Note that this only supports text files, " +
      "and is limited by the 16k maximum userdata size on Amazon EC2",
      (c, v) => c.copy(setupScripts =
        c.setupScripts :+ (os.Path(v, os.pwd).last, os.read(os.Path(v, os.pwd)))
      )
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-region", None,
      "What AWS region to create your instance in",
      (c, v) => c.copy(awsRegion = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "prepare-instance-command", None,
      "",
      (c, v) => c.copy(prepareInstanceCommand = Some(v))
    ),
    Arg[EnsureInstanceRunning, String](
      "url", None,
      "The URL you want your instance to live at; e.g. --url devbox.databricks.com " +
      "will adjust your ~/.ssh/config file to allow you to connect to your instance " +
      "via `ssh devbox.databricks.com`",
      (c, v) => c.copy(url = v)
    ),
  )
  def main(args: Array[String]): Unit = {
    Cli.groupArgs(args.toList, signature, EnsureInstanceRunning()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((config, remaining)) =>
        config.main0(println)
        System.exit(0)
    }
  }
}
case class EnsureInstanceRunning(userName: String = os.proc("whoami").call().out.trim,
                                 ephemeral: Boolean = false,
                                 instanceType: String = "m5.4xlarge",
                                 sshPublicKeyOpt: Option[os.Path] = None,
                                 sshPrivateKeyOpt: Option[os.Path] = None,
                                 awsAccessKeyId: String = "",
                                 awsSecretKeyId: String = "",
                                 setupScripts: Seq[(String, String)] = Nil,
                                 forceNewInstance: Boolean = false,
                                 amiId: String = null,
                                 sshConfigPath: os.Path = os.home / ".ssh" / "config",
                                 securityGroupIds: Seq[String] = Nil,
                                 subnetId: Option[String] = None,
                                 ebsVolumeSize: Int = 500,
                                 ebsVolumeType: String = "gp2",
                                 iamInstanceArn: Option[String] = None,
                                 tags: Seq[(String, String)] = Nil,
                                 initFiles: Seq[(String, String)] = Nil,
                                 awsRegion: String = "us-west-2",
                                 prepareInstanceCommand: Option[String] = None,
                                 url: String = ""){
  def main0(log: String => Unit) = {
    assert(url != "", "--url parameter is required to define the URL of the devbox")

    val httpClient = software.amazon.awssdk.http.apache.ApacheHttpClient.builder().build()
    val ec2 = (awsAccessKeyId, awsSecretKeyId) match{
      case ("", "") =>
        Ec2Client
          .builder()
          .httpClient(httpClient)
          .region(software.amazon.awssdk.regions.Region.of(awsRegion))
          .build()
      case _ =>
        Ec2Client
          .builder()
          .httpClient(httpClient)
          .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(awsAccessKeyId, awsSecretKeyId))
          )
          .region(software.amazon.awssdk.regions.Region.of(awsRegion))
          .build()
    }


    val (sshPublicKey, sshPrivateKey) = (sshPublicKeyOpt, sshPrivateKeyOpt) match{
      case (Some(pub), Some(priv)) => (pub, priv)
      case (None, None) => (os.home / ".ssh" / "id_rsa.pub", os.home / ".ssh" / "id_rsa")
      case _ => throw new Exception(
        "--ssh-private-key and --ssh-public-key must be passed in together, or not at all. " +
        "Make sure you set both if you want to configure them"
      )
    }

    val pubKey = os.read(sshPublicKey)

    val emailPrefix = pubKey.split(" ").last.split("@").head

    val m = java.security.MessageDigest.getInstance("MD5")

    m.update(pubKey.getBytes)

    val pubkeyHash = javax.xml.bind.DatatypeConverter.printHexBinary(m.digest()).toLowerCase

    val instanceName = s"devbox-$emailPrefix-$url-$pubkeyHash"

    val setupScriptMappings =
      for(((fileName, fileContents), i) <- setupScripts.zipWithIndex)
      yield (s"/home/$userName/.devbox/setup-script-$i-$fileName", fileContents)

    val initFileMapping = Map(
      s"/home/$userName/.ssh/authorized_keys" -> pubKey,
      s"/home/$userName/.devbox/config.json" -> os.read(os.resource / "default_config.json"),
      // We use the raw script files, rather than building a pex, because the pex file
      // ends up too big to put into AWS's instance initialization userdata
      "/lib/systemd/system/devbox_daemon.service" -> os.read(os.resource / "daemon.service").replace("$DEVBOX_USER", userName),
      s"/home/$userName/.devbox/daemon.py" -> os.read(os.resource / "daemon.py"),
      s"/home/$userName/.devbox/setup_ephemeral_filesystem.py" -> os.read(os.resource / "setup_ephemeral_filesystem.py")
    ) ++
      setupScriptMappings ++
      initFiles.map{case (local, remote) => (remote, local)}



    val userdata = s"""#!/bin/bash
      export DEVBOX_USER=$userName
      sudo usermod -md /home/$userName -l $userName ubuntu
      sudo sh -c "echo '$userName ALL=NOPASSWD: ALL' > /etc/sudoers.d/devbox-user"
      mkdir -p /home/$userName/.ssh
      mkdir -m777 -p /home/$userName/.devbox
      mkdir -m777 -p /home/$userName/.aws
      mkdir -m777 -p /root/.aws

      ${
        initFileMapping
          .map{case (dest, data) =>
            s"""echo "${java.util.Base64.getEncoder.encodeToString(data.getBytes)}" | base64 --decode > $dest"""
          }
          .mkString("\n")
      }

      sudo systemctl enable devbox_daemon
      sudo systemctl start devbox_daemon
      sudo service sshd restart
      ${
        setupScriptMappings.map{case (path, _) => s"chmod +x $path; $path"}.mkString("\n")
      }
      ${if (ephemeral) s"sudo python /home/$userName/.devbox/setup_ephemeral_filesystem.py\n" else ""}
    """

    val instanceInfo = launcher.Instance.ensureInstanceRunning(
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
      log
    )

    val ipAddress = instanceInfo.publicIpAddress()

    launcher.Instance.mangleSshConfig(sshConfigPath, ipAddress, url, sshPrivateKey, userName, log)
    launcher.Instance.mangleKnownHosts(ipAddress, log)

    var connectCount = 0

    while({
      log(s"Attempting to connect to devbox $ipAddress")
      val whoami = os
        .proc("ssh", "-o", "ConnectTimeout=4", url, "whoami")
        .call(check = false)

      if (whoami.out.string == s"$userName\n") false
      else{
        connectCount += 1
        if (connectCount == 4) {
          throw new Exception(s"Failed to connect to $url after 5 attempts")
        } else {
          Thread.sleep(2000)
          true
        }
      }
    })()

    val isNewInstance =
      try os.read(os.home / ".devbox" / s"instance_id-$url") != instanceInfo.instanceId()
      catch{case e: Throwable => true}

    if (isNewInstance){
      os.write.over(os.home / ".devbox" / s"instance_id-$url", instanceInfo.instanceId())
      val tmp = os.temp(os.read.bytes(os.resource / "agent.jar"))
      os.proc("scp", tmp, s"$url:/home/$userName/.devbox/agent.jar").call(
        mergeErrIntoOut = true,
        stdout = os.ProcessOutput.Readlines(log)
      )
    }

    log(s"Instance $ipAddress is ready at $url")
  }
}