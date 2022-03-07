echo "run in process, slots: $SLOTS, image: $IMAGE, pod name: $POD_NAME"

echo "source .bash_profile to set JAVA_HOME when using sdkman"
source ~/.bash_profile

echo "build SingleShotComp"
./gradlew distTar; pushd build/tmp/; tar -xzf ../distributions/chipster-web-server.tar.gz; popd

#bash -c "sleep 5; java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN  > logs/SingleShotComp-run-${POD_NAME}.log 2>&1" &
java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN
