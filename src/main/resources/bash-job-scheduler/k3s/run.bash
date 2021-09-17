stdin=$(</dev/stdin)
echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
job_id=$(echo comp-job-$SESSION_ID-$JOB_ID | cut -c 1-63)
if kubectl get pod $job_id > /dev/null 2>&1; then
  echo "pod exists already"
  exit 1
fi
memory=$(( SLOTS*8 ))Gi
cpu_limit=$(( SLOTS*2 ))
cpu_request=$SLOTS

cpu="2"
memory="100M"
job_json=$(echo "$stdin" | yq r - --tojson | jq '
    .spec.metadata.name='"$job_id"' |
    .spec.containers[0].image='"$IMAGE"' |
    .spec.containers[0].command[+]='"$SESSION_ID"' |
    .spec.containers[0].command[+]='"$JOB_ID"' |
    .spec.containers[0].command[+]='"$SESSION_TOKEN"' |
    .spec.containers[0].command[+]='"$COMP_TOKEN"' |
    .spec.containers[0].resources.limits.cpu='"$cpu_limit"' | 
    .spec.containers[0].resources.limits.memory="'"$memory"'" |
    .spec.containers[0].resources.requests.cpu='"$cpu_request"' | 
    .spec.containers[0].resources.requests.memory="'"$memory"'"')

echo "$job_json"
echo "$job_json" | kubectl apply -f -