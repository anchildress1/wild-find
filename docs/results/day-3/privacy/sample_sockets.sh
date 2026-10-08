#!/bin/sh
# Samples the app's sockets from /proc/net every 0.2 s for 90 s; prints proto, remote address, state.
i=0
while [ $i -lt 450 ]; do
  for f in tcp tcp6 udp udp6; do
    awk -v u=10352 -v f=$f 'NR>1 && $8==u {print f, $3, $4}' /proc/net/$f
  done
  sleep 0.2
  i=$((i+1))
done
