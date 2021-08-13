job_id=$(echo comp-job-$SESSION_ID-$JOB_ID | cut -c 1-63)
json=$(kubectl get pod $job_id -o json | jq .status.containerStatuses[0].state)

if [ "$json" == null ]; then
  echo "pod not found"
  exit 1
fi

running_state=$(echo "$json" | jq .running -r)
if [ "$running_state" != null ]; then
  echo "pod is running"
  exit 0
fi

terminated_reason=$(echo "$json" | jq .terminated.reason -r)
if [ "$terminated_reason" != null ]; then
  echo "pod has terminated: $terminated_reason"
  exit 1
fi

waiting_reason=$(echo "$json" | jq .waiting.reason -r)
if [ "$waiting_reason" != null ]; then
  echo "pod is waiting: $waiting_reason"
  if [ "$waiting_reason" == "ContainerCreating" ]; then
    exit 0
  fi
  exit 1
fi

echo "unknown pod status: $json"
exit 1
