
# if the process should be running already
if cat logs/SingleShotComp-run-${POD_NAME}.log | grep "\[STATE_RUNNING\]"; then
    # check if the process exists
    ps aux | grep SingleShotComp | grep -v grep | grep $POD_NAME
else
    # otherwise its enough that the building has started (but we won't notice if it fails)
    cat logs/SingleShotComp-run-${POD_NAME}.log | grep "\[STATE_BUILDING\]"
fi
