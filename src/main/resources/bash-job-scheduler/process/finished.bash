pid=$(ps aux | grep SingleShotComp | grep -v grep | grep $POD_NAME | tr -s ' ' | cut -d ' ' -f 2)
found=$(echo "$pid" | wc -l)
if [ -z "$pid" ]; then
    # this is normal when the job has finished
    :
elif [ $found == 1 ]; then
    # kill the process, like the pod would be deleted after exceeding storage limit
    echo "kill $pid"
    kill $pid
else
    echo "found $found processes"
fi

rm logs/SingleShotComp-run-${POD_NAME}.log || true
