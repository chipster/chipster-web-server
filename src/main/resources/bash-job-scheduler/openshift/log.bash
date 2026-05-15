json=$(kubectl get pod $POD_NAME -o json | jq .status)
short_name=$(echo $JOB_ID | cut -c 1-4)
log="$(kubectl logs $POD_NAME)"

if [ "$json" == null ]; then
    echo "pod $short_name not found"
    exit 1
fi

terminated_reason=$(echo "$json" | jq .containerStatuses[0].state.terminated.reason -r)
if [ "$terminated_reason" != null ]; then              
    if [ "$terminated_reason" == "OOMKilled" ]; then
        echo ""
    echo "The job run out of memory (RAM). You can adjust the memory limit. Click the button "
    echo "\"Parameters\" and then open its last section \"Computing Resources\". "
    echo ""
    else
    # something unexpected happened. maybe the log has some useful information
    echo "$log"
    fi
    echo "pod $short_name has terminated: $terminated_reason"
    exit 0
fi

waiting_reason=$(echo "$json" | jq .containerStatuses[0].state.waiting.reason -r)
if [ "$waiting_reason" != null ]; then
    # ErrImagePull (there is now log yet)
    echo "pod $short_name is waiting: $waiting_reason"
    exit 0
fi

echo "$log"
