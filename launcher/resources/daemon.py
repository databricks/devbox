"""
Automatically shuts down the devbox after a period of inactivity to save $$$
"""
import subprocess, time, json, sys
print("Starting devbox_daemon.py")
last_seen = time.time()

while True:
    time.sleep(60)

    jps_output = subprocess.check_output(["jps"]).decode()
    print("\n%s" % jps_output)
    if "DevboxAgentMain" in jps_output:
        last_seen = time.time()

    with open("/home/" + sys.argv[1] + "/.devbox/config.json") as conf_file:
        conf = json.load(conf_file)

    inactivity_delay = conf['idle_shutdown_minutes']
    delta = time.time() - last_seen
    print("%s seconds since DevboxAgentMain was active" % delta)
    if delta > 60 * inactivity_delay:
        subprocess.check_call(["sudo", "shutdown", "-P", "now"])
