echo "run in process, slots: $SLOTS, image: $IMAGE, pod name: $POD_NAME"

echo env length:  $(env | wc -l) bytes
echo json length: $(echo "$job_json" | wc -l) bytes

bash -c "sleep 5; ~/.sdkman/candidates/java/current/bin/java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN $COMP_TOKEN> logs/SingleShotComp-run-${POD_NAME}.log 2>&1" &
#~/.sdkman/candidates/java/current/bin/java -cp build/tmp/chipster-web-server/lib/*: fi.csc.chipster.comp.SingleShotComp $SESSION_ID $JOB_ID $SESSION_TOKEN $COMP_TOKEN