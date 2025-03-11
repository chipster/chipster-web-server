pid=$(ps aux | grep SingleShotComp | grep -v grep | grep -v "bash -c" | grep $JOB_ID | tr -s ' ' | cut -d ' ' -f 2)
found=$(echo "$pid" | wc -l)
if [ -z "$pid" ]; then
    echo 'process not found'
elif [ $found == 1 ]; then
    echo "kill: $pid"
    kill $pid
else
    echo "found $found processes"
fi
