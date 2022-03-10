echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
if kubectl get pod $POD_NAME > /dev/null 2>&1; then
  echo "pod exists already"
  exit 1
fi
memory=$(( SLOTS*8 ))Gi
cpu_limit=$(( SLOTS*2 ))
cpu_request=$SLOTS

# openshift doesn't allow the limit to be larger than the quota
max_cpu_limit=8
cpu_limit=$(( cpu_limit <= max_cpu_limit ? cpu_limit : max_cpu_limit))

pod_patch=".metadata.name=\"$POD_NAME\" |
  .spec.containers[0].image=\"$IMAGE\" |
  .spec.containers[0].command += [\"$SESSION_ID\", \"$JOB_ID\", \"$SESSION_TOKEN\"] |
  .spec.containers[0].resources.limits.cpu=\"$cpu_limit\" | 
  .spec.containers[0].resources.limits.memory=\"$memory\" |
  .spec.containers[0].resources.requests.cpu=\"$cpu_request\" | 
  .spec.containers[0].resources.requests.memory=\"$memory\" |
  .spec.containers[0].volumeMounts[1].mountPath=\"$TOOLS_BIN_PATH\" |
  .spec.volumes[1]={\"name\": \"tools-bin\", \"persistentVolumeClaim\": { \"claimName\": \"$TOOLS_BIN_VOLUME\"}}"

if [ -z $STORAGE ]; then
  echo "use emptyDir for working directory"

else
  echo "use PVC for working directory: $STORAGE GiB"

  pod_patch="$jq_patch |
    .spec.volumes[2]={\"name\": \"jobs-data\", \"persistentVolumeClaim\": { \"claimName\": \"$POD_NAME\"}}"

  pvc_patch=".spec.resources.requests.storage=\"${STORAGE}Gi\""

  pvc_json=$(echo "$PVC_YAML" | yq e - -o=json | jq "$pvc_patch")

  echo "$pvc_json" | kubectl apply -f -
fi

job_json=$(echo "$POD_YAML"    | yq e - -o=json | jq "$pod_patch")

echo "$job_json" | kubectl apply -f -