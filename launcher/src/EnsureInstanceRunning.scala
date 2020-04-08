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
      "init-file", None,
      "Copy a file to the instance when first created. Note that this only supports text files, " +
        "and is limited by the 16k maximum userdata size on Amazon EC2. For larger files, consider " +
        "using --setup-file which is copied over once the instance has fully initialized. Can be " +
        "passed more than once, and interleaved with `--init-command`s",
      (c, v) => c.copy(initFilesAndCommands =
        c.initFilesAndCommands :+ (v.split(":", 2) match{
          case Array(k, v) => Left((v, os.read(os.Path(k, os.pwd))))
        })
      )
    ),
    Arg[EnsureInstanceRunning, String](
      "setup-file", None,
      "Copy a file to the instance after the `--init-files` and `--init-commands` handling is complete. " +
        "supports binary files and files of arbitrary size. " +
        "Can be passed more than once, and interleaved with `--setup-command`s",
      (c, v) => c.copy(setupFilesAndCommands =
        c.setupFilesAndCommands :+ (v.split(":", 2) match{
          case Array(k, v) => Left((v, os.read.bytes(os.Path(k, os.pwd))))
        })
      )
    ),

    Arg[EnsureInstanceRunning, String](
      "url", None,
      "The URL you want your instance to live at; e.g. --url devbox.databricks.com " +
        "will adjust your ~/.ssh/config file to allow you to connect to your instance " +
        "via `ssh devbox.databricks.com`",
      (c, v) => c.copy(url = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "init-command", None,
      "Commands you want to run during early devbox initialization, as part of the " +
        "AWS userdata script. Can be passed more than once, and interleaved with `--init-file`s" +
        "",
      (c, v) => c.copy(initFilesAndCommands = c.initFilesAndCommands :+ Right(v))
    ),
    Arg[EnsureInstanceRunning, String](
      "setup-command", None,
      "Can be passed more than once, and interleaved with `--setup-file`s",
      (c, v) => c.copy(setupFilesAndCommands = c.setupFilesAndCommands :+ Right(v))
    ),

    Arg[EnsureInstanceRunning, Unit](
      "new-instance", None,
      "Create a new instance rather than re-using an existing one; " +
        "useful if your instance is in a bad state and you want to start a fresh",
      (c, v) => c.copy(forceNewInstance = true)
    ),

    Arg[EnsureInstanceRunning, String](
      "aws-instance-type", None,
      "What kind of instance to use; you can specify a bigger instance or more specialized instance if necessary",
      (c, v) => c.copy(instanceType = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-ami-id", None,
      "What AMI to use; defaults to the same AMI as used for our jenkins slaves",
      (c, v) => c.copy(amiIdOpt = Some(v))
    ),
    Arg[EnsureInstanceRunning, Unit](
      "ephemeral", None,
      "Use ephemeral local filesystem instead of EBS. Ephemeral instances have much " +
      "better disk performance than EBS-backed instances, but lose all their disk state " +
      "and need to be restarted and re-syncedfrom scratch every time they shut down. " +
      "must be used with a ephemeral-disk-compatible instance, e.g. --instance-type m5d.4xlarge",
      (c, v) => c.copy(ephemeral = true)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-security-group-id", None,
      "Add a security group to your EC2 instance (can be passed more than once)",
      (c, v) => c.copy(securityGroupIds = c.securityGroupIds :+ v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-subnet-id", None,
      "",
      (c, v) => c.copy(subnetId = Some(v))
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-access-key-id", None,
      "",
      (c, v) => c.copy(awsAccessKeyId = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-secret-access-key", None,
      "",
      (c, v) => c.copy(awsSecretAccessKey = v)
    ),
    Arg[EnsureInstanceRunning, Int](
      "aws-ebs-volume-size", None,
      "How big do you want your EBS volume to be?",
      (c, v) => c.copy(ebsVolumeSize = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-ebs-volume-type", None,
      "What kind of EBS volume do you want? e.g. gp2, io1, st1 or sc1",
      (c, v) => c.copy(ebsVolumeType = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-iam-instance-arn", None,
      "Any IAM role that you want to give this instance",
      (c, v) => c.copy(ebsVolumeType = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-instance-tag", None,
      "Add a tag to give this instance, e.g. 'foo=bar' (can be passed more than once)",
      (c, v) => c.copy(tags = c.tags :+ (v.split("=", 2) match{case Array(k, v) => (k, v)}))
    ),
    Arg[EnsureInstanceRunning, String](
      "aws-region", None,
      "What AWS region to create your instance in",
      (c, v) => c.copy(awsRegion0 = v)
    ),

    Arg[EnsureInstanceRunning, String](
      "prepare-instance-command", None,
      "",
      (c, v) => c.copy(prepareInstanceCommand = Some(v))
    ),
    Arg[EnsureInstanceRunning, String](
      "python-daemon-executable", None,
      "",
      (c, v) => c.copy(pythonExecutable = v)
    ),
    Arg[EnsureInstanceRunning, String](
      "devbox-vm-version", None,
      "",
      (c, v) => c.copy(devboxVmVersion = v)
    ),
    Arg[EnsureInstanceRunning, Unit](
      "no-terminate", None,
      "",
      (c, v) => c.copy(noTerminate = true)
    ),
  )
  def main(args: Array[String]): Unit = {
    Cli.groupArgs(args.toList, signature, EnsureInstanceRunning()) match {
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((config, remaining)) =>
        println(config)
        println(remaining)
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
                                 awsSecretAccessKey: String = "",
                                 setupFilesAndCommands: Seq[Either[(String, Array[Byte]), String]] = Nil,
                                 forceNewInstance: Boolean = false,
                                 amiIdOpt: Option[String] = None,
                                 sshConfigPath: os.Path = os.home / ".ssh" / "config",
                                 securityGroupIds: Seq[String] = Nil,
                                 subnetId: Option[String] = None,
                                 ebsVolumeSize: Int = 500,
                                 ebsVolumeType: String = "gp2",
                                 iamInstanceArn: Option[String] = None,
                                 tags: Seq[(String, String)] = Nil,
                                 awsRegion0: String = "us-west-2",
                                 prepareInstanceCommand: Option[String] = None,
                                 url: String = "",
                                 initFilesAndCommands: Seq[Either[(String, String), String]] = Nil,
                                 pythonExecutable: String = "/usr/bin/python3",
                                 devboxVmVersion: String = "101",
                                 noTerminate: Boolean = false){
  def main0(log: String => Unit) = {
    assert(url != "", "--url parameter is required to define the URL of the devbox")
    val awsRegion = software.amazon.awssdk.regions.Region.of(awsRegion0)
    val ec2 = prepareEc2Client(awsRegion)
    val (instanceName, sshPrivateKey, pubKey) = prepareKeysAndNames()

    val allInitFilesAndCommands = Seq(
      Left(s"/home/$userName/.ssh/authorized_keys" -> pubKey),
      Left(
        s"/home/$userName/.devbox/config.json" ->
          os.read(os.resource / "default_config.json")
      ),
      Left(
        s"/home/$userName/.devbox/daemon.py" ->
          os.read(os.resource / "daemon.py")
      ),
      Left(
        s"/home/$userName/.devbox/setup_ephemeral_filesystem.py" ->
          os.read(os.resource / "setup_ephemeral_filesystem.py")
      ),
      Left(
        "/lib/systemd/system/devbox_daemon.service" ->
          os.read(os.resource / "daemon.service")
            .replace("$DEVBOX_USER", userName)
            .replace("$PYTHON_EXECUTABLE", pythonExecutable)
      ),
      Right("systemctl enable devbox_daemon"),
      Right("systemctl start devbox_daemon"),
    ) ++ initFilesAndCommands

    val userdata = s"""#!/bin/bash
      export DEVBOX_USER=$userName
      sudo usermod -md /home/$userName -l $userName ubuntu
      sudo sh -c "echo '$userName ALL=NOPASSWD: ALL' > /etc/sudoers.d/devbox-user"
      mkdir -p /home/$userName/.ssh
      mkdir -m777 -p /home/$userName/.devbox

      ${
        allInitFilesAndCommands
          .map{
            case Left((dest, data)) =>
              s"""echo '${data.replace("'", """'"'"'""")}'> $dest"""
            case Right(cmd) => cmd
          }
          .mkString("\n")
      }
      ${if (ephemeral) s"$pythonExecutable /home/$userName/setup_ephemeral_filesystem.py" else ""}
    """

    val instanceInfo = launcher.Instance.ensureInstanceRunning(
      ec2,
      instanceType,
      // If no --aws-ami-id is provided, we default to the latest Ubuntu 18.04 LTS
      // hvm:ebs-ssd images available here https://cloud-images.ubuntu.com/locator/ec2/
      amiIdOpt.getOrElse(awsRegion match{
        case software.amazon.awssdk.regions.Region.US_WEST_1 => "ami-0d58800f291760030"
        case software.amazon.awssdk.regions.Region.US_WEST_2 => "ami-0edf3b95e26a682df"
        case software.amazon.awssdk.regions.Region.US_EAST_1 => "ami-046842448f9e74e7d"
        case software.amazon.awssdk.regions.Region.US_EAST_2 => "ami-0367b500fdcac0edc"
        case software.amazon.awssdk.regions.Region.AP_NORTHEAST_1 => "ami-01f90b0460589991e"
        case software.amazon.awssdk.regions.Region.AP_NORTHEAST_2 => "ami-096e3ded41e3bda6a"
        case software.amazon.awssdk.regions.Region.AP_SOUTH_1 => "ami-0d11056c10bfdde69"
        case software.amazon.awssdk.regions.Region.AP_SOUTHEAST_1 => "ami-07ce5f60a39f1790e"
        case software.amazon.awssdk.regions.Region.AP_SOUTHEAST_2 => "ami-04c7af7de7ad468f0"
        case software.amazon.awssdk.regions.Region.CA_CENTRAL_1 => "ami-064efdb82ae15e93f"
        case software.amazon.awssdk.regions.Region.EU_CENTRAL_1 => "ami-0718a1ae90971ce4d"
        case software.amazon.awssdk.regions.Region.EU_NORTH_1 => "ami-0e850e0e9c20d9deb"
        case software.amazon.awssdk.regions.Region.EU_WEST_1 => "ami-07042e91d04b1c30d"
        case software.amazon.awssdk.regions.Region.EU_WEST_2 => "ami-04cc79dd5df3bffca"
        case software.amazon.awssdk.regions.Region.EU_WEST_3 => "ami-0c367ebddcf279dc6"
      }),
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
      log,
      devboxVmVersion,
      noTerminate
    )

    val ipAddress = instanceInfo.publicIpAddress()

    mangleSshConfig(sshConfigPath, ipAddress, url, sshPrivateKey, userName, log)
    mangleKnownHosts(ipAddress, log)

    pollUntilConnected(log, ipAddress)

    val instanceIdFile = os.home / ".devbox" / s"instance_id-$url"

    val isNewInstance =
      try os.read(instanceIdFile) != instanceInfo.instanceId()
      catch{case e: Throwable => true}

    if (isNewInstance){
      initializeNewInstance(log)
      os.write.over(instanceIdFile, instanceInfo.instanceId())
    }

    log(s"Instance $ipAddress is ready at $url")
  }

  def prepareEc2Client(awsRegion: software.amazon.awssdk.regions.Region) = {
    val httpClient = software.amazon.awssdk.http.apache.ApacheHttpClient.builder().build()
    (awsAccessKeyId, awsSecretAccessKey) match{
      case ("", "") =>
        Ec2Client
          .builder()
          .httpClient(httpClient)
          .region(awsRegion)
          .build()
      case _ =>
        Ec2Client
          .builder()
          .httpClient(httpClient)
          .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey))
          )
          .region(awsRegion)
          .build()
    }
  }

  def prepareKeysAndNames() = {


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
    val pubkeyHash = software.amazon.awssdk.utils.internal.Base16.encodeAsString(m.digest():_*).toLowerCase

    val instanceName = s"devbox-$emailPrefix-$url-$pubkeyHash"
    (instanceName, sshPrivateKey, pubKey)
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

  def pollUntilConnected(log: String => Unit, ipAddress: String) = {
    var connectCount = 0

    while({
      log(s"Attempting to connect to devbox $ipAddress")
      val javaVersion = os
        .proc("ssh", "-o", "ConnectTimeout=4", url, "java -version")
        .call(check = false)

      if (javaVersion.exitCode == 0) false
      else{
        connectCount += 1
        if (connectCount == 60) {
          throw new Exception(s"Failed to connect to $url after 5 attempts")
        } else {
          Thread.sleep(2000)
          true
        }
      }
    })()
  }

  def initializeNewInstance(log: String => Unit) = {
    val tmp = os.temp(os.read.bytes(os.resource / "agent.jar"))
    os.proc("scp", tmp, s"$url:/home/$userName/.devbox/agent.jar").call(
      mergeErrIntoOut = true,
      stdout = os.ProcessOutput.Readlines(log)
    )

    os.proc(
      "ssh",
      url,
      s"HOME=/root sudo java -cp /home/$userName/.devbox/agent.jar devbox.agent.DevboxSetupMain"
    ).call(
      mergeErrIntoOut = true,
      stdin = upickle.default.writeBinary(setupFilesAndCommands),
      stdout = os.ProcessOutput.Readlines(log)
    )
  }
}