mill devbox.agent.assembly &&
mill devbox.assembly &&

# ssh -i ~/Downloads/qian-dev.pem ubuntu@54.187.178.201 rm ~/out.jar
# scp -i ~/Downloads/qian-dev.pem out/devbox/agent/assembly/dest/out.jar ubuntu@54.187.178.201:~/out.jar

java -jar out/devbox/assembly/dest/out.jar \
     --log-file log.txt \
     --repo /Users/qianyu/devbox ssh -i ~/Downloads/qian-dev.pem ubuntu@54.187.178.201 java -jar /home/ubuntu/out.jar



# block drop from any to 54.187.178.201