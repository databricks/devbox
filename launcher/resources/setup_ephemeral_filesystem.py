#!/usr/bin/sudo python

import os
import subprocess
import sys
import time


def call(cmd):
    print cmd
    return subprocess.check_call(cmd, stdout=sys.stdout, stderr=sys.stderr)


def check_output(cmd):
    return subprocess.check_output(cmd, stderr=sys.stderr)


ec2_instance_type = check_output(["ec2metadata", "--instance-type"]).strip()

def create_raid_disk():
    if "m5d.4xlarge" in ec2_instance_type or "m5d.12xlarge" in ec2_instance_type:
        # If we are on an m5d.4xlarge or 12xlarge, we have multiple ephemeral disks. Use RAID-0 to
        # present them as a single block device
        blk_device = "/dev/md0"
        call(["mdadm", "--create", "--verbose", "--auto=yes", blk_device,
              "--level=0", "--raid-devices=2", "/dev/nvme1n1", "/dev/nvme0n1"])
    elif "m5d.24xlarge" in ec2_instance_type:
        # If we are on a 24xlarge, we have 4 ephemeral disks.
        blk_device = "/dev/md0"
        call(["mdadm", "--create", "--verbose", "--auto=yes", blk_device,
              "--level=0", "--raid-devices=4", "/dev/nvme0n1", "/dev/nvme1n1", "/dev/nvme2n1", "/dev/nvme3n1"])
    else:
        blk_device = "/dev/nvme0n1"
    return blk_device

try:
    blk_device = create_raid_disk()
except subprocess.CalledProcessError:
    time.sleep(5)
    blk_device = create_raid_disk()

call(["mkfs", "-t", "ext4", blk_device])
call(["mkdir", "-p", "/ephemeral"])
call(["mount", "-t", "ext4", blk_device, "/ephemeral", "-o",
      "noatime,data=writeback,barrier=0,nobh,errors=remount-ro"])

MOUNT_DIRS = {
    "/home": ("/ephemeral/home", False),
    "/tmp": ("/ephemeral/tmp", False),
    "/var/lib/docker": ("/ephemeral/docker", True),
}

for mount_point, (ephem_dir, should_bindmount) in MOUNT_DIRS.items():
    call(["rsync", "-a", mount_point, "/ephemeral"])
    if should_bindmount:
        print("Mounting", mount_point, ephem_dir)
        # In some cases we need to bind mount because some applications freak out if they are in a
        # symlinked directory (docker to name one).
        call(["mount", "--bind", ephem_dir, mount_point])
    else:
        print("Symlinking", mount_point, ephem_dir)
        # Delete the target so that we can create a symlink in its place
        call(["rm", "-rf", mount_point])
        call(["ln", "-sf", ephem_dir, os.path.dirname(mount_point)])


# Turn on swap for the ec2 instance
call(["dd", "if=/dev/zero", "of=/home/swapfile1", "bs=8k", "count=625000"])
call(["mkswap", "/home/swapfile1"])
call(["sh", "-c", 'echo "/home/swapfile1 swap swap defaults 0 0" >> /etc/fstab'])
call(["swapon", "-a"])
