#!/bin/sh
# Starts as root, reads the bind-mounted Docker socket's group so the
# unprivileged `floci` user can reach it on any host, then re-executes this
# script as `floci` via coreutils `chroot --userspec`, which also grants that
# group as a supplementary group. The second invocation falls through to exec
# the user's command. No gosu, no usermod: both would cost a package layer the
# base image does not otherwise need.
#
# Why: on native Linux Docker, /var/run/docker.sock is owned by
# root:docker with mode 660 and the docker GID varies by distro. On
# Docker Desktop (macOS/Windows) the socket is typically root:root (GID
# 0). Without the group fix-up below, floci (uid 1001, group 0) can open
# the socket on Docker Desktop but not on native Linux — which breaks
# Azure Functions emulation there. Discovering the GID at runtime handles
# every host transparently.

set -eu

if [ "$(id -u)" = '0' ]; then
    groups='0'
    if [ -S /var/run/docker.sock ]; then
        sock_gid="$(stat -c '%g' /var/run/docker.sock)"
        if [ "$sock_gid" != '0' ]; then
            groups="0,$sock_gid"
        fi
    fi

    # Re-own state dir for the case where a host bind-mount arrives with
    # ownership the floci user cannot write to. Ignore errors (read-only
    # mounts, unusual filesystems) so the container still starts.
    if [ -d /app/data ]; then
        chown -R floci:root /app/data 2>/dev/null || true
    fi

    # `chroot /` changes nothing but the identity: uid 1001, primary gid 0, plus the socket's
    # group. Supplementary groups are set by number, so the group needs no /etc/group entry.
    # --skip-chdir keeps the working directory (/app, where relative data paths resolve); GNU
    # chroot would otherwise chdir to the new root.
    exec chroot --userspec=1001:0 --groups="$groups" --skip-chdir / "$0" "$@"
fi

exec "$@"
