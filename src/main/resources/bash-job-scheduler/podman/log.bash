echo "inspect container"

bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body -H content-type:application/json http://d/v4.0.0/libpod/containers/$POD_NAME/json | jq '.State'"

echo "collect logs"

# parse binary output from the /logs

# store log in base64 to avoid problems with null bytes
log=$(bash -c "$ENV_PREFIX_PODMAN_SOCKET -s --fail-with-body http://d/v4.0.0/libpod/containers/$POD_NAME/logs?stdout=true'&'stderr==true | base64")

while [ -n "$log" ]; do
    # first byte is 1 (stdout) or 2 (stderr)
    # stream=$(echo "$log" | base64 -d | od --read-bytes 1 -t u1 --address-radix=n | tr -d ' ')

    # bytes 4-7 is the message length
    # in Ubuntu 24.04 we can read it directly with od
    if ! length=$(echo "$log" | base64 -d | od --skip-bytes 4 --read-bytes 4 --endian=big -t u4 --address-radix=n | tr -d ' '); then
        # in MacOS (but we don't have xxd in Ubuntu, so we can't use this everywhere):
        # - tail to skip 4 bytes, 
        # - xxd to convert to little-endian (4 bytes) hex
        # - xxd -r to convert hex to bytes
        # - od to convert bytes to decimal
        length=$(echo "$log" | base64 -d | tail -c+5 | xxd -e -l 4 | xxd -r | od -t u4 | head -n1 | tr -s ' ' | cut -d " " -f 2)
    fi

    # skip 8 bytes and read the message (should be ASCII)
    message=$(echo "$log" | base64 -d | tail -c+9 | head -c $length)

    echo "$message"

    # delete 8 bytes and the message from the $log
    log=$(echo "$log" | base64 -d | tail -c+$((9+$length)) | base64)
done