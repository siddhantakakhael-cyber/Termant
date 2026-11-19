# What the shell inside the app is built from. Change a value here, nowhere else.

# The engine: iSH (https://ish.app), GPL version 3.
ISH_REPO=https://github.com/ish-app/ish
ISH_COMMIT=ce96e698f1820b9d8db47e87c501ecb89119d7ce

# The Linux that runs in it: Alpine's "minirootfs" for 32-bit x86, as Alpine publishes it for Docker.
ROOTFS_NAME=alpine-minirootfs-3.19.9-x86.tar.gz
ROOTFS_URL=https://github.com/alpinelinux/docker-alpine/raw/ee939d52345248420cf62d4606ccc7152bc5a107/x86/$ROOTFS_NAME
ROOTFS_SHA256=8b85e1c9c743704eda40f69cd82c80ac3805732eb0906f35c870b44d91e1818d

# Build tools, installed with pip into ios/ish/work/tools when the computer has none.
MESON_VERSION=1.12.1
NINJA_VERSION=1.13.2
# Its clang and lld build the one small piece that is Linux code (the "vdso"); Xcode has no linker for that.
ZIG_VERSION=0.17.1

# The oldest iOS the app runs on (the same as in ios/project.yml).
IOS_MINIMUM=15.0
