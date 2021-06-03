#!/usr/bin/env bash

set -euxo pipefail

echo "Space before cleanup"
df -h

echo "Removing redundant directories"
sudo du -hs /opt/hostedtoolcache/go
#sudo rm -rf /opt/hostedtoolcache/go
sudo du -hs /usr/local/lib/android
#sudo rm -rf /usr/local/lib/android
sudo du -hs /usr/share/dotnet
#sudo rm -rf /usr/share/dotnet

echo "Space after cleanup"
df -h
