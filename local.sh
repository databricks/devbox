mill devbox.agent.assembly &&
mill devbox.assembly &&

ssh -p 2222 root@localhost rm /root/out.jar &&
scp -P 2222 out/devbox/agent/assembly/dest/out.jar root@localhost:/root/out.jar &&

java -jar out/devbox/assembly/dest/out.jar \
     --log-file ../log.txt \
     --healthcheck-interval 5 \
     --retry-interval 20 \
     --repo /Users/qianyu/devbox ssh -p 2222 root@localhost java -jar /root/out.jar

