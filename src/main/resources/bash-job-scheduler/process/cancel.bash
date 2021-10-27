pid=$(ps aux | grep SingleShotComp | grep -v grep | grep $POD_NAME | tr -s ' ' | cut -d ' ' -f 2)
found=$(echo "$pid" | wc -l)
if [ -z "$pid" ]; then
    echo 'process not found'
elif [ $found == 1 ]; then
    echo "kill $pid"
    kill $pid
else
    echo "found $found processes"
fi
