echo "start container, slots: $SLOTS, image: $IMAGE, sessionId: $SESSION_ID, jobId: $JOB_ID"
if kubectl get pod $POD_NAME > /dev/null 2>&1; then
  echo "pod exists already"
  exit 1
fi

pod_patch=".metadata.name=\"$POD_NAME\" |
  .spec.containers[0].image=\"$IMAGE\" |
  .spec.containers[0].command += [\"$SESSION_ID\", \"$JOB_ID\", \"$SESSION_TOKEN\"] |
  .spec.containers[0].volumeMounts[1].mountPath=\"$TOOLS_BIN_PATH\" |
  .spec.volumes[1]={\"name\": \"tools-bin\", \"persistentVolumeClaim\": { \"claimName\": \"$TOOLS_BIN_VOLUME\"}}"

if [[ $ENABLE_RESOURCE_LIMITS == "true" ]]; then
  echo "cpu $POD_CPU, memory ${POD_MEMORY}Gi"
  pod_patch="$pod_patch |
    .metadata.name=\"$POD_NAME\" |
    .spec.containers[0].resources.limits.cpu=\"$POD_CPU\" | 
    .spec.containers[0].resources.limits.memory=\"${POD_MEMORY}Gi\" |
    .spec.containers[0].resources.requests.cpu=\"$POD_CPU\" | 
    .spec.containers[0].resources.requests.memory=\"${POD_MEMORY}Gi\" |
    .spec.containers[0].volumeMounts[1].mountPath=\"$TOOLS_BIN_PATH\""
else
  echo "resource limits are disabled"
fi

if [ -n "$STORAGE" ]; then
  echo "use PVC for working directory: $STORAGE GiB"

  pod_patch="$pod_patch |
    .spec.volumes[2]={\"name\": \"jobs-data\", \"persistentVolumeClaim\": { \"claimName\": \"$POD_NAME\"}}"

  pvc_patch=".metadata.name=\"$POD_NAME\" |
    .spec.resources.requests.storage=\"${STORAGE}Gi\" |
    .spec.storageClassName=\"$STORAGE_CLASS\""

  pvc_json=$(echo "$PVC_YAML" | yq e - -o=json | jq "$pvc_patch")

  echo "$pvc_json" | kubectl apply -f -
fi

job_json=$(echo "$POD_YAML"    | yq e - -o=json | jq "$pod_patch")

echo "$job_json" | kubectl apply -f -