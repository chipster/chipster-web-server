echo "run in process, slots: $SLOTS, image: $IMAGE, pod name: $POD_NAME"

echo "source .bash_profile to set JAVA_HOME when using sdkman"
source ~/.bash_profile


echo "build SingleShotComp"
./gradlew clean;./gradlew distTar; pushd build/tmp/; tar -xzf ../distributions/chipster-web-server.tar.gz; popd

for prefixed_name in $(env | grep "ENV_PREFIX_" | cut -d "=" -f 1); do
    name="$(echo "$prefixed_name" | sed s/ENV_PREFIX_//)"
    value="${!prefixed_name}"
    export $name="$value"
done

bash -c "java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN  > logs/SingleShotComp-run-${POD_NAME}.log 2>&1" &
#bash -c "sleep 5; java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN  > logs/SingleShotComp-run-${POD_NAME}.log 2>&1" &
#java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN
