pid=$(ps aux | grep SingleShotComp | grep $SESSION_ID | grep $JOB_ID | tr -s ' ' | cut -d ' ' -f 2)
if [ -z $pid ]; then
    echo 'process not found'
else
    kill $pid
fi