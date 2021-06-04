#!/usr/bin/env bash

set -euxo pipefail

echo "Space before cleanup"
df -h

echo "Removing redundant directories"
sudo du -hs /opt/hostedtoolcache/go
#sudo rm -rf /opt/hostedtoolcache/go
sudo du -hs /usr/local/lib/android
sudo rm -rf /usr/local/lib/android
sudo du -hs /usr/share/dotnet
#sudo rm -rf /usr/share/dotnet

# After removing above we have 28GB space free - let's bring that down to 25GB as reported in the original flaky test issue
dd if=/dev/zero of=space_filler.img bs=1M count=3000

echo "Space after cleanup"
df -h
