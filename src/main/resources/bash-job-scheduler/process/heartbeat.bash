
# if the process should be running already
if cat logs/SingleShotComp-run-${POD_NAME}.log | grep "\[STATE_RUNNING\]"; then
    # check if the process exists
    ps aux | grep SingleShotComp | grep -v grep | grep $POD_NAME
else
    # otherwise it's enough that run.bash has started, it starts the process right after this
    cat logs/SingleShotComp-run-${POD_NAME}.log | grep "\[STATE_STARTING\]"
fi
